# An sse line the browser left is closed by its next ping or send, and its close is run once

> Changes in part [Each message and open line is counted on the app's meter](2026-10-23-messages-and-lines-are-counted-on-the-apps-meter.md): an sse line a browser leaves is counted out of `mailroom_open_lines` too. Since v0.7.1.

**The cause:** http-kit 2.8.1 never calls `:on-close` when a browser leaves an sse line ([http-kit#578](https://github.com/http-kit/http-kit/issues/578)). Its server makes a channel for each connection when it is accepted, and a new one for each request on it; when the socket closes, it tells the connection's first channel, which no request has. The line's own channel stays "open": `open?` is true and `send!` returns true, its write failing quietly inside http-kit. So `:on-sse-close` never ran, `mailroom_open_lines{pipe="sse"}` only went up, and an app that keeps its lines (doorbell's `remove-sse-line!`) kept every line ever opened. ws lines aren't hit: an upgrade puts the request's channel on the connection.

**Decision:**

- **A line knows its socket.** `mailroom.channel/is-channel-open?`: the channel isn't closed by http-kit, and its socket's selection key is valid (a key is valid until its socket closes; http-kit never cancels one otherwise). The key is in a private field of http-kit's `AsyncChannel`, `key`, read by reflection; the field is looked up when mailroom loads, so an http-kit without it fails then, not on a line.
- **The ping finds a line the browser left.** Every `:ping-every-ms` the sse ping first checks the socket; a closed one is closed with http-kit's `close`, which runs the line's `:on-close`. So a line the browser left lives at most one `:ping-every-ms` (25 s in house's settings) after it is left, counted among the open lines and kept by the app until then.
- **So does a send.** `send-message-on-sse-line!` on such a line closes it the same way and returns false; `is-line-open?` says false as soon as the socket is closed.
- **Once each.** Each line has a flag, set when it closes; only the first close counts it out (-1) and runs `:on-sse-close`. A server's close, a ping and a send that find it gone, and http-kit's `:on-close` (once it calls it) may all come; one runs it.
- **http-kit stays 2.8.1.** The fix (#579) is in 2.9.0-beta1 to -beta4, not in a release.

**Why:** an app that keeps its lines leaks one for every tab closed, for as long as the server runs, and the gauge says nothing true about the browsers listening. The ping already runs on every line, every `:ping-every-ms`, so finding the dead line there costs no new thread and no new setting, and it bounds how long a dead line lives by a number the app already sets. It works on 2.8.1 and still holds once http-kit calls `:on-close` itself.

**The price:** a dead line lives up to one `:ping-every-ms`, and a message told to it in that time goes nowhere (as before; a browser that reconnects catches up by Last-Event-ID). mailroom reads a private field of http-kit; an http-kit that renames it stops mailroom loading, and `mailroom.sse-close-test` drops real sockets to catch a change that keeps the field but breaks it.

**Instead of:**
- **http-kit 2.9.0-beta4**, which calls `:on-close`: a pre-release with breaking server changes ("the server now owns wire response framing", malformed websocket handshakes refused) under every app's server; once 2.9.0 is released, the upgrade is its own decision, and this check stays as the bound
- **`send!`'s false alone**, as asked first: on 2.8.1 it is true on a dead line, the failed write never reaches the channel
- **closing the line on any write error**: http-kit catches it inside and tells only the first channel
- **a separate sweep over every line**: a second loop and a setting for what the ping already does on each line
- **the socket's address from the channel's text** (`toString`): text that says nothing reliable once the socket is closed
