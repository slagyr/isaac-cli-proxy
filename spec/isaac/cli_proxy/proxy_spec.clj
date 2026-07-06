(ns isaac.cli-proxy.proxy-spec
  (:require
    [isaac.cli-proxy.protocol :as protocol]
    [isaac.cli-proxy.proxy :as sut]
    [isaac.cli-proxy.ws :as ws]
    [speclj.core :refer :all])
  (:import
    (java.io StringReader)))

(defn- with-loopback-pair [client-fn server-fn]
  (let [{:keys [client server]} (ws/loopback-pair)]
    (try
      (future (server-fn server))
      (client-fn client)
      (finally
        (ws/ws-close! client)
        (ws/ws-close! server)))))

(defn- send-frames! [conn frames]
  (doseq [frame frames]
    (ws/ws-send! conn (protocol/encode-frame frame))))

(defn- empty-stdin-binding [body]
  (binding [*in* (java.io.BufferedReader. (StringReader. ""))]
    (body)))

(describe "run-proxy!"

  (it "ships start argv, prints stdout frames, returns server exit code"
    (let [out* (java.io.StringWriter.)]
      (binding [*out* out*]
        (let [code (empty-stdin-binding
                     (fn []
                       (with-loopback-pair
                         (fn [client]
                           (sut/run-proxy! {:url                "ws://stub/cli"
                                            :argv               ["echo" "hi"]
                                            :connection-factory (constantly client)}))
                         (fn [server]
                           (should= {:type "start" :argv ["echo" "hi"]}
                                    (protocol/parse-frame (ws/ws-receive! server)))
                           (send-frames! server [{:type "start-ack" :stream-id "s-1"}
                                                 {:type "stdout" :data (protocol/b64-encode "hello")}
                                                 {:type "exit" :code 0}])))))]
          (should= 0 code)
          (should= "hello" (.toString out*))))))

  (it "sends empty argv when no remote command is given"
    (let [out* (java.io.StringWriter.)]
      (binding [*out* out*]
        (let [code (empty-stdin-binding
                     (fn []
                       (with-loopback-pair
                         (fn [client]
                           (sut/run-proxy! {:url                "ws://stub/cli"
                                            :argv               []
                                            :connection-factory (constantly client)}))
                         (fn [server]
                           (should= {:type "start" :argv []}
                                    (protocol/parse-frame (ws/ws-receive! server)))
                           (send-frames! server [{:type "start-ack" :stream-id "s-1"}
                                                 {:type "stdout" :data (protocol/b64-encode "Usage: ...")}
                                                 {:type "exit" :code 0}])))))]
          (should= 0 code)
          (should= "Usage: ..." (.toString out*))))))

  (it "renders stderr on *err* and relays nonzero exit codes"
    (let [err* (java.io.StringWriter.)]
      (binding [*out* (java.io.StringWriter.) *err* err*]
        (let [code (empty-stdin-binding
                     (fn []
                       (with-loopback-pair
                         (fn [client]
                           (sut/run-proxy! {:url                "ws://stub/cli"
                                            :argv               ["fail"]
                                            :connection-factory (constantly client)}))
                         (fn [server]
                           (ws/ws-receive! server)
                           (send-frames! server [{:type "start-ack" :stream-id "s-1"}
                                                 {:type "stderr" :data (protocol/b64-encode "oops")}
                                                 {:type "exit" :code 2}])))))]
          (should= 2 code)
          (should= "oops" (.toString err*))))))

  (it "forwards stdin as frames and closes stdin on EOF"
    (let [received* (atom [])
          code      (binding [*in* (java.io.BufferedReader. (StringReader. "line1\nline2\n"))]
                      (with-loopback-pair
                        (fn [client]
                          (sut/run-proxy! {:url                "ws://stub/cli"
                                           :argv               ["cat"]
                                           :connection-factory (constantly client)}))
                        (fn [server]
                          (should= "start"
                                   (:type (protocol/parse-frame (ws/ws-receive! server))))
                          (send-frames! server [{:type "start-ack" :stream-id "s-1"}])
                          (loop []
                            (when-let [line (ws/ws-receive! server 2000)]
                              (let [frame (protocol/parse-frame line)]
                                (swap! received* conj frame)
                                (when-not (= "stdin-close" (:type frame))
                                  (recur)))))
                          (send-frames! server [{:type "exit" :code 0}]))))]
      (should= 0 code)
      (should= ["stdin" "stdin" "stdin-close"] (mapv :type @received*))
      (should= "line1\n" (protocol/b64-decode (:data (nth @received* 0))))
      (should= "line2\n" (protocol/b64-decode (:data (nth @received* 1))))))

  (it "sends bearer token as Authorization header"
    (let [headers* (atom nil)
          factory  (fn [_url {:keys [headers]}]
                     (reset! headers* headers)
                     (let [{:keys [client server]} (ws/loopback-pair)]
                       (future
                         (ws/ws-receive! server)
                         (send-frames! server [{:type "start-ack" :stream-id "s-1"}
                                               {:type "exit" :code 0}]))
                       client))]
      (let [code (empty-stdin-binding
                   (fn []
                     (sut/run-proxy! {:url                "ws://stub/cli"
                                      :argv               []
                                      :token              "secret"
                                      :connection-factory factory})))]
        (should= 0 code)
        (should= {"Authorization" "Bearer secret"} @headers*))))

  (it "reattaches after a dropped socket and renders replayed frames once"
    (let [transport (ws/loopback-transport)
          out*      (java.io.StringWriter.)
          err*      (java.io.StringWriter.)]
      (binding [*out* out* *err* err*]
        (future
          (let [server-1 (ws/accept-loopback! transport)]
            (should= {:type "start" :argv ["sessions" "list"]}
                     (protocol/parse-frame (ws/ws-receive! server-1)))
            (send-frames! server-1 [{:type "start-ack" :stream-id "s-1"}
                                    {:type "stdout" :data (protocol/b64-encode "first")}])
            (ws/ws-close! server-1)
            (let [server-2 (ws/accept-loopback! transport)]
              (should= {:type "attach" :stream-id "s-1"}
                       (protocol/parse-frame (ws/ws-receive! server-2)))
              (send-frames! server-2 [{:type "stdout" :data (protocol/b64-encode "second")}
                                      {:type "exit" :code 0}]))))
        (let [code (empty-stdin-binding
                     (fn []
                       (sut/run-proxy! {:url                "ws://stub/cli"
                                        :argv               ["sessions" "list"]
                                        :connection-factory #(ws/connect-loopback! transport %1 %2)})))]
          (should= 0 code)
          (should= "firstsecond" (.toString out*))
          (should (re-find #"reconnecting" (.toString err*)))
          (should (re-find #"reattached" (.toString err*))))))))