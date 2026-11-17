(ns isaac.cli-proxy.cli-spec
  (:require
    [clojure.edn :as edn]
    [isaac.foundation.cli.host :as host]
    [isaac.foundation.cli.registry :as registry]
    [isaac.cli-proxy.cli :as sut]
    [isaac.foundation.config.api :as config-api]
    [isaac.foundation.nexus :as nexus]
    [isaac.foundation.fs :as fs]
    [isaac.cli-proxy.proxy :as proxy]
    [isaac.cli-proxy.token :as token]
    [speclj.core :refer :all]))

(describe "parse-remote-opts"

  (it "splits url, proxy options, and post -- remote argv"
    (should= {:url         "ws://host/cli"
              :remote-argv ["version" "--help"]
              :token       "tok"
              :token-file  nil
              :token-env   nil
              :help        nil
              :errors      []}
             (#'sut/parse-remote-opts ["ws://host/cli" "--token" "tok" "--" "version" "--help"])))

  (it "treats a lone url as empty remote argv"
    (should= {:url         "ws://host/cli"
              :remote-argv []
              :token       nil
              :token-file  nil
              :token-env   nil
              :help        nil
              :errors      []}
             (#'sut/parse-remote-opts ["ws://host/cli"])))

  (it "parses --help"
    (should= {:url         "ws://host/cli"
              :remote-argv []
              :token       nil
              :token-file  nil
              :token-env   nil
              :help        true
              :errors      []}
             (#'sut/parse-remote-opts ["ws://host/cli" "--help"]))))

(describe "remote CLI host"

  (around [example]
    (nexus/-with-nested-nexus {:root "/tmp"}
      (example)))

  (it "uses the own loopback server token when no other token is configured"
    (let [mem (fs/mem-fs)
          sent (atom nil)]
      (fs/spit mem "/grover/state/cli-server.edn"
               "{:url \"ws://127.0.0.1:4567/cli\" :token \"host-secret\"}")
      (with-redefs [token/resolve-system-token (constantly nil)
                    proxy/run-proxy! (fn [opts] (reset! sent opts) 0)]
        (should= 0 (sut/run {:root "/grover" :fs mem
                             :_raw-args ["ws://127.0.0.1:4567/cli" "--" "fx-where"]}))
        (should= "host-secret" (:token @sent))
        (should= ["fx-where"] (:argv @sent)))))

  (it "does not use the own server token for a different port"
    (let [mem (fs/mem-fs)
          sent (atom nil)]
      (fs/spit mem "/grover/state/cli-server.edn"
               "{:url \"ws://127.0.0.1:4567/cli\" :token \"host-secret\"}")
      (with-redefs [token/resolve-system-token (constantly nil)
                    proxy/run-proxy! (fn [opts] (reset! sent opts) 0)]
        (should= 0 (binding [*err* (java.io.StringWriter.)]
                     (sut/run {:root "/grover" :fs mem
                               :_raw-args ["ws://127.0.0.1:9999/cli" "--" "fx-where"]})))
        (should-be-nil (:token @sent)))))

  (it "declares remote as local-only"
    (let [manifest (edn/read-string (slurp "src/isaac-manifest.edn"))]
      (should= true (get-in manifest [:isaac/cli :remote :local-only]))))

  (it "refuses remote over the embedded host and leaves ambient runtime identical"
    (registry/register! {:name "remote" :local-only true :run-fn (constantly 0)})
    (let [before-nexus (nexus/necho)
          before-memo  (config-api/process-memo-snapshot)
          err          (java.io.StringWriter.)
          exit         (host/run-embedded {:argv ["remote" "ws://host/cli" "version"]
                                           :in   (java.io.StringReader. "")
                                           :out  (java.io.StringWriter.)
                                           :err  err
                                           :env  {}
                                           :cwd  "/tmp"
                                           :root "/tmp"})]
      (should= 2 exit)
      (should (.contains (str err) "local-only"))
      (should= before-nexus (nexus/necho))
      (should= before-memo (config-api/process-memo-snapshot)))))