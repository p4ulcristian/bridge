# mailroom is renamed bridge

**Decision:** the library is named bridge: the repository is github.com/p4ulcristian/bridge (the old address forwards), the namespaces are `bridge.*` on both sides (`bridge.api` the public one), and every name it gives is renamed with it:

- the context's and the line's keys: `:bridge/log`, `:bridge/wire`, `:bridge/request`, `:bridge/message-name`, `:bridge/message-data`, `:bridge/answer`, `:bridge/notes`, `:bridge/channel`;
- the ws ping, `{:bridge/ping {}}`; the browser's `:bridge/bad-params` and `:bridge/errors`;
- the errors: `:bridge/bad-settings`, `:bridge/bad-options`, `:bridge/bad-handlers`, `:bridge/bad-pages`, `:bridge/bad-shapes`;
- the log events: `:bridge/handler-failed`, `:bridge/server-error`;
- the metrics: `bridge_messages_total`, `bridge_answer_seconds`, `bridge_open_lines`.

The decisions written before keep their text and their file names, mailroom in them; this one says the name changed.

**Why:** next to postbox, the app's email, mailroom sounded like email too. A bridge is the one way across between the browser and the server, with a half on each side, which is what this library is: named messages cross it over http, sse and ws, and it holds both ends.

**The price:** every app and library that uses it changes its requires, its keywords and its metric names at once; a dashboard or alert reading `mailroom_*` reads `bridge_*`. The old tags stay on the renamed repository; a deps.edn naming `io.github.p4ulcristian/mailroom` still resolves through GitHub's forward until it moves.

**Instead of:**
- **switchboard**: one centre that connects others, not a way between two sides
- **relay**: passes on what it gets; bridge answers too, with the handlers' steps
- **exchange**: a trading floor or a key exchange first
- **courier**: one carrier on one trip, not the standing way both ways
- **keeping mailroom**: the email reading stays next to postbox
