(ns isaac.cli-proxy.integration-steps-spec
  (:require
    [clojure.java.io :as io]
    [clojure.string :as str]
    [gherclj.core :as g]
    [isaac.cli-proxy.integration-steps :as sut]
    [speclj.core :refer :all]))

;; isaac-dqy9 deleted the test isaac launcher: commands run inside the server
;; process, so there is no binary for the harness to write or point cli-server
;; at. Only the interactive client — a real second process, the proxy under
;; test — still resolves bb.

(describe "integration-steps bb resolution"

  (it "resolves bb to an absolute existing path"
    (let [bb (sut/-resolve-bb-bin)]
      (should (string? bb))
      (should-not (str/blank? bb))
      (should (.isAbsolute (io/file bb)))
      (should (.exists (io/file bb)))))

  (it "includes the remote stderr when an exit code differs"
    (with-redefs [g/get (fn [key] ({:exit-code 1 :stderr "remote rejected: bad token"} key))
                  g/should= (fn [expected actual]
                              (when-not (= expected actual)
                                (throw (ex-info (str "Expected: " expected ", got: " actual) {}))))]
      (should-throw Exception #"remote rejected: bad token"
        (sut/remote-exit-code-is 0))))

  (it "builds the interactive client command from the resolved bb"
    (let [bb  (sut/-resolve-bb-bin)
          cmd (sut/-interactive-client-command ["ws://x/cli" "--" "version"])]
      (should= ["env" "ISAAC_REMOTE_TOKEN=fixture-cli-secret" bb] (subvec (vec cmd) 0 3))
      (should= "-e" (nth cmd 3))
      (should= "--" (nth cmd 5))
      (should= ["ws://x/cli" "--" "version"] (subvec (vec cmd) 6))))

  )
