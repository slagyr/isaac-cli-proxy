(ns isaac.cli-proxy.proxy
  (:require
    [isaac.cli-proxy.protocol :as protocol]
    [isaac.cli-proxy.ws :as ws]
    [isaac.logger :as log])
  (:import
    (java.io BufferedReader)))

(def ^:dynamic *connection-factory* ws/connect!)

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

(defn- pump-stdin! [conn]
  (future
    (try
      (let [reader (if (instance? BufferedReader *in*)
                     *in*
                     (BufferedReader. *in*))]
        (loop []
          (when-let [line (.readLine reader)]
            (ws/ws-send! conn (protocol/encode-frame
                                (protocol/stdin-frame (str line "\n"))))
            (recur))))
      (ws/ws-send! conn (protocol/encode-frame protocol/stdin-close-frame))
      (catch Exception e
        (log/error :stdin-pump/error :throwable e)))))

(defn- await-exit-code! [conn stdin-fut]
  (loop []
    (if-let [line (ws/ws-receive! conn)]
      (let [frame (protocol/parse-frame line)]
        (cond
          (= "error" (:type frame))
          (do
            (future-cancel stdin-fut)
            (binding [*out* *err*] (println (:message frame)))
            1)

          (= "exit" (:type frame))
          (do
            (future-cancel stdin-fut)
            (long (or (:code frame) 0)))

          :else
          (do
            (render-frame! frame)
            (recur))))
      (do
        (future-cancel stdin-fut)
        1))))

(defn run-proxy!
  "Open a WebSocket to `url`, ship `argv`, relay local stdio, and return the
   server's exit code."
  [{:keys [url argv token cwd connection-factory]
    :or   {argv []}}]
  (let [factory (or connection-factory *connection-factory* ws/connect!)
        conn    (factory url {:headers (bearer-headers token)})]
    (try
      (ws/ws-send! conn (protocol/encode-frame (apply protocol/start-frame argv
                                                      (when cwd [:cwd cwd]))))
      (await-exit-code! conn (pump-stdin! conn))
      (finally
        (ws/ws-close! conn)))))