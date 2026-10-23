# A message sent over http may carry more headers, written out

> **Added to** by parlor's [Every send writes out its options, its once-key among them](https://github.com/p4ulcristian/parlor/blob/tidy-up/decisions/2026-10-23-every-send-writes-out-its-options-its-once-key-among-them.md) (2026-10-23, built on tidy-up, not released): `send-message!`'s options gain a required `:keep-alive?`, fetch's keepalive.

> **Changed in part** by [The family and its composer are groundwork](https://github.com/p4ulcristian/groundwork/blob/Starting-from-the-bottom/decisions/2026-10-23-the-family-and-its-composer-are-groundwork.md) (2026-10-23): manor, the family, and house, the composer, are both named groundwork now.

> Since v0.5.0. Changes in part [Every value that sets behaviour is a setting; no hidden defaults](2026-10-22-every-value-that-sets-behaviour-is-a-setting.md): `send-message!`'s options gain `:headers`.

**Decision:** the browser's `send-message!` takes one more required option, `:headers`: more request headers, by lowercase name, `{}` for none. They go out beside mailroom's own two, `accept` and `content-type`, which an app can't replace: naming either refuses, `:mailroom/bad-options`, and so does a name with a capital letter.

```clojure
(mailroom/send-message! (:http addresses) shapes-by-name {:orders/move {:order/id id}}
                        {:wire wire :headers {"idempotency-key" "4b1c…"}})
```

The first user is house: every write carries a one-time key in the `idempotency-key` header, so a repeat of the same send saves nothing twice. mailroom doesn't know what a header means; the server's steps read it from the request (`:mailroom/request`), as they read the cookie.

**Why:** what travels beside a message and is about the request, not its data (a one-time key, a trace id), is what HTTP headers are for; the server's steps already see the whole request. Lowercase, since Ring hands the server's steps lowercase names: the name written in the browser is the one read on the server.

**The price:** every call writes `:headers`, nearly always `{}`. A header reaches the server over http only; a message on a ws line has no headers of its own.

**Instead of:**
- a reserved name beside the message's names (`{:orders/move {…} :mailroom/once "…"}`): mailroom's message format on both sides would carry another library's idea, and every name would share it
- a `:once-key` option: mailroom would know of run-once, which isn't its idea
- headers as an optional key: a send that adds none and one that forgot to would read the same
