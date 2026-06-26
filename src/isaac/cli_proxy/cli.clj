(ns isaac.cli-proxy.cli
  "CLI command `remote`: opens a WebSocket to a server's /cli endpoint, ships the
   handshake argv, pipes local stdin/stdout/stderr, and exits with the server's
   reported exit code.

   Generalizes the `acp --remote` proxy (ws connect, reconnect, stdio relay) but
   command-agnostic. The ws client uses the JDK's java.net.http.WebSocket.

   See PROTOCOL.md for the wire contract."
  (:require
    [isaac.cli.api :as cli-api]))

(defmethod cli-api/option-spec :remote [_id]
  [[nil "--token TOKEN" "Bearer token for remote authentication"]
   ["-h" "--help" "Show help"]])

;; ---------------------------------------------------------------------------
;; M1 TODO:
;;   - parse <url> + remaining argv (the command to run remotely) from opts.
;;   - open a ws to <url>; send {:type "start" :argv [...] :cwd ...}.
;;   - print {:type "stdout"} frames to *out*; exit with {:type "exit" :code}.
;;   - no command -> request usage (empty argv) and print it.
;; ---------------------------------------------------------------------------

(defmethod cli-api/run :remote [_id _opts]
  (binding [*out* *err*] (println "remote: not implemented yet (M1)"))
  1)
