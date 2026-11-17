(ns isaac.cli-proxy.local-server
  "The local server discovery file is runtime state, not user configuration."
  (:require
    [clojure.edn :as edn]
    [isaac.foundation.fs :as fs]
    [isaac.foundation.nexus :as nexus]))

(defn token [opts target-url]
  (let [fs* (or (:fs opts) (nexus/get :fs) (fs/real-fs))
        path (str (:root opts) "/state/cli-server.edn")]
    (when (and (:root opts) target-url (fs/exists? fs* path))
      (try
        (let [local (edn/read-string (fs/slurp fs* path))
              own (java.net.URI/create (:url local))
              target (java.net.URI/create target-url)]
          (when (and (= (.getPort own) (.getPort target))
                     (= (.getPath own) (.getPath target))
                     (contains? #{"localhost" "127.0.0.1" "::1"} (.getHost own))
                     (contains? #{"localhost" "127.0.0.1" "::1"} (.getHost target)))
            (:token local)))
        (catch Exception _ nil)))))

