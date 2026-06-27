(ns isaac.cli-proxy.cli-spec
  (:require
    [isaac.cli-proxy.cli :as sut]
    [speclj.core :refer :all]))

(describe "parse-remote-opts"

  (it "splits url, proxy options, and post -- remote argv"
    (should= {:url         "ws://host/cli"
              :remote-argv ["version" "--help"]
              :token       "tok"
              :help        nil
              :errors      []}
             (#'sut/parse-remote-opts ["ws://host/cli" "--token" "tok" "--" "version" "--help"])))

  (it "treats a lone url as empty remote argv"
    (should= {:url         "ws://host/cli"
              :remote-argv []
              :token       nil
              :help        nil
              :errors      []}
             (#'sut/parse-remote-opts ["ws://host/cli"])))

  (it "parses --help"
    (should= {:url         "ws://host/cli"
              :remote-argv []
              :token       nil
              :help        true
              :errors      []}
             (#'sut/parse-remote-opts ["ws://host/cli" "--help"]))))