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

  @wip
  Scenario: the proxy keeps reconnecting through a 30 s outage (isaac-iskp)
    A server restart takes 30–60 s. The proxy backs off and keeps trying for
    the reconnect window (default 120 s, ISAAC_REMOTE_RECONNECT_SECS overrides)
    instead of giving up after four sub-second attempts.
    Given the env var "ISAAC_REMOTE_RECONNECT_SECS" is "5"
    And a stub /cli server that assigns stream-id "s-1" and replies with frames:
      | type   | data   |
      | stdout | first  |
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

  @wip
  Scenario: the proxy gives up after the reconnect window (isaac-iskp)
    Given the env var "ISAAC_REMOTE_RECONNECT_SECS" is "1"
    And a stub /cli server that assigns stream-id "s-1" and replies with frames:
      | type   | data   |
      | stdout | first  |
    And the stub server drops the connection after sending
    And the stub server drops the connection permanently
    When isaac remote is run with "${stub.url} -- sessions list"
    Then the stdout contains "first"
    And the stderr contains "could not reconnect within 1s"
    And the exit code is 1

  @wip
  Scenario: an unknown stream after a server restart starts the command fresh (isaac-iskp)
    After a restart the server has no memory of the stream. The proxy falls
    back to a new start frame with the same argv instead of exiting.
    Given a stub /cli server that assigns stream-id "s-1" and replies with frames:
      | type   | data   |
      | stdout | first  |
    And the stub server drops the connection after sending
    And the stub server answers reattach with an unknown-stream error
    And the stub server on reattach replays frames:
      | type   | data   | code |
      | stdout | fresh  |      |
      | exit   |        | 0    |
    When isaac remote is run with "${stub.url} -- sessions list"
    Then the stub server received frames:
      | type   | stream-id | argv          |
      | attach | s-1       |               |
      | start  |           | sessions list |
    And the stdout contains "fresh"
    And the stderr contains "restarted"
    And the exit code is 0

  @wip
  Scenario: an acp remote replays initialize and session/load once after a fresh start (isaac-iskp)
    The proxy remembers the ACP handshake it forwarded and re-drives it after a
    fresh start, swallowing the duplicate responses so the client sees one of each.
    Given a stub /cli server that assigns stream-id "s-1" and replies with frames:
      | type   | data                                 |
      | stdout | {"jsonrpc":"2.0","id":1,"result":{}} |
      | stdout | {"jsonrpc":"2.0","id":2,"result":{}} |
    And the stub server drops the connection after sending
    And the stub server answers reattach with an unknown-stream error
    And the stub server on reattach replays frames:
      | type   | data                                 | code |
      | stdout | {"jsonrpc":"2.0","id":1,"result":{}} |      |
      | stdout | {"jsonrpc":"2.0","id":2,"result":{}} |      |
      | exit   |                                      | 0    |
    And the stdin lines are:
      | line                                                                          |
      | {"jsonrpc":"2.0","id":1,"method":"initialize","params":{}}                    |
      | {"jsonrpc":"2.0","id":2,"method":"session/load","params":{"sessionId":"abc"}} |
    When isaac remote is run with "${stub.url} -- acp"
    Then the stub server received frames:
      | type   | stream-id | argv | stdin                                                                         |
      | start  |           | acp  |                                                                               |
      | stdin  |           |      | {"jsonrpc":"2.0","id":1,"method":"initialize","params":{}}                    |
      | stdin  |           |      | {"jsonrpc":"2.0","id":2,"method":"session/load","params":{"sessionId":"abc"}} |
      | attach | s-1       |      |                                                                               |
      | start  |           | acp  |                                                                               |
      | stdin  |           |      | {"jsonrpc":"2.0","id":1,"method":"initialize","params":{}}                    |
      | stdin  |           |      | {"jsonrpc":"2.0","id":2,"method":"session/load","params":{"sessionId":"abc"}} |
    And the stdout contains "\"id\":1" exactly once
    And the stdout contains "\"id\":2" exactly once
    And the exit code is 0
