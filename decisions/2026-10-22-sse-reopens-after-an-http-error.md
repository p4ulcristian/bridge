# An sse line reopens after an HTTP error too, after the reconnect setting the browser is handed

> **Added to** by [An sse line tells its break, once, for an offline banner](2026-10-23-an-sse-line-tells-its-break.md) (2026-10-23, built on tidy-up, not released): `:on-break`, required, nil for none, runs once at the first error after an open.

> Changes in part [Every value that sets behaviour is a setting; no hidden defaults](2026-10-22-every-value-that-sets-behaviour-is-a-setting.md): `:sse-reconnect-after-ms` reaches the browser through the app too, and `open-sse-line!` takes it as a required option. Since v0.1.3.

**Decision:** the browser's sse line stays open until `close-sse-line!`, whatever ended it.

- **A network break**: as before, the browser's EventSource reopens the line by itself after the line's `retry:` (the server's `:sse-reconnect-after-ms`), and sends `Last-Event-ID`.
- **An HTTP error** (a proxy's 502 while the server restarts in a deploy, any status but 200, or an answer that isn't `text/event-stream`): the EventSource gives the line up for good, `readyState` CLOSED, and never reopens. mailroom now sees that in `onerror` and opens a new EventSource after `:reconnect-after-ms`, and again after each failure, until one opens or the app closes the line. A line closed while a reopen waits stays closed.
- **`open-sse-line!` takes `:reconnect-after-ms`**, required, `[:int {:min 1}]`: the server's `:sse-reconnect-after-ms` setting, which the app puts in the page with the addresses, as it does `:ws-reopen-after-ms` for `open-ws-line!`'s `:reopen-after-ms`. A missing one refuses, `:mailroom/bad-options`. The browser has no value of its own.
- **The app learns of it through `:on-open`**: the new line's open calls `(on-open true)`, as after a network break, so the app asks for what it missed. A new EventSource starts without `Last-Event-ID`: on the server the line's `:mailroom/last-event-id` is nil, and the catching up is the app's, by `:on-open`.
- **The line is mailroom's map now**, not the EventSource; `close-sse-line!` takes it, as before.

**Why:** the docs said a broken line reopens by itself; it did only for a network break. During a deploy the proxy in front answers 502 until the new server is up, and every open tab's EventSource took that as final: the screens stayed deaf to the server for the rest of their life, with no error shown. The delay is the same setting the server already sends as `retry:`, so both ways back wait the same time; it is handed to the browser because the `retry:` field arrives only on a line that opened, and the error came before one did.

**The price:** the app hands one more number to the browser and writes it in `open-sse-line!`'s options. A server answering with an error is asked again every `:reconnect-after-ms` by every open tab, without backing off, as the browser does after a network break.

**Instead of:**
- reading the delay from the last `retry:` the line got: a line that never opened has none, so it would need a fallback number, a hidden default
- a growing wait (backing off): more numbers to set (the start, the factor, the top) for a restart that takes seconds; the browser's own reconnect doesn't back off either
- telling the app the line died and leaving the reopen to it: every app would write the same loop, and the docs' promise would stay false
- a separate hook for a reopen: `:on-open` with `again?` already says a break happened, whichever kind
