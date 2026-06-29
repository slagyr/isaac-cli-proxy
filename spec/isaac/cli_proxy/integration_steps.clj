(ns isaac.cli-proxy.integration-steps
  "End-to-end harness for features/integration.feature: ensure the cli-server
   /cli route is visible to the server harness (classpath discovery plus an
   explicit inject fallback for sibling checkouts or git-pinned CI)."
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

(def ^:private cli-server-git-coord
  {:git/url "https://github.com/slagyr/isaac-cli-server.git"
   :git/sha "96df1ccaa56e4c9dd6d067ef1debd693ec029ed2"})

(defn- ensure-remote-command! []
  (when-not (cli-registry/get-command "remote")
    (cli-registry/register! (remote-cli/make-command))))

(cli-steps/register-isaac-run-preflight! ensure-remote-command!)

(defn- cli-server-sibling-root []
  (let [f (io/file "../isaac-cli-server")]
    (when (.exists f)
      (.getAbsolutePath f))))

(defn- cli-server-manifest-from-url [url]
  (some-> url io/reader slurp edn/read-string))

(defn- cli-server-manifest-from-classpath []
  (let [loader (.getSystemClassLoader java.lang.ClassLoader)
        urls   (enumeration-seq (.getResources loader "isaac-manifest.edn"))]
    (some (fn [url]
            (let [manifest (cli-server-manifest-from-url url)]
              (when (= :isaac.cli-server (:id manifest))
                manifest)))
          urls)))

(defn- cli-server-manifest []
  (or (some-> (cli-server-sibling-root)
              (io/file "src/isaac-manifest.edn")
              slurp
              edn/read-string)
      (cli-server-manifest-from-classpath)))

(defn- cli-server-module-coord []
  (if-let [root (cli-server-sibling-root)]
    {:isaac.cli-server {:local/root root}}
    {:isaac.cli-server cli-server-git-coord}))

(defn- cli-server-module-index [manifest]
  (if-let [root (cli-server-sibling-root)]
    {:isaac.cli-server {:coord    {:local/root root}
                        :manifest manifest
                        :path     nil}}
    {:isaac.cli-server {:coord    cli-server-git-coord
                        :manifest manifest
                        :path     nil}}))

(defn- feature-fs []
  (or (g/get :mem-fs) (nexus/get :fs) (fs/real-fs)))

(defn- persist-cli-server-module! []
  (when-let [state-root (or (g/get :runtime-root-dir) (g/get :root))]
    (nexus/-with-nested-nexus {:fs (feature-fs)}
      (fn []
        (let [path    (str state-root "/config/isaac.edn")
              fs*     (feature-fs)
              current (if (fs/exists? fs* path)
                        (edn/read-string (fs/slurp fs* path))
                        {})
              updated (update current :modules merge (cli-server-module-coord))]
          (fs/mkdirs fs* (fs/parent path))
          (fs/spit fs* path (pr-str updated)))))))

(defn ensure-cli-server-route! []
  (when-let [manifest (cli-server-manifest)]
    (g/update! :server-config
               #(-> (or % {})
                    (update :modules merge (cli-server-module-coord))
                    (update :inject-module-index
                            merge (cli-server-module-index manifest))))
    (persist-cli-server-module!)))

(defn remote-cli-ready []
  (ensure-remote-command!)
  (ensure-cli-server-route!))

(defgiven "the remote CLI command is registered" isaac.cli-proxy.integration-steps/remote-cli-ready
  "Registers `remote` and declares the cli-server module after Grover setup.")