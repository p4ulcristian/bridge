# Every answered message is counted on the meter, whichever door it came through

> The user's decision, 2026-10-23, from the tidy-up. Changes in part [Each message and open line is counted on the app's meter](2026-10-23-messages-and-lines-are-counted-on-the-apps-meter.md), which left a message answered off the server's addresses uncounted ("outside a server, in tests and the REPL"). Built on tidy-up, not released.

**Decision:** `answer-message` counts the messages it answers on the app's meter exactly as the server's messages door does (`bridge_messages_total`, `bridge_answer_seconds`, by name and outcome). A `:person` route that turns a call into a message with `answer-as` (an API in another shape, a token's script) is counted the same as the same message sent to `/groundwork/http`. The REPL's "ask as person" passes the app's meter too; a test passes a meter of its own.

**Why:** one meter for every answered message, so dashboards and alerts see a token's traffic and its failures like a browser's.

**The price:** `answer-message` takes the meter as a required option.

**Instead of:** counting only the server's own door: the same message, refused or failed, shows on the meter or not depending on how it arrived.

## Settled while building

Choices made while building, not asked:

1. **Under pipe `http`:** a message `answer-message` answers is counted as the same message sent to the http address, `pipe="http"`; no new pipe value. A dashboard filtering on `http` sees a token's call as a browser's.
2. **`answer-message` also takes `:metric-buckets`,** required, the settings' own, and declares bridge's metrics on the meter itself, on each call: the same table of names and buckets as the routes' is no change; another table or other buckets is refused by meter, `:meter/bad-metrics` (a meter takes one table). So a test's meter of its own needs no routes, and an app's routes and its `answer-message` count into the same series.
3. **A call refused before it answers** (options, handlers, limiter, a value `make-meter` didn't make: `:meter/not-a-meter`) counts nothing.
4. **`bridge.metrics/note-nothing` is gone;** `answer-message` counts through the same `make-note-answer` as the http address.

Left for later, outside bridge: every `answer-message` caller adds `:meter` and `:metric-buckets`: doorman's `leaks.clj` and its tests, doorbell's tests, and the composer's `answer_as.clj` and `tools.clj` (the REPL's "ask as person", on the app's meter).

Built since, on tidy-up (2026-10-23): doorman's leak test takes `:meter` and `:metric-buckets` (required) and its tests pass them; doorbell's tests; the composer's `answer-as` and the REPL's `ask-as-person`, on the app's meter.
