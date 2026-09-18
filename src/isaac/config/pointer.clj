(ns isaac.config.pointer
  "Read and write the bootstrap home pointer file."
  (:require
    [clojure.edn :as edn]
    [isaac.config.root :as root])
  (:import
    (java.nio.file Files)
    (java.nio.file.attribute PosixFilePermission)))

(defn path []
  (str (root/user-home) "/.config/isaac.edn"))

(defn read-config []
  (try (edn/read-string (slurp (path))) (catch Exception _ {})))

(defn write-config! [config private?]
  (let [file (java.io.File. (path))]
    (.mkdirs (.getParentFile file))
    (spit file (pr-str config))
    (when private?
      (Files/setPosixFilePermissions (.toPath file)
                                     #{PosixFilePermission/OWNER_READ PosixFilePermission/OWNER_WRITE}))))
