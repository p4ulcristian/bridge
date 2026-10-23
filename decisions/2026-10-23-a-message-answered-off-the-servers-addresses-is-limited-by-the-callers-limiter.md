# A message answered off the server's addresses is limited by the caller's limiter

> **Changed in part** by [Every answered message is counted on the meter, whichever door it came through](2026-10-23-every-answered-message-is-counted-on-the-meter-whichever-door.md) (2026-10-23, built on tidy-up, not released): its Open question is answered, a message `answer-message` answers is counted on the app's meter.

> Built 2026-10-23 (v0.7.6), recorded later. Asked for by groundwork's [A token call is answered as its person, from the app's own route](https://github.com/p4ulcristian/groundwork/blob/main/decisions/2026-10-23-a-token-call-is-answered-as-its-person-from-the-apps-route.md): a token's call, answered by `answer-message` on the app's own route, had no rate limit. Changes in part [Every value that sets behaviour is a setting; no hidden defaults](2026-10-22-every-value-that-sets-behaviour-is-a-setting.md): `answer-message` took only `:no-rate-limits`.

**Decision:** `make-message-limiter options` makes a limiter for `answer-message`, which counts each handler's `:rate-limit` per asker, by bridge's token buckets (`bridge.limits`), as the server's limiter does. Options, every key required, checked first (`:bridge/bad-options`): `:find-rate-limit-key` (fn [request]) telling askers apart, and `:buckets-before-cleanup` (above it the full buckets are dropped). `answer-message`'s second argument is that limiter or `:no-rate-limits`, written out (nothing is counted); anything else is refused, `:bridge/bad-options`.

```clojure
(def limiter (bridge/make-message-limiter {:find-rate-limit-key    find-rate-limit-key   ; the server's, say
                                           :buckets-before-cleanup 10000}))

(bridge/answer-message handlers limiter request {:orders/place {…}} {:wire wire :log log})
;; over the handler's :rate-limit => {:orders/place {:error "Too many requests."}}
```

The limiter's buckets are its own, not the server's: a message counted on it is not counted on the server's limiter, nor the other way round. One asker sending the same message through the server's addresses and through the app's route gets each handler's `:rate-limit` on each.

**Why:** a message answered off the server's addresses goes through the same handlers as a browser's; a handler's `:rate-limit` is one of its checks, and without a limiter it was skipped. The limiter the server uses already counts it; the caller says how askers are told apart, as the server's options do.

**The price:** two limiters, two counts for one asker: the limit is per way in, not per asker.

**Instead of:**
- **the server's limiter, shared**: `make-routes-for-messages` makes it inside and hands it to nobody; a caller with no server (a test) would have none
- **`:no-rate-limits` only**: a token's call would have no limit at all

**Open:** whether a message `answer-message` answers is counted on the app's meter. It takes no meter today, so a token's call is in no metrics ([Each message and open line is counted on the app's meter](2026-10-23-messages-and-lines-are-counted-on-the-apps-meter.md)).
