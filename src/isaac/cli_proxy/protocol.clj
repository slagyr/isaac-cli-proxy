(ns isaac.cli-proxy.protocol
  (:require
    [cheshire.core :as json])
  (:import
    (java.util Base64)))

(defn b64-encode [^String s]
  (when (seq s)
    (.encodeToString (Base64/getEncoder) (.getBytes s "UTF-8"))))

(defn b64-decode [^String s]
  (when (seq s)
    (String. (.decode (Base64/getDecoder) s) "UTF-8")))

(defn start-frame [argv & {:keys [cwd]}]
  (cond-> {:type "start" :argv (vec (or argv []))}
    cwd (assoc :cwd cwd)))

(defn attach-frame [stream-id]
  {:type "attach" :stream-id stream-id})

(defn stdin-frame [text]
  {:type "stdin" :data (b64-encode text)})

(def stdin-close-frame
  {:type "stdin-close"})

(defn encode-frame [frame]
  (json/generate-string frame))

(defn parse-frame [line]
  (cond
    (string? line) (when (seq line)
                     (json/parse-string line true))
    (map? line)    line
    :else          nil))
