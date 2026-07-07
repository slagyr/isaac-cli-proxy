(ns isaac.cli-proxy.integration-steps
  "End-to-end harness for features/integration.feature: ensure the cli-server
   /cli route is visible to the server harness (classpath discovery plus an
   explicit inject fallback for sibling checkouts or git-pinned CI)."
  (:require
    [babashka.process :as process]
    [cheshire.core :as json]
    [clojure.edn :as edn]
    [clojure.java.io :as io]
    [clojure.string :as str]
    [gherclj.core :as g :refer [defgiven defwhen defthen helper!]]
    [isaac.cli.registry :as cli-registry]
    [isaac.cli-proxy.cli :as remote-cli]
    [isaac.foundation.cli-steps :as cli-steps]
    [isaac.fs :as fs]
    [isaac.nexus :as nexus]
    [isaac.server.server-steps :as server-steps]
    [isaac.util.jsonrpc :as jrpc])
  (:import
    (java.io BufferedReader InputStreamReader OutputStreamWriter)
    (java.util.concurrent LinkedBlockingQueue TimeUnit)))

(helper! isaac.cli-proxy.integration-steps)

(cli-registry/register! (remote-cli/make-command))

(def ^:private cli-server-git-coord
  {:git/url "https://github.com/slagyr/isaac-cli-server.git"
   :git/sha "cf80a294fc60b4f45f7d68a7dc559a22af2c27f2"})

(def ^:private acp-module-coord
  {:git/url "https://github.com/slagyr/isaac-acp.git"
   :git/sha "3b48d9777567e2621b21361e404fc336350c8993"})

(def ^:private interactive-timeout-ms 15000)
(def ^:private interactive-eof ::interactive-eof)
(def ^:private fixture-session-name "lcay-session")
(def ^:private interactive-client-expr
  "(require '[isaac.cli-proxy.cli :as remote-cli]) (System/exit (remote-cli/run-fn {:_raw-args (vec *command-line-args*)}))")

(defn- ensure-remote-command! []
  (when-not (cli-registry/get-command "remote")
    (cli-registry/register! (remote-cli/make-command))))

(cli-steps/register-isaac-run-preflight! ensure-remote-command!)

(defn- cli-server-sibling-root []
  (let [f (io/file "../isaac-cli-server")]
    (when (.exists f)
      (.getAbsolutePath f))))

(defn- cli-server-manifest-from-url [url]
  (some-> url io/reader slurp edn/read-string))

(defn- cli-server-manifest-from-classpath []
  (when-let [loader (.getContextClassLoader (Thread/currentThread))]
    (some (fn [url]
            (let [manifest (cli-server-manifest-from-url url)]
              (when (= :isaac.cli-server (:id manifest))
                manifest)))
          (enumeration-seq (.getResources loader "isaac-manifest.edn")))))

(defn- cli-server-manifest []
  (or (some-> (cli-server-sibling-root)
              (io/file "src/isaac-manifest.edn")
              slurp
              edn/read-string)
      (cli-server-manifest-from-classpath)))

(defn- cli-server-module-coord []
  (if-let [root (cli-server-sibling-root)]
    {:isaac.cli-server {:local/root root}}
    {:isaac.cli-server cli-server-git-coord}))

(defn- cli-server-module-index [manifest]
  (if-let [root (cli-server-sibling-root)]
    {:isaac.cli-server {:coord    {:local/root root}
                        :manifest manifest
                        :path     nil}}
    {:isaac.cli-server {:coord    cli-server-git-coord
                        :manifest manifest
                        :path     nil}}))

(defn- feature-fs []
  (or (g/get :mem-fs) (nexus/get :fs) (fs/real-fs)))

(defn- persist-cli-server-module! []
  (when-let [state-root (or (g/get :runtime-root-dir) (g/get :root))]
    (nexus/-with-nested-nexus {:fs (feature-fs)}
      (fn []
        (let [path    (str state-root "/config/isaac.edn")
              fs*     (feature-fs)
              current (if (fs/exists? fs* path)
                        (edn/read-string (fs/slurp fs* path))
                        {})
              updated (update current :modules merge (cli-server-module-coord))]
          (fs/mkdirs fs* (fs/parent path))
          (fs/spit fs* path (pr-str updated)))))))

(defn ensure-cli-server-route! []
  (when-let [manifest (cli-server-manifest)]
    (g/update! :server-config
               #(-> (or % {})
                    (update :modules merge (cli-server-module-coord))
                    (update :inject-module-index
                            merge (cli-server-module-index manifest))))
    (persist-cli-server-module!)))

(defn remote-cli-ready []
  (ensure-remote-command!)
  (ensure-cli-server-route!))

(defn- close-quietly! [closeable]
  (when closeable
    (try
      (.close closeable)
      (catch Exception _))))

(defn- destroy-quietly! [proc]
  (when-let [process (:proc proc)]
    (try
      (.destroy ^Process process)
      (catch Exception _))))

(defn- delete-tree! [path]
  (let [dir (io/file path)]
    (when (.exists dir)
      (doseq [f (reverse (file-seq dir))]
        (.delete f)))))

(defn- fixture-root []
  (str (System/getProperty "user.dir") "/target/lcay-fixture-root"))

(defn- write-text! [path content]
  (.mkdirs (.getParentFile (io/file path)))
  (spit path content))

(defn- ensure-acp-fixture-root! []
  (let [root (fixture-root)]
    (delete-tree! root)
    (write-text! (str root "/config/isaac.edn")
                 (pr-str {:defaults {:crew "main" :model "grover"}
                          :crew     {"main" {:model :grover :soul "You are Atticus."}}
                          :models   {"grover" {:model "echo" :provider "grover:openai" :context-window 32768}}
                          :providers {}
                          :modules  {:isaac.comm.acp acp-module-coord}}))
    (g/assoc! :fixture-root root)
    root))

(defn- server-url []
  (or (g/get :server-url)
      (when-let [port (g/get :server-port)]
        (str "ws://localhost:" port "/cli"))))

(defn real-cli-server-backed-by-installed-isaac-with-echo-model []
  (ensure-cli-server-route!)
  (ensure-acp-fixture-root!)
  (g/update! :server-config
             #(-> (or % {})
                  (assoc-in [:server :host] "127.0.0.1")
                  (assoc-in [:server :port] 0)))
  (server-steps/server-running)
  (g/assoc! :server-url (server-url)))

(defn- parse-json-line [line]
  (try
    (json/parse-string line true)
    (catch Exception _
      {:raw line})))

(defn- stdout-lines* []
  (g/get :interactive-stdout-lines*))

(defn- stderr-lines* []
  (g/get :interactive-stderr-lines*))

(defn- stdout-text []
  (str/join "\n" @(stdout-lines*)))

(defn- stderr-text []
  (str/join "\n" @(stderr-lines*)))

(defn- remaining-ms [deadline-ms]
  (max 1 (- deadline-ms (System/currentTimeMillis))))

(defn- await-stdout-message! [pred description]
  (let [deadline (+ (System/currentTimeMillis) interactive-timeout-ms)
        queue    (g/get :interactive-stdout-queue)]
    (loop [seen []]
      (let [item (.poll ^LinkedBlockingQueue queue (remaining-ms deadline) TimeUnit/MILLISECONDS)]
        (cond
          (nil? item)
          (throw (ex-info (str "Timed out waiting for " description)
                          {:stdout (stdout-text) :stderr (stderr-text) :seen seen}))

          (= interactive-eof item)
          (throw (ex-info (str "Reached EOF before " description)
                          {:stdout (stdout-text) :stderr (stderr-text) :seen seen}))

          :else
          (let [message (parse-json-line item)]
            (if (pred item message)
              message
              (recur (conj seen item)))))))))

(defn- await-session-id! []
  (or (g/get :interactive-session-id)
      (let [message (await-stdout-message!
                      (fn [_line message]
                        (and (= 2 (:id message))
                             (get-in message [:result :sessionId])))
                      "ACP session/new result")
            session-id (get-in message [:result :sessionId])]
        (g/assoc! :interactive-session-id session-id)
        session-id)))

(defn- start-line-drain! [reader]
  (let [queue  (LinkedBlockingQueue.)
        lines* (atom [])
        run    (bound-fn []
                 (try
                   (loop []
                     (if-let [line (.readLine ^BufferedReader reader)]
                       (do
                         (swap! lines* conj line)
                         (.put queue line)
                         (recur))
                       (.put queue interactive-eof)))
                   (finally
                     (close-quietly! reader))))]
    {:queue  queue
     :lines* lines*
     :future (future (run))}))

(defn- interactive-client-command [argv]
  (into ["bb" "-e" interactive-client-expr "--"] argv))

(defn- interpolate-interactive-args [args]
  (-> args
      (str/replace "${server.url}" (or (server-url) ""))
      (str/replace "${fixture.root}" (or (g/get :fixture-root) (fixture-root)))
      (str/replace "\\\"" "\"")))

(defn- reset-interactive-state! []
  (close-quietly! (g/get :interactive-stdin-writer))
  (destroy-quietly! (g/get :interactive-proc))
  (doseq [fut [(g/get :interactive-client-future)
               (g/get :interactive-stdout-future)
               (g/get :interactive-stderr-future)]]
    (when fut (future-cancel fut)))
  (g/dissoc! :interactive-proc
             :interactive-client-future
             :interactive-stdin-writer
             :interactive-stdout-future
             :interactive-stdout-lines*
             :interactive-stdout-queue
             :interactive-stderr-future
             :interactive-stderr-lines*
             :interactive-stderr-queue
             :interactive-session-id
             :output
             :stderr
             :exit-code))

(g/after-scenario reset-interactive-state!)

(defn isaac-remote-run-interactively [args]
  (reset-interactive-state!)
  (let [argv          (cli-steps/parse-argv (interpolate-interactive-args args))
        proc          (process/process (interactive-client-command argv)
                                       {:dir (System/getProperty "user.dir")
                                        :in  :pipe
                                        :out :pipe
                                        :err :pipe})
        stdin-writer  (OutputStreamWriter. (:in proc))
        stdout-reader (BufferedReader. (InputStreamReader. (:out proc)))
        stderr-reader (BufferedReader. (InputStreamReader. (:err proc)))
        stdout-drain  (start-line-drain! stdout-reader)
        stderr-drain  (start-line-drain! stderr-reader)
        wait-future   (future
                        (let [process   ^Process (:proc proc)
                              exit-code (.waitFor process)]
                          @(:future stdout-drain)
                          @(:future stderr-drain)
                          (g/assoc! :output (stdout-text))
                          (g/assoc! :stderr (stderr-text))
                          (g/assoc! :exit-code exit-code)
                          exit-code))]
    (g/assoc! :interactive-proc proc)
    (g/assoc! :interactive-stdin-writer stdin-writer)
    (g/assoc! :interactive-client-future wait-future)
    (g/assoc! :interactive-stdout-queue (:queue stdout-drain))
    (g/assoc! :interactive-stdout-lines* (:lines* stdout-drain))
    (g/assoc! :interactive-stdout-future (:future stdout-drain))
    (g/assoc! :interactive-stderr-queue (:queue stderr-drain))
    (g/assoc! :interactive-stderr-lines* (:lines* stderr-drain))
    (g/assoc! :interactive-stderr-future (:future stderr-drain))))

(defn- write-client-line! [line]
  (let [writer (g/get :interactive-stdin-writer)]
    (when-not writer
      (throw (ex-info "interactive client is not running" {})))
    (.write ^OutputStreamWriter writer line)
    (.flush ^OutputStreamWriter writer)))

(defn client-writes-acp-initialize-request []
  (write-client-line! (jrpc/request-line 1 "initialize" {:protocolVersion 1})))

(defn client-reads-acp-initialize-response-before-eof []
  (let [message (await-stdout-message!
                  (fn [_line message]
                    (and (= 1 (:id message))
                         (= 1 (get-in message [:result :protocolVersion]))))
                  "ACP initialize response")]
    (g/should= 1 (:id message))
    (g/should= 1 (get-in message [:result :protocolVersion]))))

(defn client-writes-acp-session-new-request []
  (write-client-line! (jrpc/request-line 2 "session/new" {:name fixture-session-name})))

(defn client-writes-acp-session-prompt-request [text]
  (let [session-id (await-session-id!)]
    (write-client-line!
      (jrpc/request-line 3 "session/prompt"
                         {:sessionId session-id
                          :prompt    [{:type "text" :text text}]}))))

(defn client-reads-streamed-acp-response-containing-before-eof [text]
  (let [message (await-stdout-message!
                  (fn [line message]
                    (or (str/includes? line text)
                        (str/includes? (or (get-in message [:params :update :content :text]) "") text)))
                  (str "streamed ACP response containing " (pr-str text)))]
    (g/should (str/includes? (or (get-in message [:params :update :content :text]) "") text))))

(defn client-closes-stdin []
  (close-quietly! (g/get :interactive-stdin-writer))
  (g/dissoc! :interactive-stdin-writer))

(defgiven "the remote CLI command is registered" isaac.cli-proxy.integration-steps/remote-cli-ready
  "Registers `remote` and declares the cli-server module after Grover setup.")

(defgiven "a real cli-server backed by an isaac install with an echo model"
  isaac.cli-proxy.integration-steps/real-cli-server-backed-by-installed-isaac-with-echo-model)

(defwhen "isaac remote is run interactively with {args:string}"
  isaac.cli-proxy.integration-steps/isaac-remote-run-interactively)

(defwhen "the client writes the ACP initialize request to stdin"
  isaac.cli-proxy.integration-steps/client-writes-acp-initialize-request)

(defthen "the client reads an ACP initialize response from stdout before EOF"
  isaac.cli-proxy.integration-steps/client-reads-acp-initialize-response-before-eof)

(defwhen "the client writes an ACP session/new request to stdin"
  isaac.cli-proxy.integration-steps/client-writes-acp-session-new-request)

(defwhen "the client writes an ACP session/prompt request {text:string} to stdin"
  isaac.cli-proxy.integration-steps/client-writes-acp-session-prompt-request)

(defthen "the client reads a streamed ACP response containing {text:string} before EOF"
  isaac.cli-proxy.integration-steps/client-reads-streamed-acp-response-containing-before-eof)

(defwhen "the client closes stdin"
  isaac.cli-proxy.integration-steps/client-closes-stdin)
