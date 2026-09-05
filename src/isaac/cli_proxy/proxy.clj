(ns isaac.cli-proxy.proxy
  (:require
    [c3kit.apron.env :as c3env]
    [cheshire.core :as json]
    [clojure.string :as str]
    [isaac.cli-proxy.protocol :as protocol]
    [isaac.cli-proxy.ws :as ws]
    [isaac.logger :as log])
  (:import
    (java.io BufferedReader)))

(def ^:dynamic *connection-factory* ws/connect!)
(def ^:dynamic *sleep-fn* (fn [ms] (Thread/sleep (long ms))))
(def ^:dynamic *now-ms* (fn [] (System/currentTimeMillis)))
(def ^:dynamic *stdout-tty?* #(some? (System/console)))

(def DEFAULT-RECONNECT-WINDOW-SECS 120)
(def RECONNECT-INITIAL-DELAY-MS 250)
(def RECONNECT-MAX-DELAY-MS 5000)

(defn- bearer-headers [token]
  (when (seq token)
    {"Authorization" (str "Bearer " token)}))

(defn- render-frame! [frame]
  (case (:type frame)
    "stdout" (do (print (protocol/b64-decode (:data frame)))
                 (flush))
    "stderr" (binding [*out* *err*]
               (print (protocol/b64-decode (:data frame)))
               (flush))
    nil))

(defn- status-line! [text]
  (binding [*out* *err*]
    (println text)
    (flush)))

(defn- parse-json-line [text]
  (try
    (json/parse-string text true)
    (catch Exception _ nil)))

(defn- acp-command? [argv]
  (= "acp" (first argv)))

(defn- acp-method [parsed]
  (when (map? parsed)
    (:method parsed)))

(defn- acp-id [parsed]
  (when (map? parsed)
    (:id parsed)))

(defn- session-id-from [parsed]
  (or (get-in parsed [:params :sessionId])
      (get-in parsed [:params :session-id])
      (get-in parsed [:result :sessionId])
      (get-in parsed [:result :session-id])))

(defn- record-acp-outbound! [acp* text]
  (when-let [parsed (parse-json-line text)]
    (let [method (acp-method parsed)]
      (cond
        (= "initialize" method)
        (swap! acp* assoc :initialize text :initialize-id (acp-id parsed))

        (or (= "session/new" method) (= "session/load" method))
        (swap! acp* assoc
               :session-handshake text
               :session-handshake-id (acp-id parsed)
               :session-id (or (session-id-from parsed) (:session-id @acp*)))

        (= "session/prompt" method)
        (swap! acp* assoc :in-flight-prompt-id (acp-id parsed))))))

(defn- record-acp-inbound! [acp* text]
  (when-let [parsed (parse-json-line text)]
    (when-let [sid (session-id-from parsed)]
      (swap! acp* assoc :session-id sid))))

(defn- swallow-replayed-response? [acp* frame]
  (when (and (= "stdout" (:type frame))
             (pos? (or (:swallow-remaining @acp*) 0)))
    (swap! acp* update :swallow-remaining dec)
    true))

(defn- pump-stdin! [conn* acp*]
  (future
    (try
      (let [reader (if (instance? BufferedReader *in*)
                     *in*
                     (BufferedReader. *in*))]
        (loop []
          (when-let [line (.readLine reader)]
            (let [text (str line "\n")]
              (record-acp-outbound! acp* (str/trim-newline line))
              (when-let [conn @conn*]
                (ws/ws-send! conn (protocol/encode-frame (protocol/stdin-frame text)))))
            (recur))))
      (when-let [conn @conn*]
        (ws/ws-send! conn (protocol/encode-frame protocol/stdin-close-frame)))
      (catch Exception e
        (when-not (instance? InterruptedException e)
          (log/error :stdin-pump/error :throwable e))))))

(defn- authentication-error? [error]
  (let [cause  (loop [e error]
                 (if-let [c (ex-cause e)]
                   (recur c)
                   e))
        class-name (.getName (class cause))
        message    (or (.getMessage cause) "")]
    (or (= "java.net.http.WebSocketHandshakeException" class-name)
        (re-find #"(?i)401|unauthorized|authentication failed" message))))

(defn- print-connect-error! [error url]
  (binding [*out* *err*]
    (println (if (authentication-error? error)
               "authentication failed"
               (str "could not connect to remote CLI endpoint: " url)))))

(defn- connect! [factory url token]
  (factory url {:headers (bearer-headers token)}))

(defn- first-frame! [conn]
  (some-> (ws/ws-receive! conn) protocol/parse-frame))

(defn- send-start! [conn argv cwd]
  (let [stdout-tty (boolean (*stdout-tty?*))]
    (ws/ws-send! conn (protocol/encode-frame (apply protocol/start-frame argv
                                                    (concat (when cwd [:cwd cwd])
                                                            (when stdout-tty [:stdout-tty true])))))))

(defn- start-connection! [factory url argv token cwd]
  (let [conn        (connect! factory url token)
        _           (send-start! conn argv cwd)
        first-frame (first-frame! conn)]
    {:conn          conn
     :initial-frame (when-not (= "start-ack" (:type first-frame)) first-frame)
     :stream-id     (:stream-id first-frame)}))

(defn- attach-connection! [factory url token stream-id]
  (let [conn (connect! factory url token)]
    (when (seq stream-id)
      (ws/ws-send! conn (protocol/encode-frame (protocol/attach-frame stream-id))))
    conn))

(defn -reconnect-window-ms []
  (let [raw  (c3env/env "ISAAC_REMOTE_RECONNECT_SECS")
        secs (or (some-> raw str parse-long) DEFAULT-RECONNECT-WINDOW-SECS)]
    (* 1000 (max 0 secs))))

(defn -next-delay-ms [attempt]
  (min RECONNECT-MAX-DELAY-MS
       (long (* RECONNECT-INITIAL-DELAY-MS (Math/pow 2 (dec attempt))))))

(defn- unknown-stream-error? [frame]
  (and (= "error" (:type frame))
       (let [message (or (:message frame) "")]
         (or (str/includes? (str/lower-case message) "unknown stream")
             (str/includes? (str/lower-case message) "unknown-stream")))))

(defn- replay-acp-handshake! [conn acp*]
  (let [{:keys [initialize session-handshake session-id session-handshake-id]} @acp*
        load-line (when (or session-handshake session-id)
                    (if (and session-id session-handshake)
                      (let [parsed (or (parse-json-line session-handshake) {})]
                        (json/generate-string
                          (-> parsed
                              (assoc :method "session/load")
                              (assoc-in [:params :sessionId] session-id)
                              (cond-> session-handshake-id (assoc :id session-handshake-id)))))
                      session-handshake))
        lines     (cond-> []
                    initialize (conj initialize)
                    load-line (conj load-line))]
    (doseq [line lines]
      (ws/ws-send! conn (protocol/encode-frame (protocol/stdin-frame (str (str/trim-newline line) "\n")))))
    (swap! acp* assoc :swallow-remaining (count lines))))

(defn- synthesize-in-flight-prompt! [acp*]
  (when-let [prompt-id (:in-flight-prompt-id @acp*)]
    (print (json/generate-string {:jsonrpc "2.0"
                                  :id      prompt-id
                                  :result  {:stopReason "end_turn"}}))
    (flush)
    (swap! acp* dissoc :in-flight-prompt-id)))

(defn- start-fresh! [factory url argv token cwd acp*]
  (let [{:keys [conn initial-frame stream-id]} (start-connection! factory url argv token cwd)]
    (when (acp-command? argv)
      (replay-acp-handshake! conn acp*)
      (synthesize-in-flight-prompt! acp*))
    (status-line! "isaac remote: restarted")
    {:conn conn :initial-frame initial-frame :stream-id stream-id}))

(defn- try-reconnect-once! [factory url token stream-id argv cwd acp*]
  (try
    (if (seq stream-id)
      (let [conn  (attach-connection! factory url token stream-id)
            frame (first-frame! conn)]
        (cond
          (unknown-stream-error? frame)
          (do
            (ws/ws-close! conn)
            (assoc (start-fresh! factory url argv token cwd acp*) :status :restarted))

          :else
          (do
            (status-line! "isaac remote: reattached")
            {:conn          conn
             :initial-frame (when-not (= "start-ack" (:type frame)) frame)
             :stream-id     stream-id
             :status        :reattached})))
      (assoc (start-fresh! factory url argv token cwd acp*) :status :restarted))
    (catch Exception _
      nil)))

(defn- reconnect-give-up! []
  (status-line! (str "isaac remote: could not reconnect within "
                     (long (/ (-reconnect-window-ms) 1000)) "s"))
  nil)

(defn- reconnect! [factory url token stream-id argv cwd acp*]
  (let [window-ms (-reconnect-window-ms)
        started   (*now-ms*)]
    (loop [attempt 1]
      (if (>= (- (*now-ms*) started) window-ms)
        (reconnect-give-up!)
        (do
          (status-line! (str "isaac remote: reconnecting (attempt " attempt ")…"))
          (if-let [result (try-reconnect-once! factory url token stream-id argv cwd acp*)]
            result
            (let [elapsed   (- (*now-ms*) started)
                  remaining (max 0 (- window-ms elapsed))
                  delay-ms  (-next-delay-ms attempt)
                  sleep-ms  (min delay-ms remaining)]
              (when (pos? sleep-ms)
                (*sleep-fn* sleep-ms))
              (if (>= (- (*now-ms*) started) window-ms)
                (reconnect-give-up!)
                (recur (inc attempt))))))))))

(defn- handle-frame! [frame stdin-fut acp*]
  (cond
    (= "error" (:type frame))
    (if (unknown-stream-error? frame)
      {:done? false :unknown-stream? true}
      (do
        (future-cancel stdin-fut)
        (binding [*out* *err*] (println (:message frame)))
        {:done? true :code 1}))

    (= "exit" (:type frame))
    (do
      (future-cancel stdin-fut)
      {:done? true :code (long (or (:code frame) 0))})

    :else
    (if (swallow-replayed-response? acp* frame)
      {:done? false}
      (do
        (when (= "stdout" (:type frame))
          (record-acp-inbound! acp* (protocol/b64-decode (:data frame))))
        (render-frame! frame)
        {:done? false}))))

(defn- apply-fresh-start! [factory conn* url argv token cwd acp* stdin-fut]
  (if-let [fresh (try
                   (start-fresh! factory url argv token cwd acp*)
                   (catch Exception _ nil))]
    (do
      (reset! conn* (:conn fresh))
      {:pending (:initial-frame fresh) :stream-id (:stream-id fresh)})
    (do
      (future-cancel stdin-fut)
      {:code 1})))

(defn- await-exit-code! [factory {:keys [conn* initial-frame url token stream-id argv cwd acp*]} stdin-fut]
  (loop [pending-frame initial-frame
         stream-id     stream-id]
    (if pending-frame
      (let [{:keys [done? code unknown-stream?]} (handle-frame! pending-frame stdin-fut acp*)]
        (cond
          done?           code
          unknown-stream? (let [next (apply-fresh-start! factory conn* url argv token cwd acp* stdin-fut)]
                            (if (:code next)
                              (:code next)
                              (recur (:pending next) (:stream-id next))))
          :else           (recur nil stream-id)))
      (let [raw   (ws/ws-receive! @conn*)
            frame (some-> raw protocol/parse-frame)]
        (cond
          frame
          (let [{:keys [done? code unknown-stream?]} (handle-frame! frame stdin-fut acp*)]
            (cond
              done?           code
              unknown-stream? (let [next (apply-fresh-start! factory conn* url argv token cwd acp* stdin-fut)]
                                (if (:code next)
                                  (:code next)
                                  (recur (:pending next) (:stream-id next))))
              :else           (recur nil stream-id)))

          :else
          (if-let [result (reconnect! factory url token stream-id argv cwd acp*)]
            (do
              (reset! conn* (:conn result))
              (recur (:initial-frame result) (or (:stream-id result) stream-id)))
            (do
              (future-cancel stdin-fut)
              1)))))))

(defn run-proxy!
  "Open a WebSocket to `url`, ship `argv`, relay local stdio, and return the
   server's exit code."
  [{:keys [url argv token cwd connection-factory]
    :or   {argv []}}]
  (try
    (let [factory   (or connection-factory *connection-factory* ws/connect!)
          started   (start-connection! factory url argv token cwd)
          conn*     (atom (:conn started))
          acp*      (atom {})
          stdin-fut (pump-stdin! conn* acp*)]
      (try
        (await-exit-code! factory {:conn*         conn*
                                   :initial-frame (:initial-frame started)
                                   :stream-id     (:stream-id started)
                                   :token         token
                                   :url           url
                                   :argv          argv
                                   :cwd           cwd
                                   :acp*          acp*}
                          stdin-fut)
        (finally
          (some-> @conn* ws/ws-close!))))
    (catch Exception e
      (print-connect-error! e url)
      1)))
