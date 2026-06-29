(ns isaac.cli-proxy.feature-bootstrap
  "Loaded after isaac.**-steps. Drops colliding session/harness steps when the
   server harness is present."
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
                        [ns-sym (cond
                                  (= ns-sym session-ns)
                                  (without-templates entries (session-drop-templates m))

                                  (= ns-sym harness-ns)
                                  (without-templates entries (harness-drop-templates m))

                                  :else entries)]))
                 m))))