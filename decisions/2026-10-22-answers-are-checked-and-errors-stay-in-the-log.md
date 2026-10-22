# Answers are checked, errors stay in the log, and a handler may run its steps inside something

> Builds on Groundwork's decision "Every endpoint declares its answer" and [A handler says its shapes](2026-10-22-a-handler-says-its-shapes.md).

**Decision:**

- An answer is checked against its `:answer-shape` with every map closed (wire's `turn-shape-into-closed-shape`): a key the shape doesn't name, such as an extra column or a password hash, means the answer isn't sent; the name answers "The answer doesn't fit its shape." instead.
- A handler that throws answers "Something went wrong."; the details go to the log, never to the browser.
- A handler may have `:run-steps-in (fn [run context])`: transport hands it the running of the steps, so it can run them inside what it sets up. groundwork.server.access uses it for the session, the transaction and who is asking.

**Why:** the answer shape is what stops data that shouldn't leave the server; a database error's text tells an attacker about the tables. Running steps inside a transaction can't be a step itself, since a step can't wrap the ones after it.

**The price:** a `:run-steps-in` is a function around the steps, not data; there is one per handler, made by Groundwork, not by the app.

**Instead of:** steps with an around phase (dropped with the effects version of steps): more to understand for one use.
