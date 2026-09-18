(ns isaac.cli-proxy.token-spec
  (:require
    [isaac.cli-proxy.token :as sut]
    [speclj.core :refer [describe it should-contain should-not-contain should=]]))

(describe "remote token resolution"
  (it "prefers a private token file and trims its trailing newline"
    (let [read-file #(if (= "/tmp/token" %) " file-secret\n" nil)]
      (should= {:token "file-secret" :source :token-file}
               (sut/resolve-token {:url "wss://ship/cli"
                                   :token-file "/tmp/token"
                                   :env {"ISAAC_REMOTE_TOKEN" "default-secret"}
                                   :read-file read-file
                                   :file-mode (constantly 0600)}))))

  (it "rejects group-readable credential files"
    (let [result (sut/resolve-token {:url "wss://ship/cli"
                                     :token-file "/tmp/token"
                                     :read-file (constantly "secret")
                                     :file-mode (constantly 0640)})]
      (should= 1 (:exit result))
      (should-contain "chmod 600" (:error result))
      (should-not-contain "secret" (:error result))))

  (it "reads an explicitly named environment variable"
    (should= {:token "named-secret" :source :token-env}
             (sut/resolve-token {:url "wss://ship/cli"
                                 :token-env "SHIP_TOKEN"
                                 :env {"SHIP_TOKEN" "named-secret"
                                       "ISAAC_REMOTE_TOKEN" "default-secret"}})))

  (it "reports an unset explicitly named environment variable"
    (should= {:exit 1 :error "remote token environment variable SHIP_TOKEN is unset or blank"}
             (sut/resolve-token {:url "wss://ship/cli" :token-env "SHIP_TOKEN" :env {}})))

  (it "uses ISAAC_REMOTE_TOKEN by default"
    (should= {:token "default-secret" :source :default-env}
             (sut/resolve-token {:url "wss://ship/cli"
                                 :env {"ISAAC_REMOTE_TOKEN" "default-secret"}})))

  (it "substitutes environment variables in a matching home config"
    (should= {:token "pointer-secret" :source :home-config}
             (sut/resolve-token {:url "wss://ship/cli"
                                 :env {"SHIP_TOKEN" "pointer-secret"}
                                 :home-config {:url "wss://ship/cli" :token "${SHIP_TOKEN}"}
                                 :home-config-mode 0644})))

  (it "requires a private home config for literal tokens"
    (should-contain "chmod 600"
                    (:error (sut/resolve-token {:url "wss://ship/cli"
                                                :env {}
                                                :home-config {:url "wss://ship/cli" :token "literal-secret"}
                                                :home-config-mode 0644}))))
  )
