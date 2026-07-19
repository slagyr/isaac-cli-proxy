# 🍏 Isaac CLI Proxy ⚡

<img align="left" width="200" src="https://raw.githubusercontent.com/slagyr/isaac-cli-proxy/main/isaac-cli-proxy.png" alt="isaac-cli-proxy" style="margin-right: 20px; margin-bottom: 10px;">

Remote CLI client for [Isaac](https://github.com/slagyr/isaac). Implements
`isaac remote …`: connects to the host `/cli` WebSocket, forwards local argv and
stdio, and supports reconnect/attach when the socket drops.

Pairs with [isaac-cli-server](https://github.com/slagyr/isaac-cli-server).
Wire protocol: [PROTOCOL.md](PROTOCOL.md) (canonical copy lives on the server
repo).

<br>

[![CI Tests](https://github.com/slagyr/isaac-cli-proxy/actions/workflows/ci-tests.yml/badge.svg)](https://github.com/slagyr/isaac-cli-proxy/actions/workflows/ci-tests.yml)
[![License: MIT](https://img.shields.io/badge/License-MIT-yellow.svg)](https://opensource.org/licenses/MIT)
[![Clojure](https://img.shields.io/badge/Clojure-1.11%2B-blue?logo=clojure)](https://clojure.org)
[![Babashka](https://img.shields.io/badge/Babashka-1.3%2B-red?logo=clojure)](https://babashka.org)
[![Java](https://img.shields.io/badge/Java-21%2B-orange?logo=openjdk)](https://openjdk.org/)

<br clear="left">

## Development

```bash
bb spec       # Run Clojure specs
bb features   # Run Gherkin feature scenarios
bb ci         # Run both
```

Depends on [isaac-foundation](https://github.com/slagyr/isaac-foundation),
[isaac-agent](https://github.com/slagyr/isaac-agent),
[isaac-server](https://github.com/slagyr/isaac-server), and
[isaac-cli-server](https://github.com/slagyr/isaac-cli-server).

## License

Copyright © 2026 Micah Martin. See [LICENSE](LICENSE).
