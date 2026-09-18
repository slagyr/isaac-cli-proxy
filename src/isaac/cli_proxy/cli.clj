(ns isaac.cli-proxy.cli
  "CLI command `remote`: opens a WebSocket to a server's /cli endpoint, ships the
   handshake argv, pipes local stdin/stdout/stderr, and exits with the server's
   reported exit code.

   Generalizes the `acp --remote` proxy (ws connect, reconnect, stdio relay) but
   command-agnostic. The ws client uses the JDK's java.net.http.WebSocket.

   See PROTOCOL.md for the wire contract."
  (:require
    [clojure.string :as str]
    [isaac.cli.api :as cli-api]
    [isaac.cli.registry :as registry]
    [isaac.cli-proxy.proxy :as proxy]
    [isaac.cli-proxy.token :as token]
    [isaac.config.cli.common :as cli-common]
    [isaac.config.pointer :as pointer]))

(def option-spec
  [[nil "--token TOKEN" "Deprecated: bearer token in argv (use a secure source below)"]
   [nil "--token-file PATH" "Read bearer token from a mode-600 file"]
   [nil "--token-env VAR" "Read bearer token from the named environment variable"]
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

(defn- use-remote! [args]
  (let [{:keys [url token-file token-env errors]} (parse-remote-opts args)]
    (cond
      (seq errors) (cli-common/print-cli-errors! errors)
      (str/blank? url) (cli-common/print-cli-error! "remote use: missing WebSocket URL")
      :else
      (let [literal (when token-file (str/trim (slurp token-file)))
            token   (cond literal literal token-env (str "${" token-env "}"))
            config  (assoc-in (pointer/read-config) [:cli :remote] (cond-> {:url url} token (assoc :token token)))]
        (pointer/write-config! config (boolean literal))
        (println (str "remote CLI configured: " url))
        0))))

(defn- off-remote! []
  (let [config (update (pointer/read-config) :cli #(not-empty (dissoc (or % {}) :remote)))]
    (pointer/write-config! (if (:cli config) config (dissoc config :cli)) false)
    (println "remote CLI disabled")
    0))

(declare run)

(defn- status-remote! [opts]
  (if-let [remote (get-in (pointer/read-config) [:cli :remote])]
    (let [code (run (assoc opts :_raw-args [(:url remote) "--" "--version"]))]
      (println (str (:url remote) (if (zero? code) " reachable" " unreachable")))
      code)
    (do (println "remote CLI not configured") 0)))

(defn run [opts]
  (let [raw (vec (:_raw-args opts))
        sub (first raw)]
    (cond
      (= "use" sub) (use-remote! (subvec raw 1))
      (= "off" sub) (off-remote!)
      (= "status" sub) (status-remote! opts)
      :else
      (let [{:keys [url remote-argv token token-file token-env help errors]}
        (parse-remote-opts (:_raw-args opts))
        resolve-token (or (:token-resolver opts) token/resolve-system-token)]
    (cond
      help
      (do (println (registry/command-help (registry/get-command "remote"))) 0)

      (seq errors)
      (cli-common/print-cli-errors! errors)

      (or (nil? url) (str/blank? url))
      (cli-common/print-cli-error! "remote: missing WebSocket URL")

      :else
      (let [resolved (when-not token
                       (resolve-token {:url url :token-file token-file :token-env token-env}))]
        (cond
          token
          (do
            (warn-deprecated-token!)
            (proxy/run-proxy! {:url url :argv remote-argv :token token
                               :connection-factory (:connection-factory opts)}))

          (:error resolved)
          (cli-common/print-cli-error! (:error resolved))

          :else
          (do
            (when-not (:token resolved)
              (binding [*out* *err*]
                (println "isaac remote: no bearer token resolved; tried --token-file, --token-env, ISAAC_REMOTE_TOKEN, and ~/.config/isaac.edn")))
            (proxy/run-proxy! {:url url :argv remote-argv :token (:token resolved)
                               :connection-factory (:connection-factory opts)})))))))))

(defn run-fn [opts]
  (run opts))

(defn make-command
  "Factory for feature tests and module registration."
  []
  {:name        "remote"
   :usage       "remote <url>/cli -- <command...>"
   :summary     "Run an isaac command on a remote server over /cli"
   :option-spec option-spec
   :run-fn      run-fn})

(defmethod cli-api/run :remote [_id opts]
  (run-fn opts))