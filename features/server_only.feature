@slow
Feature: The host reaches its own server with isaac remote
  Server-only commands never run in a local CLI process, and nothing forwards
  them (isaac-crrm removed the isaac-dcxk forwarder). Typed locally, one
  refuses with the way to reach it. On the server's own host,
  `isaac remote <url> -- <command>` reaches that server without a configured
  token: isaac-cli-server writes state/cli-server.edn when /cli starts,
  {:url "ws://127.0.0.1:<port>/cli" :token "<random>"}, readable only by the
  server's user, and `isaac remote` to that loopback url uses its token. A
  restart writes a new token.

  The fixture command fx-where is :server-only; it prints "embedded" inside the
  server and "process" in a process of its own.

  Tracked by isaac-crrm (was isaac-w0yx).

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

  Scenario: a server-only command typed on the host refuses with the remote hint, even with its server running (isaac-crrm)
    Given config:
      | http.host       | 127.0.0.1 |
      | http.port       | 0         |
      | http.auth.token | secret123 |
    And the Isaac server is started
    And stdin is empty
    When isaac is run with "fx-where"
    Then the stderr contains "fx-where runs on the server: isaac remote <name> -- fx-where"
    And the stdout does not contain "embedded"
    And the stdout does not contain "process"
    And the exit code is 2

  Scenario: isaac remote to the host's own server runs a server-only command there with the local token (isaac-crrm)
    Given config:
      | http.host       | 127.0.0.1 |
      | http.port       | 0         |
      | http.auth.token | secret123 |
    And the Isaac server is started
    And stdin is empty
    When isaac is run with "remote ws://127.0.0.1:${server.port}/cli -- fx-where"
    Then the stdout contains "embedded"
    And the exit code is 0
    And the server log has entries matching:
      | level | event                | argv         | hosted |
      | :info | :cli/command-started | ["fx-where"] | true   |

  Scenario: a stale local token is refused by the host's own server (isaac-crrm)
    Given config:
      | http.host       | 127.0.0.1 |
      | http.port       | 0         |
      | http.auth.token | secret123 |
    And the Isaac server is started
    And the token in "state/cli-server.edn" is replaced with "not-the-token"
    And stdin is empty
    When isaac is run with "remote ws://127.0.0.1:${server.port}/cli -- fx-where"
    Then the stderr contains "token rejected"
    And the stdout does not contain "embedded"
    And the exit code is 77
