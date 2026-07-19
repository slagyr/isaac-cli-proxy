(ns isaac.cli-proxy.integration-steps-spec
  (:require
    [babashka.fs :as fs]
    [clojure.java.io :as io]
    [clojure.string :as str]
    [isaac.cli-proxy.integration-steps :as sut]
    [isaac.cli-server.dispatch :as dispatch]
    [speclj.core :refer :all]))

(describe "integration-steps bb + launcher resolution"

  (it "resolves bb to an absolute existing path"
    (let [bb (sut/-resolve-bb-bin)]
      (should (string? bb))
      (should-not (str/blank? bb))
      (should (.isAbsolute (io/file bb)))
      (should (.exists (io/file bb)))))

  (it "writes the test isaac launcher using the resolved bb path"
    (let [bb          (sut/-resolve-bb-bin)
          script-path (sut/-ensure-test-isaac-launcher!)]
      (should (.exists (io/file script-path)))
      (let [body (slurp script-path)]
        (should (str/includes? body (str "exec " bb " --config")))
        (should (str/includes? body "-m isaac.main")))))

  (it "installs the absolute test launcher as cli-server *launcher-command*"
    (let [prior dispatch/*launcher-command*
          path  (sut/-install-test-isaac-launcher!)]
      (try
        (should= [path] dispatch/*launcher-command*)
        (should (.isAbsolute (io/file path)))
        (finally
          (alter-var-root #'dispatch/*launcher-command* (constantly prior))
          (sut/-restore-launcher-command!)))))

  (it "builds the interactive client command from the resolved bb"
    (let [bb  (sut/-resolve-bb-bin)
          cmd (sut/-interactive-client-command ["ws://x/cli" "--" "version"])]
      (should= bb (first cmd))
      (should= "-e" (nth cmd 1))
      (should= "--" (nth cmd 3))
      (should= ["ws://x/cli" "--" "version"] (subvec (vec cmd) 4))))

  )
