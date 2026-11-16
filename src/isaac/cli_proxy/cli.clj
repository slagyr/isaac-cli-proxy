(ns isaac.cli-proxy.cli
  "CLI command `remote`: opens a WebSocket to a server's /cli endpoint, ships the
   handshake argv, pipes local stdin/stdout/stderr, and exits with the server's
   reported exit code.

   Generalizes the `acp --remote` proxy (ws connect, reconnect, stdio relay) but
   command-agnostic. The ws client uses the JDK's java.net.http.WebSocket.

   See PROTOCOL.md for the wire contract."
  (:require
    [clojure.string :as str]
    [isaac.foundation.cli.api :as cli-api]
    [isaac.foundation.cli.registry :as registry]
    [isaac.cli-proxy.proxy :as proxy]
    [isaac.cli-proxy.token :as token]
    [isaac.foundation.config.cli.common :as cli-common]
    [isaac.cli-proxy.config.pointer :as pointer]))

(def option-spec
  [[nil "--token TOKEN" "Deprecated: bearer token in argv (use a secure source below)"]
   [nil "--token-file PATH" "Read bearer token from a mode-600 file"]
   [nil "--token-env VAR" "Read bearer token from the named environment variable"]
   [nil "--token-command CMD" "Run a command to retrieve the token (with remote use <name> <url>)"]
   ["-h" "--help" "Show help"]])

(defmethod cli-api/option-spec :remote [_id]
  option-spec)

(defn- parse-remote-opts [raw-args]
  (let [{:keys [arguments errors options]}
        (cli-common/parse-option-map (vec (or raw-args [])) option-spec)]
    (cond-> {:errors (or errors [])}
      (seq errors)  identity
      :else         (assoc :url (first arguments)
                           :remote-argv (vec (rest arguments))
                           :token (:token options)
                           :token-file (:token-file options)
                           :token-env (:token-env options)
                           :help (:help options)))))

(defn- warn-deprecated-token! []
  (binding [*out* *err*]
    (println "isaac remote: --token exposes the secret in the process list; use --token-file, --token-env, or ISAAC_REMOTE_TOKEN")))

(defn- remotes []
  (get-in (pointer/read-config) [:cli :remotes] {}))

(defn- remote-name [name]
  (keyword name))

(defn- unknown-remote! [name]
  (cli-common/print-cli-error!
    (str "unknown remote: " name " (configured: "
         (str/join ", " (sort (map clojure.core/name (keys (remotes))))) ")")))

(defn- use-remote! [args]
  (let [{:keys [arguments errors options]} (cli-common/parse-option-map (vec args) option-spec)
        [name url] arguments
        token-file (:token-file options)
        token-env (:token-env options)
        token-command (:token-command options)]
    (cond
      (seq errors) (cli-common/print-cli-errors! errors)
      (or (str/blank? name) (str/blank? url))
      (cli-common/print-cli-error! "remote use: missing name or WebSocket URL")
      :else
      (let [resolved (when token-file (token/resolve-system-token {:token-file token-file}))]
        (if (:error resolved)
          (cli-common/print-cli-error! (:error resolved))
          (let [value  (cond token-file (:token resolved)
                             token-env (str "${" token-env "}")
                             token-command {:command token-command})
                config (assoc-in (pointer/read-config) [:cli :remotes (remote-name name)]
                                 (cond-> {:url url} value (assoc :token value)))]
            (pointer/write-config! config (boolean token-file))
            (println (str "remote CLI configured: " name " " url))
            0))))))

(defn- remote-help []
  (str/join "\n"
            (concat [(registry/command-help (assoc (registry/get-command "remote")
                                                   :id nil :help-text nil :option-spec option-spec))
                     "       isaac remote <url>/cli -- <command...>"
                     "       isaac remote use <name> <url> [--token-env VAR | --token-command CMD | --token-file PATH]"
                     "       isaac remote list"
                     "       isaac remote remove <name>"
                     "       isaac remote status [<name>]"
                     "Remotes live in ~/.config/isaac.edn under :cli :remotes."]
                    (when (seq (remotes))
                      (concat ["Configured remotes:"]
                              (for [[name {:keys [url]}] (sort-by (comp clojure.core/name key) (remotes))]
                                (str (clojure.core/name name) "  " url "  isaac remote "
                                     (clojure.core/name name) " -- <command>")))))))

(defmethod cli-api/help :remote [_id]
  (remote-help))

(defn- help-remote! []
  (println (cli-api/help :remote))
  0)

(defn- list-remotes! []
  (doseq [[name {:keys [url]}] (sort-by (comp clojure.core/name key) (remotes))]
    (println (str (clojure.core/name name) "  " url "  isaac remote "
                  (clojure.core/name name) " -- <command>")))
  0)

(defn- remove-remote! [name]
  (if (contains? (remotes) (remote-name name))
    (let [config (update-in (pointer/read-config) [:cli :remotes] dissoc (remote-name name))]
      (pointer/write-config! config false)
      (println (str "remote removed: " name))
      0)
    (unknown-remote! name)))

(declare run)

(defn- status-remote! [opts name]
  (if (and (nil? name) (empty? (remotes)))
    (do (println "no remotes configured") 0)
    (if-let [remote (get (remotes) (remote-name name))]
      (let [code (run (assoc opts :_raw-args [name "--" "--version"]))]
        (println (str (:url remote) (if (zero? code) " reachable" " unreachable")))
        code)
      (unknown-remote! (or name "")))))

(defn run [opts]
  (let [raw (vec (:_raw-args opts))
        sub (first raw)]
    (cond
      (= "use" sub) (use-remote! (subvec raw 1))
      (= "list" sub) (list-remotes!)
      (= "remove" sub) (remove-remote! (second raw))
      (= "status" sub) (status-remote! opts (second raw))
      :else
      (let [{:keys [url remote-argv token token-file token-env help errors]}
        (parse-remote-opts (:_raw-args opts))
        resolve-token (or (:token-resolver opts) token/resolve-system-token)
        named? (and url (not (str/includes? url "://")))
        named (when named? (get (remotes) (remote-name url)))
        name (when named? url)
        target-url (if named? (:url named) url)]
    (cond
      help
      (help-remote!)

      (seq errors)
      (cli-common/print-cli-errors! errors)

      (or (nil? url) (str/blank? url))
      (cli-common/print-cli-error! "remote: missing WebSocket URL")

      (and named? (nil? named))
      (unknown-remote! name)

      :else
      (let [resolved (when-not token
                       (resolve-token {:url target-url :name name :home-config named
                                       :token-file token-file :token-env token-env}))]
        (cond
          token
          (do
            (warn-deprecated-token!)
            (proxy/run-proxy! {:url target-url :argv remote-argv :token token
                               :connection-factory (:connection-factory opts)}))

          (:error resolved)
          (cli-common/print-cli-error! (:error resolved))

          :else
          (do
            (when-not (:token resolved)
              (binding [*out* *err*]
                (println "isaac remote: no bearer token resolved; tried --token-file, --token-env, ISAAC_REMOTE_TOKEN, and ~/.config/isaac.edn")))
            (proxy/run-proxy! {:url target-url :argv remote-argv :token (:token resolved)
                               :connection-factory (:connection-factory opts)})))))))))

(defn run-fn [opts]
  (run opts))

(defn make-command
  "Factory for feature tests and module registration."
  []
  {:name        "remote"
   :usage       "remote <name> -- <command...>"
   :summary     "Run an isaac command on a remote server over /cli"
   :option-spec option-spec
   :help-text   remote-help
   :run-fn      run-fn})

(defmethod cli-api/run :remote [_id opts]
  (run-fn opts))