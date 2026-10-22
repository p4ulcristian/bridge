# An answer may carry notes, and a handler's run returns its context

> **Changed in part** by [mailroom is its own library](2026-10-22-mailroom-is-its-own-library.md) (2026-10-22): the notes are at `:mailroom/notes`, in the context and beside the answers; `default-addresses` is gone, the addresses are a required setting.

> Changes in part [Answers are checked, errors stay in the log, and a handler may run its steps inside something](2026-10-22-answers-are-checked-and-errors-stay-in-the-log.md): `:run-steps-in` returns the context, not the answer.

**Decision:**

- `(run-steps-in run context)` returns the context `run` returned, or one with its own answer added (a refusal). Transport reads the answer from it.
- What the ending context holds at `:groundwork.server.transport/notes` goes out beside the answers, under one more name: `{:orders/all […] :groundwork/notes {:orders/all {:tables ["orders"]}}}`. groundwork.server.sync puts the tables there; the browser's question store reads them.
- The server's `default-addresses` and the page routes' checks live on the server only; the browser gets both from the page (Groundwork's decision "The server puts the browser's settings in the page").
- `make-ring-handler-from-routes` may answer a GET no route matches from a folder on the classpath (`:files-folder`): the built JS and styles.

**Why:** steps work on one context; a wrapper that returns the context lets several (access, sync) stack without knowing each other. Notes keep the answer exactly what the handler's shape says.

**Instead of:** notes inside the answer (it would no longer fit its shape); a header per name (http only; sse and ws have none).
