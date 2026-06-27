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