(ns isaac.cli-proxy.token
  "Resolve remote bearer credentials without exposing secrets in argv."
  (:require
    [clojure.edn :as edn]
    [c3kit.apron.env :as c3env]
    [clojure.string :as str]
    [isaac.config.env :as config-env]
    [isaac.config.root :as root])
  (:import
    (java.nio.file Files LinkOption)
    (java.nio.file.attribute PosixFilePermission)))

(def DEFAULT_ENV "ISAAC_REMOTE_TOKEN")

(defn- present [value]
  (some-> value str str/trim not-empty))

(defn- secure-mode? [mode]
  (zero? (bit-and (long mode) 077)))

(defn posix-mode [path]
  (let [permissions (Files/getPosixFilePermissions (.toPath (java.io.File. path))
                                                    (make-array LinkOption 0))]
    (reduce (fn [mode [permission bit]]
              (if (.contains permissions permission) (bit-or mode bit) mode))
            0
            [[PosixFilePermission/OWNER_READ 0400]
             [PosixFilePermission/OWNER_WRITE 0200]
             [PosixFilePermission/OWNER_EXECUTE 0100]
             [PosixFilePermission/GROUP_READ 0040]
             [PosixFilePermission/GROUP_WRITE 0020]
             [PosixFilePermission/GROUP_EXECUTE 0010]
             [PosixFilePermission/OTHERS_READ 0004]
             [PosixFilePermission/OTHERS_WRITE 0002]
             [PosixFilePermission/OTHERS_EXECUTE 0001]])))

(defn- expand-env [token env env-fn]
  (when-let [[_ name] (and (string? token) (re-matches #"\$\{([^}]+)\}" token))]
    (present (or (get env name) (when env-fn (env-fn name))))))

(defn- home-config-path []
  (str (root/user-home) "/.config/isaac.edn"))

(defn- read-home-config [read-file]
  (let [path (home-config-path)]
    (when-let [text (try (read-file path) (catch Exception _ nil))]
      (try
        {:path path :mode nil :remote (get-in (edn/read-string text) [:cli :remote])}
        (catch Exception _ nil)))))

(defn resolve-token
  "Resolve a remote token. Returns {:token ... :source ...}, nil, or {:exit 1 :error ...}."
  [{:keys [url token-file token-env env env-fn read-file file-mode home-config home-config-mode]
    :or   {env {} read-file slurp file-mode posix-mode}}]
  (cond
    token-file
    (if-not (secure-mode? (file-mode token-file))
      {:exit 1 :error (str "remote token file must not be group/world readable; chmod 600 " token-file)}
      (if-let [value (present (read-file token-file))]
        {:token value :source :token-file}
        {:exit 1 :error (str "remote token file is empty: " token-file)}))

    token-env
    (if-let [value (present (get env token-env))]
      {:token value :source :token-env}
      {:exit 1 :error (str "remote token environment variable " token-env " is unset or blank")})

    (present (get env DEFAULT_ENV))
    {:token (present (get env DEFAULT_ENV)) :source :default-env}

    :else
    (let [{file-path :path file-remote :remote}
          (when-not home-config (read-home-config read-file))
          remote (or home-config file-remote)
          mode   (or home-config-mode (when file-path (file-mode file-path)))
          value  (:token remote)]
      (when (and remote (or (nil? url) (= url (:url remote))))
        (if-let [expanded (expand-env value env env-fn)]
          {:token expanded :source :home-config}
          (when-let [literal (present value)]
            (if (secure-mode? mode)
              {:token literal :source :home-config}
              {:exit 1 :error (str "remote config with a literal token must be private; chmod 600 "
                                   (or file-path (home-config-path)))})))))))

(defn- env-value [name]
  (or (config-env/env name) (c3env/env name)))

(defn resolve-system-token [{:keys [token-env] :as opts}]
  (let [names (cond-> [DEFAULT_ENV]
                token-env (conj token-env))]
    (resolve-token (assoc opts
                     :env (into {} (map (juxt identity env-value)) names)
                     :env-fn env-value))))
