# The browser passes the server's error answers past the answer shape

> Built on tidy-up, not released. Adds to [An error answer is told from data by one function, the same on both sides](2026-10-23-an-error-answer-is-told-by-one-function.md). Found by groundwork's end-to-end test once a handler answered "Not found." for a shop the asker isn't in (doorman's "a handler says where it acts").

**Decision:** an answer the server made instead of data, a refusal (`{:error "Not found."}`) or params that didn't fit (`{:errors …}`), reaches the browser's caller as it is: `turn-answer-into-shaped-data` doesn't hold it to the handler's `:answer-shape`, as the server doesn't. Only data is shaped; data that doesn't fit still arrives with `:bridge/errors`.

**Why:** the server sends these past the shape on purpose; checked against a `[:vector …]` in the browser, "Not found." became `{:bridge/errors ["invalid type"] :data {:error "Not found."}}`, and a screen showing `(:error error)` showed nothing.

**Instead of:** every answer shape allowing `[:or shape refusal]`: each app writing bridge's own answers into its shapes
