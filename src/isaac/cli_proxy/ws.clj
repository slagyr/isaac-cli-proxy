(ns isaac.cli-proxy.ws
  (:require
    [clojure.string :as str]
    [isaac.logger :as log])
  (:import
    (java.net URI)
    (java.net.http HttpClient WebSocket WebSocket$Listener)
    (java.util.concurrent CompletableFuture LinkedBlockingQueue TimeUnit)))

(def ^:private closed-sentinel ::closed)
(def timeout ::timeout)

(defprotocol WsConnection
  (ws-send! [this message])
  (ws-receive! [this] [this timeout-ms])
  (ws-close! [this]))

(defn- completed-future []
  (CompletableFuture/completedFuture nil))

(defn- queue-message! [queue message]
  (.put queue message))

(defn- queue-closed! [queue]
  (.offer queue closed-sentinel))

(defn- receive-queue-message [queue closed? timeout-ms]
  (let [message (if (some? timeout-ms)
                  (.poll queue timeout-ms TimeUnit/MILLISECONDS)
                  (.take queue))]
    (cond
      (= closed-sentinel message) nil
      (and (nil? message) @closed?) nil
      (nil? message) timeout
      :else message)))

(deftype LoopbackWs [incoming outgoing closed?]
  WsConnection
  (ws-send! [_ message]
    (when-not @closed?
      (queue-message! outgoing message))
    nil)
  (ws-receive! [_]
    (receive-queue-message incoming closed? nil))
  (ws-receive! [_ timeout-ms]
    (receive-queue-message incoming closed? timeout-ms))
  (ws-close! [_]
    (reset! closed? true)
    (queue-closed! incoming)
    (queue-closed! outgoing)
    nil))

(defn loopback-pair []
  (let [client-incoming (LinkedBlockingQueue.)
        server-incoming (LinkedBlockingQueue.)
        client-closed?  (atom false)
        server-closed?  (atom false)]
    {:client (->LoopbackWs client-incoming server-incoming client-closed?)
     :server (->LoopbackWs server-incoming client-incoming server-closed?)}))

(defrecord LoopbackTransport [accept-queue connected-queue active-client active-server
                            connect-headers captured-headers])

(defn loopback-transport []
  (->LoopbackTransport (LinkedBlockingQueue.) (LinkedBlockingQueue.) (atom nil) (atom nil)
                       (atom nil) (atom nil)))

(defn connect-loopback! [transport _url {:keys [headers] :as _opts}]
  (when headers
    (reset! (:captured-headers transport) headers))
  (let [{:keys [client server]} (loopback-pair)]
    (reset! (:active-client transport) client)
    (reset! (:active-server transport) server)
    (.put ^LinkedBlockingQueue (:accept-queue transport) server)
    (.put ^LinkedBlockingQueue (:connected-queue transport) server)
    client))

(defn accept-loopback! [transport]
  (.poll ^LinkedBlockingQueue (:accept-queue transport) 1000 TimeUnit/MILLISECONDS))

(deftype RealWs [websocket incoming closed?]
  WsConnection
  (ws-send! [_ message]
    (.join (.sendText websocket message true))
    nil)
  (ws-receive! [_]
    (receive-queue-message incoming closed? nil))
  (ws-receive! [_ timeout-ms]
    (receive-queue-message incoming closed? timeout-ms))
  (ws-close! [_]
    (reset! closed? true)
    (.join (.sendClose websocket WebSocket/NORMAL_CLOSURE "bye"))
    (queue-closed! incoming)
    nil))

(defn- ws-listener [incoming closed?]
  (let [partial (StringBuilder.)]
    (reify WebSocket$Listener
      (onOpen [_ ws]
        (.request ^WebSocket ws 1)
        (completed-future))
      (onText [_ ws data last?]
        (locking partial
          (.append partial data)
          (when last?
            (queue-message! incoming (.toString partial))
            (.setLength partial 0)))
        (.request ^WebSocket ws 1)
        (completed-future))
      (onBinary [_ ws _data _last?]
        (.request ^WebSocket ws 1)
        (completed-future))
      (onPing [_ ws _data]
        (.request ^WebSocket ws 1)
        (completed-future))
      (onPong [_ ws _data]
        (.request ^WebSocket ws 1)
        (completed-future))
      (onClose [_ _ws _status-code _reason]
        (reset! closed? true)
        (queue-closed! incoming)
        (completed-future))
      (onError [_ _ws error]
        (reset! closed? true)
        (log/error :ws/error :throwable error)
        (queue-message! incoming {:error error})
        nil))))

(defn connect!
  ([url]
   (connect! url {}))
  ([url {:keys [headers]}]
   (let [incoming (LinkedBlockingQueue.)
         closed?  (atom false)
         listener (ws-listener incoming closed?)
         builder  (.newWebSocketBuilder (HttpClient/newHttpClient))]
     (doseq [[header value] (or headers {})]
       (.header builder header value))
     (let [websocket (.join (.buildAsync builder (URI/create url) listener))]
       (->RealWs websocket incoming closed?)))))