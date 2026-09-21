(ns isaac.cli-proxy.integration-steps-spec
  (:require
    [clojure.java.io :as io]
    [clojure.string :as str]
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

  (it "builds the interactive client command from the resolved bb"
    (let [bb  (sut/-resolve-bb-bin)
          cmd (sut/-interactive-client-command ["ws://x/cli" "--" "version"])]
      (should= bb (first cmd))
      (should= "-e" (nth cmd 1))
      (should= "--" (nth cmd 3))
      (should= ["ws://x/cli" "--" "version"] (subvec (vec cmd) 4))))

  )
