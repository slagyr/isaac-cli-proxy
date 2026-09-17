Feature: remote CLI proxy
  Client-side tests for `isaac remote` against a stub /cli WebSocket server.

  Scenario: ships argv, renders stdout, exits with the server's code
    Given a stub /cli server that replies with frames:
      | type   | data        | code |
      | stdout | hello world |      |
      | exit   |             | 0    |
    When isaac remote is run with "${stub.url} -- echo hello"
    Then the stub server received frames:
      | type  | argv              |
      | start | ["echo","hello"]  |
    And the stdout contains "hello world"
    And the exit code is 0

  Scenario: a tty stdout causes the proxy to forward stdout-tty in the start frame (isaac-nfch)
    Given a stub /cli server that replies with frames:
      | type | code |
      | exit | 0    |
    When isaac remote is run with "${stub.url} -- sessions list"
    Then the stub server received frames:
      | type  | argv                 | stdout-tty |
      | start | ["sessions","list"] | true       |
    And the exit code is 0

  Scenario: no command prints usage from the server
    Given a stub /cli server that replies with frames:
      | type   | data           | code |
      | stdout | Usage: remote  |      |
      | exit   |                | 0    |
    When isaac remote is run with "${stub.url}"
    Then the stub server received frames:
      | type  | argv |
      | start | []   |
    And the stdout contains "Usage: remote"
    And the exit code is 0

  Scenario: stdout and stderr render to separate local streams
    Given a stub /cli server that replies with frames:
      | type   | data   | code |
      | stdout | on out |      |
      | stderr | on err |      |
      | exit   |        | 2    |
    When isaac remote is run with "${stub.url} -- fail"
    Then the stdout contains "on out"
    And the stderr contains "on err"
    And the exit code is 2

  Scenario: local stdin is forwarded as stdin frames then stdin-close
    Given the stub defers replies until stdin-close
    And a stub /cli server that replies with frames:
      | type | code |
      | exit | 0    |
    And stdin is:
      """
      alpha
      beta
      """
    When isaac remote is run with "${stub.url} -- cat"
    Then the stub server received frames:
      | type        | data      |
      | start       |           |
      | stdin       | #"alpha\n" |
      | stdin       | #"beta\n"  |
      | stdin-close |           |
    And the exit code is 0

  Scenario: token is sent as the bearer credential
    Given a stub /cli server that replies with frames:
      | type | code |
      | exit | 0    |
    When isaac remote is run with "${stub.url} --token my-secret -- version"
    Then the stub connection authorization is "Bearer my-secret"
    And the exit code is 0

  # --- isaac-tvcg: authenticate without the token in argv ---------------------

  @wip
  Scenario: --token still authenticates but warns that it exposes the secret (isaac-tvcg)
    Given a stub /cli server that replies with frames:
      | type | code |
      | exit | 0    |
    When isaac remote is run with "${stub.url} --token my-secret -- version"
    Then the stub connection authorization is "Bearer my-secret"
    And the stderr contains "--token exposes the secret in the process list"
    And the stderr contains "--token-file"
    And the stderr does not contain "my-secret"
    And the exit code is 0

  @wip
  Scenario: a private token file supplies the bearer credential (isaac-tvcg)
    Given a stub /cli server that replies with frames:
      | type | code |
      | exit | 0    |
    And a file "${tmp}/token" with mode "600" containing "file-secret"
    When isaac remote is run with "${stub.url} --token-file ${tmp}/token -- version"
    Then the stub connection authorization is "Bearer file-secret"
    And the exit code is 0

  @wip
  Scenario: a group- or world-readable token file is refused before connecting (isaac-tvcg)
    Given a stub /cli server that replies with frames:
      | type | code |
      | exit | 0    |
    And a file "${tmp}/token" with mode "644" containing "file-secret"
    When isaac remote is run with "${stub.url} --token-file ${tmp}/token -- version"
    Then the stderr contains "chmod 600"
    And the stderr does not contain "file-secret"
    And the stub server received no connection
    And the exit code is 1

  @wip
  Scenario: a named environment variable supplies the bearer credential (isaac-tvcg)
    Given a stub /cli server that replies with frames:
      | type | code |
      | exit | 0    |
    And environment variable "MY_TOK" is "env-secret"
    When isaac remote is run with "${stub.url} --token-env MY_TOK -- version"
    Then the stub connection authorization is "Bearer env-secret"
    And the exit code is 0

  @wip
  Scenario: an unset named environment variable is an error naming the variable (isaac-tvcg)
    Given a stub /cli server that replies with frames:
      | type | code |
      | exit | 0    |
    When isaac remote is run with "${stub.url} --token-env NOPE_TOK -- version"
    Then the stderr contains "NOPE_TOK"
    And the stub server received no connection
    And the exit code is 1

  @wip
  Scenario: ISAAC_REMOTE_TOKEN supplies the bearer credential with no flag (isaac-tvcg)
    Given a stub /cli server that replies with frames:
      | type | code |
      | exit | 0    |
    And environment variable "ISAAC_REMOTE_TOKEN" is "default-secret"
    When isaac remote is run with "${stub.url} -- version"
    Then the stub connection authorization is "Bearer default-secret"
    And the exit code is 0

  @wip
  Scenario: the home config's remote token is used when the url matches (isaac-tvcg)
    Given a stub /cli server that replies with frames:
      | type | code |
      | exit | 0    |
    And environment variable "ZANE_TOK" is "pointer-secret"
    And the home config file with mode "644" contains:
      """
      {:cli {:remote {:url "${stub.url}" :token "${ZANE_TOK}"}}}
      """
    When isaac remote is run with "${stub.url} -- version"
    Then the stub connection authorization is "Bearer pointer-secret"
    And the exit code is 0

  @wip
  Scenario: a literal token in a readable home config is refused (isaac-tvcg)
    Given a stub /cli server that replies with frames:
      | type | code |
      | exit | 0    |
    And the home config file with mode "644" contains:
      """
      {:cli {:remote {:url "${stub.url}" :token "literal-secret"}}}
      """
    When isaac remote is run with "${stub.url} -- version"
    Then the stderr contains "chmod 600"
    And the stderr does not contain "literal-secret"
    And the stub server received no connection
    And the exit code is 1

  @wip
  Scenario: the home config's token is ignored for a different url (isaac-tvcg)
    Given a stub /cli server that replies with frames:
      | type | code |
      | exit | 0    |
    And the home config file with mode "600" contains:
      """
      {:cli {:remote {:url "wss://elsewhere.example/cli" :token "other-secret"}}}
      """
    When isaac remote is run with "${stub.url} -- version"
    Then the stub connection has no authorization
    And the exit code is 0

  @wip
  Scenario: an explicit token file beats ISAAC_REMOTE_TOKEN (isaac-tvcg)
    Given a stub /cli server that replies with frames:
      | type | code |
      | exit | 0    |
    And environment variable "ISAAC_REMOTE_TOKEN" is "default-secret"
    And a file "${tmp}/token" with mode "600" containing "file-secret"
    When isaac remote is run with "${stub.url} --token-file ${tmp}/token -- version"
    Then the stub connection authorization is "Bearer file-secret"
    And the exit code is 0
  Scenario: the proxy reattaches after a socket drop and replayed frames render once (isaac-4tn1)
    On a dropped socket the proxy keeps local stdio open, emits status to
    stderr (never stdout), reattaches with the stream-id, and renders replayed
    frames exactly once.
    Given a stub /cli server that assigns stream-id "s-1" and replies with frames:
      | type   | data   |
      | stdout | first  |
    And the stub server drops the connection after sending
    And the stub server on reattach replays frames:
      | type   | data   | code |
      | stdout | second |      |
      | exit   |        | 0    |
    When isaac remote is run with "${stub.url} -- sessions list"
    Then the stub server received frames:
      | type   | stream-id |
      | attach | s-1       |
    And the stdout contains "first"
    And the stdout contains "second"
    And the stdout does not contain "firstfirst"
    And the stderr contains "reconnecting"
    And the stderr contains "reattached"
    And the exit code is 0

  Scenario: the proxy keeps reconnecting through a 30 s outage (isaac-iskp)
    A server restart takes 30–60 s. The proxy backs off and keeps trying for
    the reconnect window (default 120 s, ISAAC_REMOTE_RECONNECT_SECS overrides)
    instead of giving up after four sub-second attempts.
    Given the env var "ISAAC_REMOTE_RECONNECT_SECS" is set to "5"
    And a stub /cli server that assigns stream-id "s-1" and replies with frames:
      | type   | data  |
      | stdout | first |
    And the stub server drops the connection after sending
    And the stub server refuses reattach for 6 attempts
    And the stub server on reattach replays frames:
      | type   | data   | code |
      | stdout | second |      |
      | exit   |        | 0    |
    When isaac remote is run with "${stub.url} -- sessions list"
    Then the stdout contains "first"
    And the stdout contains "second"
    And the stderr contains "reconnecting (attempt 7)"
    And the stderr contains "reattached"
    And the exit code is 0

  Scenario: the proxy gives up after the reconnect window (isaac-iskp)
    Given the env var "ISAAC_REMOTE_RECONNECT_SECS" is set to "1"
    And a stub /cli server that assigns stream-id "s-1" and replies with frames:
      | type   | data  |
      | stdout | first |
    And the stub server drops the connection after sending
    And the stub server refuses reattach for 99 attempts
    When isaac remote is run with "${stub.url} -- sessions list"
    Then the stdout contains "first"
    And the stderr contains "could not reconnect within 1s"
    And the exit code is 1

  Scenario: an unknown stream after a server restart starts the command fresh (isaac-iskp)
    After a restart the server has no memory of the stream: it answers the
    attach with an error frame. The proxy falls back to a new start frame with
    the same argv instead of exiting. Rows after the error row are the stub's
    replies to that fresh start.
    Given a stub /cli server that assigns stream-id "s-1" and replies with frames:
      | type   | data  |
      | stdout | first |
    And the stub server drops the connection after sending
    And the stub server on reattach replays frames:
      | type   | data  | code | message            |
      | error  |       |      | unknown stream s-1 |
      | stdout | fresh |      |                    |
      | exit   |       | 0    |                    |
    When isaac remote is run with "${stub.url} -- sessions list"
    Then the stub server received frames:
      | type   | stream-id | argv          |
      | start  |           | sessions list |
      | attach | s-1       |               |
      | start  |           | sessions list |
    And the stdout contains "first"
    And the stdout contains "fresh"
    And the stderr contains "restarted"
    And the exit code is 0

  Scenario: an acp remote replays initialize and session/load once after a fresh start (isaac-iskp)
    The proxy remembers the ACP handshake it forwarded and re-drives it after a
    fresh start, swallowing the duplicate responses so the client sees one of each.
    Given a stub /cli server that assigns stream-id "s-1" and replies with frames:
      | type   | data                                 |
      | stdout | {"jsonrpc":"2.0","id":1,"result":{}} |
      | stdout | {"jsonrpc":"2.0","id":2,"result":{}} |
    And the stub server drops the connection after sending
    And the stub server on reattach replays frames:
      | type   | data                                                 | code | message            |
      | error  |                                                      |      | unknown stream s-1 |
      | stdout | {"jsonrpc":"2.0","id":1,"result":{"replayed":true}}  |      |                    |
      | stdout | {"jsonrpc":"2.0","id":2,"result":{"replayed":true}}  |      |                    |
      | exit   |                                                      | 0    |                    |
    And stdin is:
      """
      {"jsonrpc":"2.0","id":1,"method":"initialize","params":{}}
      {"jsonrpc":"2.0","id":2,"method":"session/load","params":{"sessionId":"abc"}}
      """
    When isaac remote is run with "${stub.url} -- acp"
    Then the stub server received frames:
      | type   | stream-id | argv | data                          |
      | start  |           | acp  |                               |
      | stdin  |           |      | #"\"method\":\"initialize\""  |
      | stdin  |           |      | #"\"method\":\"session/load\"" |
      | stdin-close |      |      |                               |
      | attach | s-1       |      |                               |
      | start  |           | acp  |                               |
      | stdin  |           |      | #"\"method\":\"initialize\""  |
      | stdin  |           |      | #"\"sessionId\":\"abc\""      |
    And the stdout contains "\"id\":1"
    And the stdout contains "\"id\":2"
    And the stdout does not contain "replayed"
    And the exit code is 0
