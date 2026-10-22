# The hooks are written out, and the stop wait, the ws reopen delay and the rate-limit cleanup are settings

> Changes in part [mailroom is its own library](2026-10-22-mailroom-is-its-own-library.md): the four hooks are no longer optional, and three of the numbers it kept as constants are settings. Since v0.1.1.

**Decision:**

- **The four hooks are required options.** `make-routes-for-messages` refuses options without `:on-sse-open`, `:on-sse-close`, `:on-ws-open` and `:on-ws-close`; each is `(fn [line])`, or nil written out when nothing runs then (`[:maybe fn?]`). A missing one refuses with `:mailroom/bad-options`, named, with every other problem at once.
- **Three fixed numbers are settings**, in `settings-shape`, every key required like the rest:

  ```clojure
  [:map {:closed true}
   [:ip :string]
   [:port :int]
   [:client-address-header [:maybe :string]]
   [:addresses [:map {:closed true} [:http :string] [:sse :string] [:ws :string]]]
   [:ping-every-ms [:int {:min 1}]]
   [:stop-wait-ms [:int {:min 0}]]
   [:ws-reopen-after-ms [:int {:min 1}]]
   [:rate-limit-buckets-before-cleanup [:int {:min 1}]]]
  ```

  - `:stop-wait-ms`: how long `stop-server!` lets running requests finish before it cuts them (10 s before). `start-server!` keeps it in the server it returns, so `stop-server!` still takes only the server.
  - `:ws-reopen-after-ms`: how long the browser waits before it reopens a broken ws line (1 s before). The server doesn't read it; the app hands it to the browser with the addresses, and the browser's `open-ws-line!` takes it as a required option, `{:reopen-after-ms …}`, checked before any socket opens (`:mailroom/bad-options`).
  - `:rate-limit-buckets-before-cleanup`: rate limits keep a bucket per message name and asker in memory; above this many, each message first drops the buckets that have refilled full (10,000 before).

**Why:** a hook left out ran nothing, silently: the app's code didn't say whether it was forgotten or chosen. Written out as nil, it says so. The three numbers are behaviour an app can feel: a slow report needs a longer stop wait, or a deploy cuts it; how soon tabs reconnect after a deploy decides how hard a restarting server is hit and how long users wait; the cleanup threshold is memory against work per message on a busy server. As constants they were invisible in the app's settings, which "No defaults" in [mailroom is its own library](2026-10-22-mailroom-is-its-own-library.md) exists to prevent.

**The price:** every app writes four hooks and three numbers more, nearly always the same (nil, 10000, 1000, 10000). A browser that opens a ws line must be handed the delay; it has no value of its own.

**Instead of:**
- optional hooks: shorter, but a forgotten hook and a chosen "nothing" read the same
- the numbers as constants: the values in effect can't be read in the app's settings, and no app can change them
- defaults the app may override: the same invisibility, as before
- the ws delay as a browser-only option, not a setting: the server would not say it, and the page could not carry it with the addresses
