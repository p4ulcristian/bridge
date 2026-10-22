# Work is a vector of steps over one map

> **Replaced** by [Steps are a list over one context](2026-10-22-steps-are-a-list-over-one-context.md) (2026-10-22): a plain `reduce` over `{:name :enter}` steps; no effects, phases or driver.

**Decision:** a request, a job or a migrate runs as a vector of steps over one context map. A step is a map with a namespaced `:name` and an `:enter`, `:leave` and/or `:error` function; each takes the context and returns it, and none calls the next step. A step that needs outside work (SQL, an email) puts an effect in the context: the work as data and what to do with its result. A driver does the work: `run` waits for it (the JVM), `run-async` awaits it (ClojureScript). The core that moves from one state to the next is pure.

- An answer in the context stops the way in; the steps that entered leave, in reverse.
- Anything thrown is caught and kept in the context; the entered steps get `:error` in reverse; one that removes the error has handled it.
- The vector is plain data: printed, checked (names, functions, no name twice), and changed with plain functions (insert after a name, replace, remove). A required step can be checked for.

**Why:** a vector can be read: the order of a request's work is printed, not hidden in closures, so a start can refuse a vector without the access check. Steps that ask for work instead of doing it run the same on every runtime, and are tested without a database. It is the Clojure way: one map of data, functions on it.

**The price:** a step can't call outside code directly; it goes through an effect and a handler. A stack trace passes through the driver.

**Instead of:**
- Ring middleware: plain functions everyone knows, but a wrapped stack is a closure (can't be printed or checked), each one calls the next itself (no driver can await), and the order reads inside out
- steps that do their own work: simpler steps, but tied to one runtime and to a database in every test
