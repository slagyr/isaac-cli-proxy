# isaac.cli-proxy — the remote CLI client

You are a crew running inside Isaac. This chapter covers what
**isaac-cli-proxy** owns: the client half of running an `isaac` command
against a remote server instead of the local process — the `isaac remote`
command and its `use`/`list`/`remove`/`status` subcommands, bearer-token resolution,
the WebSocket wire client, and reconnect/resume. Foundation's own chapter
(`handbook__read` topic `isaac.foundation`) covers config mechanics and the
vocabulary table; read it for that context. Routing to a remote server is
always explicit — there is no transparent default-remote mode for an
ordinary `isaac <command>`; every remote invocation goes through the
`isaac remote <name> -- <command...>` or
`isaac remote <url>/cli -- <command...>` form this chapter documents. This
chapter's own topic id is `isaac.cli-proxy`.

isaac-cli-proxy does not know what happens to a command once it lands on the
far end. The server side — accepting the WebSocket, authenticating the
upgrade, and actually running the command in-process — belongs to
**isaac-cli-server** (`handbook__read` topic `isaac.cli-server`); this
chapter only covers the client that dials out. The wire contract both sides
speak is written down once, in `PROTOCOL.md` in this repo (a canonical copy
also lives in isaac-cli-server) — this chapter describes the behavior that
contract produces, not the frame format itself.

## Named remote targets

The optional home pointer file `~/.config/isaac.edn` stores named targets under
`:cli :remotes`, alongside `:root` and any other bootstrap settings. This module
owns the targets; foundation does not read them. No command routes remotely by
default: ordinary `isaac <command>` always runs locally. Explicitly select a
host by name (`isaac remote zane -- sessions list`) or by URL
(`isaac remote wss://host/cli -- sessions list`). A URL lookup also honors the
legacy single `:cli :remote` token when the URLs match.

```clojure
{:root "/Users/me/.isaac"
 :cli {:remotes {:zane {:url "wss://zanebot.example/cli"
                        :token {:command "security find-generic-password -s zanebot-cli-token -w"}}
                 :yopp {:url "wss://yopp.example/cli" :token "${YOPP_TOKEN}"}}}}
```

Manage each target independently; `use` and `remove` preserve the root pointer,
other config keys and other remotes:

```
isaac remote use zane wss://zanebot.example/cli --token-command 'security find-generic-password -s zanebot-cli-token -w'
isaac remote use yopp wss://yopp.example/cli --token-env YOPP_TOKEN
isaac remote list
isaac remote status zane
isaac remote remove yopp
```

`use` replaces one name. `--token-command CMD` records the command, not its
output; the command runs at use time (when connecting), and trimmed stdout
becomes the bearer credential. Failure, an empty result, or an unknown name
is an error before connecting. `--token-file PATH` reads a mode-600 file and
stores its literal token in the home pointer file, chmod'd 600. `list` prints
names, URLs and ready-to-use invocations but never secrets and never probes.
`status` probes the named host with `--version` and prints the URL and
reachability; with no remotes configured it says so.

Micah's hand-written wrappers can be one-liners (no generated shims):

```
exec isaac remote zane -- "$@"
exec toad acp "isaac remote zane -- acp --crew zane" "$@"
```

## Running a command on a remote server

**What it is.** `isaac remote <url>/cli -- <command...>` opens a WebSocket
to that URL, ships `<command...>` as the handshake `argv`, then pipes local
stdin to the connection and renders the server's `stdout`/`stderr` frames
back to the matching local stream. When the server reports the command's
exit, the proxy's own process exits with that **same code** — a remote
`isaac sessions list` behaves, from the caller's terminal, like the local
command would, modulo network latency. An empty `argv` (just the URL, no
`--`) asks the server for its usage text.

A handful of commands never make sense to run this way — `server`,
`service`, `modules`, and `remote` itself — because a down server has to be
startable locally even when a remote target is configured. This module
marks its own `remote` command `:local-only true` in its manifest
(`src/isaac-manifest.edn`), and isaac-cli-server refuses the same
`:local-only` commands if they somehow arrive over the wire; that refusal
is the server's, not this module's (`isaac.cli-server` topic).

**How to change it.** Nothing to configure beyond the target (above) — the
command to run is just the CLI invocation itself.

**How to verify.** Run something cheap and observable, e.g. `isaac remote
<url>/cli -- --version`, and check the exit code: `0` and version text on
stdout means the round trip works end-to-end, including auth.

### Troubleshooting

- **The proxy exits 1 with no server-side error text.** That's a local
  protocol failure (a malformed frame or a reconnect that never
  recovered — see Reconnect and stream resume, below) rather than the
  command's own exit code; the command's real exit status is whatever the
  server reports on its `exit` frame, passed through verbatim.
- **stdout and stderr look interleaved wrong.** They're carried as
  separate frame types and rendered to the matching local stream
  independently — if a script depends on their relative ordering, that
  ordering is best-effort across the two frame streams, not guaranteed.
- **`isaac remote <url>/cli -- server ...` (or `service`/`modules`/
  `remote`) is refused by the server.** Expected — those commands are
  `:local-only` and isaac-cli-server rejects them over the wire; run them
  locally instead.

## Authentication

**What it is.** Every remote connection needs a bearer token, resolved in
one strict order, first match wins:

1. `--token-file PATH` — the file's contents, trimmed. The file **must**
   be unreadable by group and other (mode `600`-equivalent) or the proxy
   refuses to connect at all and reports the exact `chmod` needed.
2. `--token-env VAR` — the named environment variable. Unset or blank is a
   hard error naming the variable, not a silent empty token.
3. `ISAAC_REMOTE_TOKEN` — the same environment variable, tried
   automatically with no flag.
4. The named remote's `:token` (or the legacy `:cli :remote :token` for
   a URL-matched target): `${VAR}` reads the environment, a literal requires
   the home pointer file to be mode 600, and `{:command "…"}` runs at connect
   time and uses trimmed stdout. A failed or empty command refuses connection
   without leaking its output. An explicit token file or env flag overrides
   a named remote token.
5. For a loopback URL targeting this root’s own server, the token from
   `state/cli-server.edn` if no other token was configured.

The older `--token TOKEN` flag still authenticates, but it's deprecated:
passing a secret on the command line exposes it in the process list and
shell history. Using it prints a one-time warning to stderr (never
repeating the secret) and skips this whole resolution chain.

None of this is a `config:` path — a bearer token is deliberately never
written to Isaac's schema-checked config tree, the same "secrets never in
config" posture as everywhere else in Isaac, just enforced here at the
filesystem layer (mode bits) instead of `${VAR}`-in-config redaction, since
the pointer file predates config entirely.

**How to change it.** Pick the resolution source that fits: an env var for
a script, `--token-file` for a long-lived secret on disk, or `remote use <name> <url>
--token-env VAR` / `--token-file PATH` to bake the choice into the pointer
file so plain `isaac remote <url>/cli -- ...` needs no flags at all.

**How to verify.** A rejected token surfaces as `isaac remote: ... token
rejected` on stderr and exit code `77` — never a silent hang or a generic
connection error. `isaac remote status` exercises the same resolution chain
against the configured target.

### Troubleshooting

- **"remote token file must not be group/world readable."** `chmod 600` the
  file named in the error; the proxy checks this before it ever opens a
  connection, so the secret is never sent with the wrong permissions.
- **"remote config with a literal token must be private."** Same fix,
  applied to the pointer file itself: `chmod 600 ~/.config/isaac.edn`, or
  switch to a `${VAR}` reference instead of a literal in that file.
- **No token found at all.** The proxy still attempts the connection (in
  case the server is unauthenticated) but warns on stderr that it tried
  `--token-file`, `--token-env`, `ISAAC_REMOTE_TOKEN`, and the pointer
  file, in that order — read the warning to see which source you meant to
  supply.
- **Exit code 77, "token rejected."** The credential reached the server but
  was refused at the WebSocket upgrade — the same 401 gate
  `isaac.cli-server`'s chapter describes on the auth side. Distinct from
  exit `69` (server unreachable at all): `77` means you connected and were
  turned away.

## Reconnect and stream resume

**What it is.** Once a command starts, its `stream-id` survives a dropped
socket. On a drop the proxy keeps local stdio open, prints status to
**stderr only** (`isaac remote: reconnecting (attempt N)…`), and retries
with exponential backoff (0.25s, 0.5, 1, 2, 4, then a 5s cap) for a
**120-second** window by default — long enough to ride out a typical server
restart, not a snap timeout after a few sub-second tries. Set
`ISAAC_REMOTE_RECONNECT_SECS` to change that window; it's a process
environment variable, not config, so it applies for the life of that one
`isaac remote` invocation.

A successful reattach replays any frames the server buffered while
detached exactly once, then resumes live streaming (`isaac remote:
reattached`). If the server no longer recognizes the `stream-id` at all —
typically because it restarted and lost all in-memory stream state — the
proxy starts the command over from scratch with the same `argv` (`isaac
remote: restarted`) rather than exiting. For an `acp` remote specifically,
the proxy also remembers the last `initialize` and `session/load` handshake
it forwarded and replays those two lines after a fresh start, swallowing
the duplicate JSON-RPC responses so the ACP client on the other end of
local stdio sees exactly one response to each, never a repeat. If the
reconnect window elapses with no success, the proxy gives up and exits `1`,
reporting how long it tried.

**How to change it.** `ISAAC_REMOTE_RECONNECT_SECS=<n>` before invoking
`isaac remote`. There is no `config:` path — reconnect behavior is a
property of one client invocation, not a server-side or persistent setting.

**How to verify.** Watch stderr during a server restart: `reconnecting`,
then either `reattached` (fast enough that the server still remembered the
stream) or `restarted` (server-side memory of the stream was gone, command
re-ran from the top with the same argv).

### Troubleshooting

- **"could not reconnect within Ns."** The window elapsed with every
  attempt failing — check the server is actually coming back up, or raise
  `ISAAC_REMOTE_RECONNECT_SECS` for a slower restart.
- **A long-running remote command appears to restart from scratch instead
  of resuming.** That's correct once the server's own memory of the
  `stream-id` is gone (a real server restart clears it) — not a proxy bug.
  For `acp`, confirm the replayed `initialize`/`session/load` lines are
  visible in the client's own log if session continuity still looks wrong
  afterward.
- **Output looks duplicated after a reattach.** Buffered frames from
  before the drop are replayed exactly once on reattach; genuinely
  duplicated output past that point is worth reporting rather than
  assuming it's expected replay behavior.
