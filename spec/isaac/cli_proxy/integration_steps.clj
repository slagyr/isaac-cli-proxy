(ns isaac.cli-proxy.integration-steps
  "End-to-end harness for features/integration.feature: ensure the cli-server
   /cli route is visible to the server harness (classpath discovery plus an
   explicit inject fallback for sibling checkouts)."
  (:require
    [clojure.edn :as edn]
    [clojure.java.io :as io]
    [gherclj.core :as g :refer [defgiven helper!]]
    [isaac.cli.registry :as cli-registry]
    [isaac.cli-proxy.cli :as remote-cli]
    [isaac.foundation.cli-steps :as cli-steps]
    [isaac.fs :as fs]
    [isaac.nexus :as nexus]))

(helper! isaac.cli-proxy.integration-steps)

(cli-registry/register! (remote-cli/make-command))

(defn- ensure-remote-command! []
  (when-not (cli-registry/get-command "remote")
    (cli-registry/register! (remote-cli/make-command))))

(cli-steps/register-isaac-run-preflight! ensure-remote-command!)

(defn- cli-server-sibling-root []
  (let [f (io/file "../isaac-cli-server")]
    (when (.exists f)
      (.getAbsolutePath f))))

(defn- cli-server-manifest [root]
  (some-> (io/file root "src/isaac-manifest.edn") slurp edn/read-string))

(defn- cli-server-module-coord [root]
  {:isaac.cli-server {:local/root root}})

(defn- cli-server-module-index [root manifest]
  {:isaac.cli-server {:coord    {:local/root root}
                      :manifest manifest
                      :path     nil}})

(defn- feature-fs []
  (or (g/get :mem-fs) (nexus/get :fs) (fs/real-fs)))

(defn- persist-cli-server-module! [root]
  (when-let [state-root (or (g/get :runtime-root-dir) (g/get :root))]
    (nexus/-with-nested-nexus {:fs (feature-fs)}
      (fn []
        (let [path    (str state-root "/config/isaac.edn")
              fs*     (feature-fs)
              current (if (fs/exists? fs* path)
                        (edn/read-string (fs/slurp fs* path))
                        {})
              updated (update current :modules merge (cli-server-module-coord root))]
          (fs/mkdirs fs* (fs/parent path))
          (fs/spit fs* path (pr-str updated)))))))

(defn ensure-cli-server-route! []
  (when-let [root (cli-server-sibling-root)]
    (when-let [manifest (cli-server-manifest root)]
      (g/update! :server-config
                 #(-> (or % {})
                      (update :modules merge (cli-server-module-coord root))
                      (update :inject-module-index
                              merge (cli-server-module-index root manifest))))
      (persist-cli-server-module! root))))

(defn remote-cli-ready []
  (ensure-remote-command!)
  (ensure-cli-server-route!))

(defgiven "the remote CLI command is registered" isaac.cli-proxy.integration-steps/remote-cli-ready
  "Registers `remote` and declares the cli-server module after Grover setup.")