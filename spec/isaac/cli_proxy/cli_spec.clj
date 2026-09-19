(ns isaac.cli-proxy.cli-spec
  (:require
    [clojure.edn :as edn]
    [isaac.cli.host :as host]
    [isaac.cli.registry :as registry]
    [isaac.cli-proxy.cli :as sut]
    [isaac.config.api :as config-api]
    [isaac.nexus :as nexus]
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