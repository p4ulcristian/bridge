# A plain route is rate-limited by the same buckets, through the api

> Since v0.7.4. Adds to [The hooks are written out, and the stop wait, the ws reopen delay and the rate-limit cleanup are settings](2026-10-22-hooks-are-written-out-and-three-numbers-are-settings.md): the limiter counted only messages. Asked for by groundwork's visitor route (a POST that sets a cookie, outside the messages' route), which had no limit.

**Decision:** `make-rate-limited-ring-handler ring-handler options` puts a Ring handler for a plain route behind a limiter of its own, mailroom's token buckets (`mailroom.limits`, as a handler's `:rate-limit`): one bucket per asker, holding `:max`, refilled at `:max` per `:per-ms`. An asker over it is answered `429` `"Too many requests."` (text/plain, no-store) and the handler isn't called. Options, every key required: `:rate-limit {:max :per-ms}`, `:find-rate-limit-key` (fn [request]), `:buckets-before-cleanup` (above it the full buckets are dropped). Kept in the server's memory, like the messages'.

**Why:** the limiter already does exactly this for messages, by an asker the app tells apart; a route that isn't a message (it sets a cookie, so it can't be one) needs the same, and a second limiter in each app would be the same code twice.

**Instead of:**
- a small limiter in groundwork: a copy of mailroom's
- making the visitor route a message: a message's answer sets no cookie
- a limit at the proxy only: the app's settings wouldn't say it, and development would have none
