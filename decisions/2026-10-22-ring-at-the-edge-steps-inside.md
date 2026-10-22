# Ring at the edge, steps inside

**Decision:** `ground-web` serves a plain Ring handler, so any Ring middleware works at the edge (the door: sessions' cookie, headers, files). Inside the door, a request's own work runs as a readable vector of named steps over one map, run by `ground-steps`.

**Why:** Ring is what every Clojure web library speaks; keeping it at the edge keeps those libraries usable. Middleware wraps functions in closures, so its order can't be printed or checked; steps are a vector, so the order of a request's work is data to read, check and extend.

**The price:** two ways of putting work around a request, each in its own place.

**Instead of:**
- Ring middleware throughout: plain functions everyone knows, but the stack is a closure: it can't be printed, the start can't check it holds the access check, and an app can't insert a step after a named one
- steps throughout, no Ring: one way, but the Ring libraries stop working
