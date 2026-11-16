(ns isaac.cli-proxy.forwarder-spec
  (:require
    [isaac.cli-proxy.forwarder :as sut]
    [isaac.cli-proxy.proxy :as proxy]
    [isaac.cli-proxy.config.pointer :as pointer]
    [isaac.cli-proxy.token :as token]
    [isaac.foundation.fs :as fs]
    [speclj.core :refer :all]))

(describe "server-only forwarding"
  (it "global --remote overrides the stale local file and uses remote credentials"
    (let [mem (fs/mem-fs) sent (atom nil)]
      (fs/spit mem "/grover/state/cli-server.edn" "{:url \"ws://127.0.0.1:1/cli\" :token \"stale\"}")
      (with-redefs [pointer/read-config (constantly {})
                    token/resolve-system-token (fn [_] {:token "named-secret"})
                    proxy/run-proxy! (fn [opts] (reset! sent opts) 0)]
        (should= {:exit 0} ((sut/make-forwarder)
                             {:root "/grover" :fs mem
                              :argv ["--remote" "ws://localhost:4567/cli" "fx-where"]}))
        (should= {:url "ws://localhost:4567/cli" :token "named-secret" :argv ["fx-where"]} @sent))))

  (it "runs locally without a server file"
    (should= {:no-server true} ((sut/make-forwarder) {:root "/absent" :argv ["fx-where"]})))

  (it "uses the discovered credential to run on the server"
    (let [mem (fs/mem-fs)
          sent (atom nil)]
      (fs/spit mem "/grover/state/cli-server.edn" "{:url \"ws://127.0.0.1:42/cli\" :token \"private\"}")
      (with-redefs [proxy/run-proxy! (fn [opts] (reset! sent opts) 3)]
        (should= {:exit 3} ((sut/make-forwarder) {:root "/grover" :fs mem :argv ["fx-where"]}))
        (should= "private" (:token @sent))
        (should= ["fx-where"] (:argv @sent)))))

  (it "falls back on connection refused, not on rejection"
    (let [mem (fs/mem-fs)]
      (fs/spit mem "/grover/state/cli-server.edn" "{:url \"ws://127.0.0.1:1/cli\" :token \"bad\"}")
      (with-redefs [proxy/run-proxy! (constantly 69)]
        (should= {:no-server true} ((sut/make-forwarder) {:root "/grover" :fs mem :argv ["fx-where"]})))
      (with-redefs [proxy/run-proxy! (constantly 77)]
        (should= {:exit 77} ((sut/make-forwarder) {:root "/grover" :fs mem :argv ["fx-where"]}))))))
