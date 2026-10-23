# The server is Jetty 12 through rj9a

> Changes in part [http-kit serves, reitit routes](2026-10-12-http-kit-and-reitit.md) (the server; reitit stays), [Every value that sets behaviour is a setting; no hidden defaults](2026-10-22-every-value-that-sets-behaviour-is-a-setting.md) (the server's settings), [The app passes its log](2026-10-22-the-app-passes-its-log.md) (what goes to `start-server!`'s log) and [An sse line the browser left is closed by its next ping or send](2026-10-23-an-sse-line-the-browser-left-is-closed-by-the-next-ping.md) (how a dropped line is seen). Since v0.7.5.

**Decision:** bridge's server is Jetty 12 (12.1.14) through rj9a, `info.sunng/ring-jetty9-adapter` 0.40.5: Jetty's core without the servlet layer, serving one Ring handler. http-kit is gone.

- **One plain HTTP/1.1 connector**, built by bridge (rj9a's is replaced in its `:configurator`): `:ip`, `:port`, the PROXY protocol first when it is on, Jetty's version never sent. The JVM's shutdown doesn't stop it (`setStopAtShutdown false`); the app stops it.
- **Every request on a virtual thread of its own**, always: bridge's Jetty handler hands the request to a new virtual thread when Jetty didn't (Jetty runs the first request of a connection that came through the PROXY detector on a pool thread). Jetty's own platform threads (accepting, reading and writing sockets) are a `QueuedThreadPool` by the setting `:threads`.
- **In front of the app's handler**, in order: Jetty's `GracefulHandler` (a stop lets running requests finish), its `SizeLimitHandler` (a body over `:max-body-bytes` is refused 413, with or without a Content-Length), then bridge's handler (the virtual thread, the Server header).
- **sse is a streamed Ring answer** (`ring.core.protocols/StreamableResponseBody`) written by its request's virtual thread: it waits for the next message in the line's queue up to `:ping-every-ms`, and pings when none came. `send-message-on-sse-line!` puts the message in the queue and returns at once, so a slow browser holds up only its own line. The answer says `Connection: close`.
- **A browser leaving an sse line is seen when its socket ends.** Jetty doesn't read a socket while it answers a request, so it never notices a browser that left, and a write to such a socket succeeds once (the browser's side answers it with a reset) and fails only at the next. So the line asks Jetty's `EndPoint` to say when its socket can be read (`tryFillInterested`): nothing more comes from a browser on an sse line, so the socket's end is the only thing to read, and the line is closed then, not at the next ping. A write that fails (Jetty's `EofException`) closes it too, as the fallback. `Connection: close` makes sure Jetty never reads another request on a socket the line was watching. The reflection into http-kit's `AsyncChannel` (`bridge.channel`) is gone. "A dropped line is closed within one ping, exactly once" still holds, now within the moment the socket ends; `bridge.sse-close-test` drops real sockets for it, with the ping a minute away.
- **ws is Ring's websocket protocols as rj9a carries them:** the handler answers an upgrade request with `{:ring.websocket/listener …}`, bridge's own `Listener`; a line's channel is Jetty's websocket session. Each message the browser sends is answered on a virtual thread of its own, so a slow answer doesn't stop Jetty reading the next frame. A message over `:max-ws-message-bytes` closes the line (1009).
- **A stop** closes the open sse lines first (each browser reopens its own, on another server; a line that opens during the stop is closed at once), then Jetty stops taking requests and lets running ones finish up to `:stop-wait-ms` (its stop timeout), then closes the connections left; bridge interrupts the request threads still running and waits up to `:stop-wait-after-cut-ms` for them, and returns whether every one had ended, as before.
- **The PROXY protocol:** `:disable`, none; `:optional`, Jetty's `ProxyConnectionFactory`, which hands a connection without the PROXY line to plain HTTP; `:enable`, the same, but such a connection is closed. The asker's address (`:remote-addr`) is the PROXY line's.
- **The Server header** is the setting's, put on every answer bridge's handler gives, or none; Jetty's own early refusals (400, 414, 431) carry none.
- **The log:** a failure no step caught (outside every handler's steps, a ws message that isn't JSON) is written to `start-server!`'s log, `:bridge/server-error`, and answered 500. `:bridge/server-warning` is gone: Jetty's own lines go to SLF4J, to whatever provider the app puts on its classpath (none: dropped, and SLF4J says so once at start).

The settings, old to new:

| http-kit (v0.7.4) | Jetty (v0.7.5) |
|---|---|
| `:ip`, `:port` | the same: the connector's host and port |
| `:proxy-protocol` `:disable` `:enable` `:optional` | the same values, through `ProxyConnectionFactory` |
| `:client-address-header` | the same (bridge's own) |
| `:server-header` | the same; Jetty's version is never sent |
| `:max-body-bytes` | the same, through `SizeLimitHandler`, 413 |
| `:max-header-line-bytes`, the longest request or header line, 414 | `:max-request-head-bytes`, the request line and every header together (Jetty's `requestHeaderSize`), 431; an address too long for it, 414 |
| `:max-ws-message-bytes` | the same: Jetty's largest text message, binary message and frame |
| (none: http-kit closed no silent connection) | `:idle-timeout-ms`, required: a connection, an sse or ws line included, that passes nothing this long is closed (the connector's and the websocket container's idle timeout); checked to be longer than `:ping-every-ms`, so a pinged line is never cut |
| (none: one virtual thread per request, http-kit's own threads fixed) | `:threads {:min :max :idle-timeout-ms}`, required: Jetty's platform threads; requests still run on virtual threads |
| `:stop-wait-ms` | the same meaning: Jetty's stop timeout, through `GracefulHandler` |
| `:stop-wait-after-cut-ms` | the same meaning: bridge interrupts the request threads left and waits this long; also the pool's own stop timeout |
| `:addresses`, `:ping-every-ms`, `:sse-reconnect-after-ms`, `:ws-reopen-after-ms`, `:rate-limit-buckets-before-cleanup`, `:metric-buckets` | the same |

The api, besides the rename: `close-line!` is new (an sse or ws line closed from the server; before, the app closed http-kit's channel itself). `send-message-on-sse-line!` returns whether the line was open when the message was queued, not whether it was written. A line's `:bridge/channel` is bridge's own (sse) or Jetty's session (ws), still a key for the app's map of lines. A request carries rj9a's keys: no `:websocket?` (doorman reads the Upgrade header) and no `:async-channel`; `:body` is an input stream for every request; bridge adds `:bridge/sse-lines`, the server's open lines. The map `start-server!` returns has `:port` as before; its other keys are bridge's.

Fixed, not settings, each with its reason:

- **HTTP/1.1 only.** HTTP/2 needs TLS at Jetty (browsers speak no cleartext HTTP/2) or a proxy that speaks h2c upstream, and rj9a's http2 module; bridge sits behind a proxy that speaks HTTP/1.1 to it. Turning it on is its own decision.
- **Jetty's defaults for the rest of the connector:** the output buffer (32 KiB) and aggregation size (8 KiB), the Date header sent, X-Powered-By not, the RFC 9110 HTTP compliance and the default URI compliance, acceptors and selectors by the CPU count, the OS's accept queue. None of them changes what an app sees; each that does becomes a setting when an app needs it.
- **The virtual threads.** Every request on its own, as with http-kit: 2,000 sse lines hold 2,000 virtual threads, nearly free, where a platform pool would need 2,000 threads.

**Why:** http-kit's 2026 security fixes, request smuggling (GHSA-qwg7-h8j3-975p, high), response splitting (GHSA-4gx4-prhm-c233, medium) and the websocket size check (GHSA-j9v4-rr7v-r6pw, low), exist only in 2.9.0's betas; a server facing the internet can't wait on a beta for a smuggling fix. Jetty ships its security fixes as stable patch releases, from a large team, and has websockets and HTTP/2. rj9a over Ring's official Jetty adapter because that one goes through the servlet layer, which is slower and adds nothing bridge uses. The dropped-sse-line problem, which needed the reflection into http-kit, is answered by Jetty's own api.

**The price:** a heavier dependency (Jetty's server, io, http, util and websocket jars, and slf4j-api). Two settings more and one changed meaning (`:max-request-head-bytes`). bridge reaches into Jetty under rj9a in three places: its own connector, its own handler in front of rj9a's, and Jetty's `EndPoint` from an sse line, found through a thread-local while rj9a writes the answer on bridge's thread; an rj9a that writes the answer on another thread loses the at-once close (the write fallback stays), and `bridge.sse-close-test` catches it.

**Instead of:**
- **http-kit 2.9.0-beta**: the fixes, but a pre-release with breaking server changes under every app
- **staying on http-kit 2.8.1**: the smuggling hole stays open
- **Ring's official Jetty adapter**: the servlet layer, slower
- **Jetty with bridge's own Ring glue, no rj9a**: bridge would write and keep the request map, the answer writing and the websocket glue that rj9a already keeps
- **async Ring handlers** (rj9a's `:async?`): every step would answer through callbacks, for nothing virtual threads don't already give
- **a platform pool for the requests**: an sse line holds its thread, so the pool would cap the open lines
- **closing a dropped line only on a failed write**: a browser that closed cleanly is seen two pings late, and a line no one writes to lives until the next ping
- **an idle timeout as the only bound on a dropped line**: it would have to be shorter than the ping, and then cut every quiet live line
