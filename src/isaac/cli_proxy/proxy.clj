(ns isaac.cli-proxy.proxy
  (:require
    [isaac.cli-proxy.protocol :as protocol]
    [isaac.cli-proxy.ws :as ws]
    [isaac.logger :as log])
  (:import
    (java.io BufferedReader)))

(def ^:dynamic *connection-factory* ws/connect!)
(def ^:dynamic *reconnect-delays-ms* [25 50 100 200])

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

(defn- pump-stdin! [conn*]
  (future
    (try
      (let [reader (if (instance? BufferedReader *in*)
                     *in*
                     (BufferedReader. *in*))]
        (loop []
          (when-let [line (.readLine reader)]
            (when-let [conn @conn*]
              (ws/ws-send! conn (protocol/encode-frame
                                  (protocol/stdin-frame (str line "\n")))))
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

(defn- start-connection! [factory url argv token cwd]
  (let [conn        (connect! factory url token)
        _           (ws/ws-send! conn (protocol/encode-frame (apply protocol/start-frame argv
                                                                    (when cwd [:cwd cwd]))))
        first-frame (first-frame! conn)]
    {:conn         conn
     :initial-frame (when-not (= "start-ack" (:type first-frame)) first-frame)
     :stream-id    (:stream-id first-frame)}))

(defn- attach-connection! [factory url token stream-id]
  (let [conn (connect! factory url token)]
    (ws/ws-send! conn (protocol/encode-frame (protocol/attach-frame stream-id)))
    conn))

(defn- reconnect! [factory url token stream-id]
  (loop [delays *reconnect-delays-ms*]
    (when-let [delay-ms (first delays)]
      (Thread/sleep delay-ms)
      (if-let [conn (try
                      (attach-connection! factory url token stream-id)
                      (catch Exception _ nil))]
        (do
          (status-line! "isaac remote: reattached")
          conn)
        (recur (rest delays))))))

(defn- handle-frame! [frame stdin-fut]
  (cond
    (= "error" (:type frame))
    (do
      (future-cancel stdin-fut)
      (binding [*out* *err*] (println (:message frame)))
      {:done? true :code 1})

    (= "exit" (:type frame))
    (do
      (future-cancel stdin-fut)
      {:done? true :code (long (or (:code frame) 0))})

    :else
    (do
      (render-frame! frame)
      {:done? false})))

(defn- await-exit-code! [factory {:keys [conn* initial-frame url token stream-id]} stdin-fut]
  (loop [pending-frame initial-frame]
    (if pending-frame
      (let [{:keys [done? code]} (handle-frame! pending-frame stdin-fut)]
        (if done?
          code
          (recur nil)))
      (let [raw   (ws/ws-receive! @conn*)
            frame (some-> raw protocol/parse-frame)]
        (cond
          frame
          (let [{:keys [done? code]} (handle-frame! frame stdin-fut)]
            (if done?
              code
              (recur nil)))

          :else
          (do
            (status-line! "isaac remote: connection lost, reconnecting...")
            (if (seq stream-id)
              (if-let [reattached (reconnect! factory url token stream-id)]
                (do
                  (reset! conn* reattached)
                  (recur nil))
                (do
                  (future-cancel stdin-fut)
                  1))
              (do
                (future-cancel stdin-fut)
                1))))))))

(defn run-proxy!
  "Open a WebSocket to `url`, ship `argv`, relay local stdio, and return the
   server's exit code."
  [{:keys [url argv token cwd connection-factory]
    :or   {argv []}}]
  (try
    (let [factory                              (or connection-factory *connection-factory* ws/connect!)
          {:keys [conn initial-frame stream-id]} (start-connection! factory url argv token cwd)
          conn*                                (atom conn)
          stdin-fut                            (pump-stdin! conn*)]
      (try
        (await-exit-code! factory {:conn* conn* :initial-frame initial-frame :stream-id stream-id :token token :url url} stdin-fut)
        (finally
          (some-> @conn* ws/ws-close!))))
    (catch Exception e
      (print-connect-error! e url)
      1)))
