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
    [isaac.step-tables :as match])
  (:import
    (java.io StringReader)))

(helper! isaac.cli-proxy.cli-proxy-steps)

(cli-registry/register! (remote-cli/make-command))

(def ^:private stub-url "loopback://cli-stub")

(defn- reset-stub-state! []
  (when-let [runner (g/get :stub-session-runner)]
    (future-cancel runner))
  (g/dissoc! :stub-reply-frames :stub-defer-replies? :stub-received-frames
             :stub-session-runner :stub-url :stub-connect-headers :main-extra-opts))

(g/after-scenario reset-stub-state!)

(defn- encode-outgoing-frame [row]
  (cond-> {:type (:type row)}
    (and (contains? row :data) (not (str/blank? (:data row))))
    (assoc :data (protocol/b64-encode (:data row)))

    (and (contains? row :code) (not (str/blank? (:code row))))
    (assoc :code (parse-long (:code row)))

    (and (contains? row :argv) (not (str/blank? (:argv row))))
    (assoc :argv (edn/read-string (:argv row)))))

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

(defn- handle-stub-session! [server]
  (loop []
    (when-let [line (ws/ws-receive! server 5000)]
      (let [frame (protocol/parse-frame line)]
        (g/update! :stub-received-frames conj frame)
        (when (= "start" (:type frame))
          (when-not (g/get :stub-defer-replies?)
            (send-reply-frames! server (g/get :stub-reply-frames))))
        (when (= "stdin-close" (:type frame))
          (when (g/get :stub-defer-replies?)
            (send-reply-frames! server (g/get :stub-reply-frames))))
        (recur)))))

(defn- stub-connect! [_url {:keys [headers]}]
  (when headers
    (g/assoc! :stub-connect-headers headers))
  (let [{:keys [client server]} (ws/loopback-pair)
        serve (bound-fn [] (handle-stub-session! server))]
    (g/assoc! :stub-session-runner (future (serve)))
    client))

(defn stub-cli-server [table]
  (when-let [runner (g/get :stub-session-runner)]
    (future-cancel runner))
  (let [defer? (g/get :stub-defer-replies?)]
    (g/dissoc! :stub-reply-frames :stub-received-frames :stub-session-runner :stub-connect-headers)
    (when defer? (g/assoc! :stub-defer-replies? true))
    (let [rows    (parse-reply-table table)
          replies (mapv encode-outgoing-frame rows)]
      (g/assoc! :stub-url stub-url)
      (g/assoc! :stub-reply-frames replies)
      (g/assoc! :stub-received-frames [])
      (g/assoc! :main-extra-opts {:connection-factory stub-connect!}))))

(defn stub-defer-replies []
  (g/assoc! :stub-defer-replies? true))

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

(defn stub-received-frames [table]
  (let [entries (frames-for-matching)
        result  (match/match-entries table entries)]
    (g/should= [] (:failures result))))

(defn stub-connection-authorization [expected]
  (g/should= expected (get (g/get :stub-connect-headers) "Authorization")))

(defgiven "a stub /cli server that replies with frames:" isaac.cli-proxy.cli-proxy-steps/stub-cli-server)

(defgiven "the stub defers replies until stdin-close" isaac.cli-proxy.cli-proxy-steps/stub-defer-replies)

(defwhen "isaac remote is run with {args:string}" isaac.cli-proxy.cli-proxy-steps/isaac-remote-run)

(defthen "the stub server received frames:" isaac.cli-proxy.cli-proxy-steps/stub-received-frames)

(defthen "the stub connection authorization is {expected:string}"
  isaac.cli-proxy.cli-proxy-steps/stub-connection-authorization)