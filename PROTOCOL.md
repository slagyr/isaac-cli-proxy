# Isaac Remote-CLI Wire Protocol

The contract between **isaac-cli-server** (the `/cli` WebSocket endpoint) and
**isaac-cli-proxy** (the `isaac remote …` client). One pipe, two ends — keep
this file in lockstep across both repos.

See isaac-cli-server/PROTOCOL.md for the canonical copy.

## Current lockstep summary

- `start` opens a new remote CLI subprocess.
- Server replies with `start-ack` carrying a `stream-id`.
- On disconnect, server keeps the subprocess alive for a grace window and buffers
  `stdout`/`stderr`/`exit` frames.
- Client reconnects with `attach` + `stream-id`.
- Server replays buffered frames exactly once, then resumes the live stream.
- Proxy prints reconnect status lines to **stderr**, never stdout.
