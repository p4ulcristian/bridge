# Every answered message is counted on the meter, whichever door it came through

> The user's decision, 2026-10-23, from the tidy-up. Changes in part [Each message and open line is counted on the app's meter](2026-10-23-messages-and-lines-are-counted-on-the-apps-meter.md), which left a message answered off the server's addresses uncounted ("outside a server, in tests and the REPL"). Not built yet.

**Decision:** `answer-message` counts the messages it answers on the app's meter exactly as the server's messages door does (`bridge_messages_total`, `bridge_answer_seconds`, by name and outcome). A `:person` route that turns a call into a message with `answer-as` (an API in another shape, a token's script) is counted the same as the same message sent to `/groundwork/http`. The REPL's "ask as person" passes the app's meter too; a test passes a meter of its own.

**Why:** one meter for every answered message, so dashboards and alerts see a token's traffic and its failures like a browser's.

**The price:** `answer-message` takes the meter as a required option.

**Instead of:** counting only the server's own door: the same message, refused or failed, shows on the meter or not depending on how it arrived.
