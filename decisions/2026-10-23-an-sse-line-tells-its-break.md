# An sse line tells its break, once, for an offline banner

> The lean of Iron Rainbow's port (GROUNDWORK-GAPS.md, G11), mechanical: built without a separate ask, 2026-10-23. Adds to [An sse line reopens after an HTTP error too](2026-10-22-sse-reopens-after-an-http-error.md). Built on tidy-up, not released: bridge; the composer's part comes later.

**What was wrong:** Iron Rainbow's offline banner hears the line come and go. bridge's sse line said only when it opened, `:on-open (fn [again?])`; the moment it broke was told to nobody, so a server down while the network is up showed no banner.

**Decision:** `open-sse-line!` takes a required `:on-break`, nil for none:

```clojure
(bridge/open-sse-line! (:sse addresses) shapes-by-name handlers
                       {:wire               wire
                        :on-open            (fn [again?] …)   ; open; again? true: back after a break
                        :on-break           (fn [] …)         ; broken, once per break
                        :reconnect-after-ms 3000})
```

With `:on-open`'s `again?` it gives the line's status: open, broken, back. The composer's start takes `:on-line-change`, an event or nil, and dispatches it with `{:line :open}`, `{:line :broken}` or `{:line :back}`.

**Why:** the server being down is seen even while the browser is online; the break is the one new thing, as `again?` already says the reopen ([a separate hook for reopening was rejected](2026-10-22-sse-reopens-after-an-http-error.md)).

**The price:** every `open-sse-line!` writes out `:on-break`.

**Instead of:** `navigator.onLine` and failed answers: a server down while the network is up shows no banner.

## Settled while building

Choices made while building, not asked:

1. **A break is the first error after an open,** whichever kind: a network break the browser reopens by itself, or an HTTP error bridge reopens after `:reconnect-after-ms`. The failed tries after it are the same break, told once.
2. **A first try that fails is no break:** the line was never open. The app hears nothing until it opens.
3. **A line closed with `close-sse-line!` tells no break,** nor does an error after it.
4. **No `:on-break` for ws:** the composer's line is sse; ws gets one when an app needs it.
