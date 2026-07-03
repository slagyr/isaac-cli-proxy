@slow
Feature: remote CLI integration
  Real Isaac server with /cli, driven by the isaac remote proxy.

  Background:
    Given default Grover setup
    And the remote CLI command is registered

  Scenario: a remote command runs on the server and streams back
    Given config:
      | server.host | 127.0.0.1 |
      | server.port | 0         |
    And the Isaac server is started
    And stdin is empty
    When isaac is run with "remote ws://localhost:${server.port}/cli -- --version"
    Then the stdout contains "isaac"
    And the exit code is 0

  Scenario: the server rejects a remote command without a valid token
    Given config:
      | server.host       | 0.0.0.0   |
      | server.port       | 0         |
      | server.auth.token | secret123 |
    And the Isaac server is started
    And stdin is empty
    When isaac is run with "remote ws://localhost:${server.port}/cli -- --version"
    Then the stderr contains "authentication failed"
    And the exit code is 1

  Scenario: a valid token authenticates the remote command
    Given config:
      | server.host       | 0.0.0.0   |
      | server.port       | 0         |
      | server.auth.token | secret123 |
    And the Isaac server is started
    And stdin is empty
    When isaac is run with "remote ws://localhost:${server.port}/cli --token secret123 -- --version"
    Then the stdout contains "isaac"
    And the exit code is 0
  @wip
  Scenario: a remote ACP session runs end-to-end through the generic pipe (isaac-lcay)
    The flagship proof: ACP as `isaac remote acp` — JSON-RPC streaming both
    directions through proxy -> server -> subprocess stdio, interactive
    latency, clean shutdown. The step vocabulary is command-agnostic; the
    ACP-ness lives in the scenario data.
    Given a real cli-server backed by an isaac install with an echo model
    When isaac remote is run interactively with "${server.url} -- acp"
    And the client writes the ACP initialize request to stdin
    Then the client reads an ACP initialize response from stdout before EOF
    When the client writes an ACP session/new request to stdin
    And the client writes an ACP session/prompt request "ping" to stdin
    Then the client reads a streamed ACP response containing "ping" before EOF
    When the client closes stdin
    Then the exit code is 0
