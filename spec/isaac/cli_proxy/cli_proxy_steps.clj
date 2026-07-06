(ns isaac.cli-proxy.cli-proxy-steps
  (:require
    [clojure.edn :as edn]
    [clojure.string :as str]
    [gherclj.core :as g :refer [defgiven defwhen defthen helper!]]
    [isaac.cli.registry :as cli-registry]
    [isaac.cli-proxy.cli :as remote-cli]
    [isaac.cli-proxy.protocol :as protocol]
    [isaac.cli-proxy.ws :as ws]
    [isaac.foundation.cli-steps :as cli-steps]
    [isaac.spec-helper :as helper]
    [isaac.step-tables :as match])
  (:import
    (java.io StringReader)))

(helper! isaac.cli-proxy.cli-proxy-steps)

(cli-registry/register! (remote-cli/make-command))

(def ^:private stub-url "loopback://cli-stub")

(defn- reset-stub-state! []
  (when-let [runner (g/get :stub-session-runner)]
    (future-cancel runner))
  (g/dissoc! :stub-defer-replies? :stub-drop-after-send? :stub-initial-reply-frames
             :stub-reattach-reply-frames :stub-received-frames :stub-session-runner
             :stub-stream-id :stub-transport :stub-url :stub-connect-headers
             :main-extra-opts :stdin-content))

(g/after-scenario reset-stub-state!)

(defn- encode-outgoing-frame [row]
  (cond-> {:type (:type row)}
    (and (contains? row :data) (not (str/blank? (:data row))))
    (assoc :data (protocol/b64-encode (:data row)))

    (and (contains? row :code) (not (str/blank? (:code row))))
    (assoc :code (parse-long (:code row)))

    (and (contains? row :argv) (not (str/blank? (:argv row))))
    (assoc :argv (edn/read-string (:argv row)))

    (and (contains? row :stream-id) (not (str/blank? (:stream-id row))))
    (assoc :stream-id (:stream-id row))))

(defn- parse-reply-table [table]
  (let [headers (mapv (comp keyword str) (:headers table))]
    (mapv (fn [row] (zipmap headers row))
          (:rows table))))

(defn- argv->matcher-str [argv]
  (str "[" (str/join "," (map pr-str (vec (or argv [])))) "]"))

(defn- decode-received-frame [frame]
  (cond-> frame
    (:data frame) (update :data protocol/b64-decode)
    (contains? frame :argv) (update :argv argv->matcher-str)))

(defn- frames-for-matching []
  (->> (g/get :stub-received-frames)
       (mapv decode-received-frame)))

(defn- send-reply-frames! [server frames]
  (doseq [frame frames]
    (ws/ws-send! server (protocol/encode-frame frame))))

(defn- maybe-close-after-send! [server]
  (when (g/get :stub-drop-after-send?)
    (g/assoc! :stub-drop-after-send? false)
    (future
      (Thread/sleep 10)
      (ws/ws-close! server))))

(defn- handle-stub-session! [server]
  (loop []
    (when-let [line (ws/ws-receive! server 5000)]
      (let [frame (protocol/parse-frame line)]
        (g/update! :stub-received-frames conj frame)
        (case (:type frame)
          "start"
          (do
            (send-reply-frames! server [{:type "start-ack" :stream-id (g/get :stub-stream-id)}])
            (when-not (g/get :stub-defer-replies?)
              (send-reply-frames! server (g/get :stub-initial-reply-frames))
              (maybe-close-after-send! server)))

          "attach"
          (send-reply-frames! server (g/get :stub-reattach-reply-frames))

          "stdin-close"
          (when (g/get :stub-defer-replies?)
            (send-reply-frames! server (g/get :stub-initial-reply-frames)))

          nil)
        (recur)))))

(defn- stub-connect! [_url {:keys [headers]}]
  (when headers
    (g/assoc! :stub-connect-headers headers))
  (let [transport (or (g/get :stub-transport)
                      (let [t (ws/loopback-transport)]
                        (g/assoc! :stub-transport t)
                        t))
        client    (ws/connect-loopback! transport stub-url {:headers headers})
        server    (ws/accept-loopback! transport)
        serve     (bound-fn [] (handle-stub-session! server))]
    (g/assoc! :stub-session-runner (future (serve)))
    client))

(defn stub-cli-server [table]
  (when-let [runner (g/get :stub-session-runner)]
    (future-cancel runner))
  (let [rows    (parse-reply-table table)
        replies (mapv encode-outgoing-frame rows)]
    (g/assoc! :stub-url stub-url)
    (g/assoc! :stub-stream-id nil)
    (g/assoc! :stub-initial-reply-frames replies)
    (g/assoc! :stub-reattach-reply-frames [])
    (g/assoc! :stub-received-frames [])
    (g/assoc! :stub-transport (ws/reconnectable-transport))
    (g/assoc! :main-extra-opts {:connection-factory stub-connect!})))

(defn stub-cli-server-with-stream-id [stream-id table]
  (stub-cli-server table)
  (g/assoc! :stub-stream-id stream-id))

(defn stub-server-drops-after-sending []
  (g/assoc! :stub-drop-after-send? true))

(defn stub-defer-replies []
  (g/assoc! :stub-defer-replies? true))

(defn stub-server-reattach-replies [table]
  (let [rows (parse-reply-table table)]
    (g/assoc! :stub-reattach-reply-frames (mapv encode-outgoing-frame rows))))

(defn- interpolate-remote-args [args]
  (-> args
      (str/replace "${stub.url}" (or (g/get :stub-url) stub-url))
      (str/replace "\\\"" "\"")))

(defn isaac-remote-run [args]
  (let [argv          (cli-steps/parse-argv (interpolate-remote-args args))
        extra-opts    (or (g/get :main-extra-opts) {})
        stdin-content (or (g/get :stdin-content) "")
        out-w         (java.io.StringWriter.)
        err-w         (java.io.StringWriter.)]
    (binding [*out* out-w
              *err* err-w
              *in*  (java.io.BufferedReader. (StringReader. stdin-content))]
      (g/assoc! :exit-code
                (remote-cli/run-fn (merge extra-opts {:_raw-args argv}))))
    (g/assoc! :output (str out-w))
    (g/assoc! :stderr (str err-w))))

(defn- frame-type-expected? [expected-types frame]
  (contains? expected-types (:type frame)))

(defn- index-table [table]
  (if (some #(= "#index" %) (:headers table))
    table
    {:headers (into ["#index"] (:headers table))
     :rows    (mapv (fn [idx row] (into [(str idx)] row))
                    (range)
                    (:rows table))}))

(defn- received-frame-result [table]
  (let [expected-types (->> (:rows table) (map first) set)
        entries        (->> (frames-for-matching)
                            (filter (partial frame-type-expected? expected-types))
                            vec)
        indexed-table  (index-table table)]
    (match/match-entries indexed-table entries)))

(defn stub-received-frames [table]
  (helper/await-condition #(empty? (:failures (received-frame-result table))) 5000)
  (g/should= [] (:failures (received-frame-result table))))

(defn stub-connection-authorization [expected]
  (g/should= expected (get (g/get :stub-connect-headers) "Authorization")))

(defgiven "a stub /cli server that replies with frames:" isaac.cli-proxy.cli-proxy-steps/stub-cli-server)
(defgiven "a stub /cli server that assigns stream-id {stream-id:string} and replies with frames:"
  isaac.cli-proxy.cli-proxy-steps/stub-cli-server-with-stream-id)
(defgiven "the stub server drops the connection after sending" isaac.cli-proxy.cli-proxy-steps/stub-server-drops-after-sending)
(defgiven "the stub server on reattach replays frames:" isaac.cli-proxy.cli-proxy-steps/stub-server-reattach-replies)
(defgiven "the stub defers replies until stdin-close" isaac.cli-proxy.cli-proxy-steps/stub-defer-replies)

(defwhen "isaac remote is run with {args:string}" isaac.cli-proxy.cli-proxy-steps/isaac-remote-run)

(defthen "the stub server received frames:" isaac.cli-proxy.cli-proxy-steps/stub-received-frames)
(defthen "the stub connection authorization is {expected:string}"
  isaac.cli-proxy.cli-proxy-steps/stub-connection-authorization)
