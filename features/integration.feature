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