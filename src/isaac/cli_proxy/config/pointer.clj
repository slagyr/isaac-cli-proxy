(ns isaac.cli-proxy.config.pointer
  "Read and write the bootstrap home pointer file. Reads go through
   isaac.foundation.config.root/pointer-config — the same sanctioned,
   pre-config bootstrap reader isaac.foundation.main uses to resolve this
   same :cli :remote key for implicit routing — rather than a duplicate raw
   slurp+edn/read-string here (which config-bypass-lint rightly treats as an
   unauthorized config bypass outside isaac.foundation.config.*)."
  (:require
    [isaac.foundation.config.root :as root]
    [isaac.foundation.fs :as fs])
  (:import
    (java.nio.file Files)
    (java.nio.file.attribute PosixFilePermission)))

(defn path []
  (str (root/user-home) "/.config/isaac.edn"))

(defn read-config []
  (or (root/pointer-config (fs/real-fs)) {}))

(defn write-config! [config private?]
  (let [file (java.io.File. (path))]
    (.mkdirs (.getParentFile file))
    (spit file (pr-str config))
    (when private?
      (Files/setPosixFilePermissions (.toPath file)
                                     #{PosixFilePermission/OWNER_READ PosixFilePermission/OWNER_WRITE}))))
