@slow @wip
Feature: Server-only commands typed on the host run inside its server
  cli-proxy is the forwarder for foundation's :isaac/cli-forwarder berth
  (see isaac-dcxk). For a :server-only command it sends the argv over /cli to:

  1. the server named by the global --remote <url> option (credentials as for
     `isaac remote`), else
  2. this root's running server, found in state/cli-server.edn. isaac-cli-server
     writes that file when /cli starts: {:url "ws://127.0.0.1:<port>/cli"
     :token "<random>"}, readable only by the server's user, and accepts the
     token on /cli. A restart writes a new token.

  If no server is named and the file is missing, or nothing answers at its url,
  the forwarder reports no server and the command runs locally. A server that
  answers but refuses the token is an error: the command does not fall back to
  running locally.

  The fixture command fx-where is :server-only; it prints "embedded" inside the
  server and "process" in a process of its own.

  Tracked by isaac-w0yx.

  Background:
    Given default Grover setup
    And the remote CLI command is registered
    And the fixture command "fx-where" is registered as server-only

  Scenario: starting the server writes its address and a token only its user can read
    Given config:
      | http.host | 127.0.0.1 |
      | http.port | 0         |
    And the Isaac server is started
    Then the isaac file "state/cli-server.edn" has mode "600"
    And the isaac file "state/cli-server.edn" EDN contains:
      | path  | value                                    |
      | url   | #"ws://127\.0\.0\.1:\d+/cli"            |
      | token | #".{20,}"                                |

  Scenario: a server-only command typed on the host runs inside its server
    Given config:
      | http.host       | 127.0.0.1 |
      | http.port       | 0         |
      | http.auth.token | secret123 |
    And the Isaac server is started
    And stdin is empty
    When isaac is run with "fx-where"
    Then the stdout contains "embedded"
    And the exit code is 0
    And the server log has entries matching:
      | level | event                | argv         | hosted |
      | :info | :cli/command-started | ["fx-where"] | true   |

  Scenario: with no server running, a server-only command runs locally
    Given stdin is empty
    When isaac is run with "fx-where"
    Then the stdout contains "process"
    And the exit code is 0

  Scenario: a server file left by a server that is gone means no server
    Given the isaac file "state/cli-server.edn" exists with:
      """
      {:url "ws://127.0.0.1:1/cli" :token "stale-token-from-a-dead-server"}
      """
    And stdin is empty
    When isaac is run with "fx-where"
    Then the stdout contains "process"
    And the exit code is 0

  Scenario: a bad token is refused, and the command does not fall back to running locally
    Given config:
      | http.host       | 127.0.0.1 |
      | http.port       | 0         |
      | http.auth.token | secret123 |
    And the Isaac server is started
    And the token in "state/cli-server.edn" is replaced with "not-the-token"
    And stdin is empty
    When isaac is run with "fx-where"
    Then the stderr contains "token rejected"
    And the stdout does not contain "process"
    And the exit code is 77

  Scenario: --remote sends a server-only command to the named server
    Given config:
      | http.host | 127.0.0.1 |
      | http.port | 0         |
    And the Isaac server is started
    And the isaac file "state/cli-server.edn" exists with:
      """
      {:url "ws://127.0.0.1:1/cli" :token "stale-token-from-a-dead-server"}
      """
    And stdin is empty
    When isaac is run with "--remote ws://localhost:${server.port}/cli fx-where"
    Then the stdout contains "embedded"
    And the exit code is 0
