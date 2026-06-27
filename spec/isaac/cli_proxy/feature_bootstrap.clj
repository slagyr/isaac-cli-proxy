(ns isaac.cli-proxy.feature-bootstrap
  "Loaded after isaac.**-steps so duplicate session-tier steps that collide
   with server-tier definitions can be dropped from the gherclj registry."
  (:require [clojure.string :as str]))

(def ^:private server-ns 'isaac.server.server-steps)
(def ^:private session-ns 'isaac.session.session-steps)
(def ^:private harness-ns 'isaac.foundation.harness-config-steps)

(defn- without-templates [entries templates]
  (let [drop? (set (or templates []))]
    (vec (remove #(contains? drop? (:template %)) entries))))

(defn- server-owns-config? [registry]
  (some (fn [[ns-sym entries]]
          (when (= ns-sym server-ns)
            (some #(= "config:" (:template %)) entries)))
        registry))

(defn- session-drop-templates [registry]
  (cond-> ["default Grover setup"]
    (server-owns-config? registry) (conj "config:")))

(defn- harness-drop-templates [registry]
  (when (server-owns-config? registry)
    ["config:"]))

(when-let [registry-var (some-> (find-ns 'gherclj.core) ns-interns (get 'registry))]
  (swap! @registry-var
         (fn [m]
           (into {}
                 (map (fn [[ns-sym entries]]
                        (cond
                          (= ns-sym session-ns)
                          [ns-sym (without-templates entries (session-drop-templates m))]

                          (= ns-sym harness-ns)
                          [ns-sym (without-templates entries (harness-drop-templates m))]

                          :else [ns-sym entries])))
                 m))))