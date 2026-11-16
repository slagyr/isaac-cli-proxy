(ns isaac.cli-proxy.forwarder
  "Server-only CLI command routing."
  (:require
    [clojure.edn :as edn]
    [clojure.string :as str]
    [isaac.cli-proxy.config.pointer :as pointer]
    [isaac.cli-proxy.proxy :as proxy]
    [isaac.cli-proxy.token :as token]
    [isaac.foundation.fs :as fs]
    [isaac.foundation.nexus :as nexus]))

(defn- discovered [root fs*]
  (let [path (str root "/state/cli-server.edn")]
    (when (fs/exists? fs* path)
      (try (edn/read-string (fs/slurp fs* path))
           (catch Exception _ nil)))))

(defn- remote-target [url root fs*]
  (let [named? (not (re-find #"://" url))
        named  (when named? (get-in (pointer/read-config) [:cli :remotes (keyword url)]))
        target (if named? (:url named) url)
        resolved (token/resolve-system-token {:url target :name (when named? url) :home-config named})
        local (discovered root fs*)
        local-port (some-> (:url local) (java.net.URI/create) .getPort)
        target-uri (when target (java.net.URI/create target))
        loopback? (contains? #{"localhost" "127.0.0.1" "::1"} (.getHost target-uri))
        local-token (when (and loopback? (= local-port (.getPort target-uri))) (:token local))]
    {:url target :token (or (:token resolved) local-token) :error (:error resolved)}))

(defn- explicit-remote [argv]
  (cond
    (= "--remote" (first argv)) [(second argv) (vec (drop 2 argv))]
    (some-> (first argv) (str/starts-with? "--remote="))
    [(subs (first argv) (count "--remote=")) (vec (rest argv))]))

(defn make-forwarder []
  (fn [{:keys [argv root fs]}]
    (let [fs* (or fs (nexus/get :fs) (fs/real-fs))
          [url forwarded-argv] (explicit-remote argv)
          remote (get-in (pointer/read-config) [:cli :remote])
          target (cond url (remote-target url root fs*)
                       (:url remote) (remote-target (:url remote) root fs*)
                       :else (discovered root fs*))]
      (if-not (:url target)
        {:no-server true}
        (if-let [error (:error target)]
          (do (binding [*out* *err*] (println error)) {:exit 1})
          (let [code (proxy/run-proxy! {:url (:url target) :token (:token target) :argv (or forwarded-argv argv)})]
            (if (and (not url) (not (:url remote)) (= 69 code))
              {:no-server true}
              {:exit code})))))))
