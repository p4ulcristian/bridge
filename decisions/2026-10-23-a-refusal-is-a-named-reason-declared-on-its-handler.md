# A refusal is a named reason, declared on its handler

> The user's decision, 2026-10-23, from Iron Rainbow's port (GROUNDWORK-GAPS.md: P12, G10). Changes in part [An error answer is told by one function](2026-10-23-an-error-answer-is-told-by-one-function.md) and [Answers are checked, and errors stay in the log](2026-10-22-answers-are-checked-and-errors-stay-in-the-log.md). Built on tidy-up, not released: bridge, doorman, parlor and dictionary; the composer's part comes later.

**What was wrong:** every refusal was `{:error "text"}` with a fixed English text. Signed out, a dead token and another shop's order all answered "Not found.", so the browser couldn't send a signed-out person to the login, say when to try again, or show a request id.

**Decision:** a refusal is a named reason with typed data, and the handler declares the reasons it can give, as it declares its answer:

```clojure
{:orders/ship {:action       :orders/edit
               :record       [:orders :order/id]
               :params-shape [:map [:order/id :uuid]]
               :answer-shape [:map [:order/id :uuid]]
               :refusals     {:order/already-shipped [:map [:order/shipped-at :time/instant]]
                              :order/no-address      nil}                 ; nil: no data
               …}}

;; a step refuses:
(bridge/refuse context :order/already-shipped {:order/shipped-at shipped-at})

;; on the wire, over http, sse and ws alike:
{:orders/ship {:bridge/refused :order/already-shipped :bridge/data {:order/shipped-at "…"}}}
```

- **The libraries' own reasons** belong to the library whose step makes them: bridge's `:bridge/too-many` (with `:retry-after-ms`), `:bridge/failed` (with the `:request-id`), `:bridge/invalid` (params that don't fit); doorman's `:doorman/signed-out`, `:doorman/not-found`, `:doorman/not-allowed`.
- **A reason nobody declared is a bug:** logged, and answered `:bridge/failed`. An unexpected failure stays apart from a refusal.
- **No English on the wire:** the reason is the dictionary key and its data are the text's arguments: `(parlor/tr :order/already-shipped data)`.
- **No HTTP status per message:** one POST carries many messages, and sse and ws have no status, so the reason travels in the answer.

**Why:** each reason is said once, by the handler that gives it or the library that makes it, and its data is checked like an answer. The browser tells reasons apart by name, not by reading English.

**The price:** every handler writes `:refusals` (`{}` for none); an `:answer-shape` may not name `:bridge/refused`; bridge, doorman, parlor and the composer change together.

**Instead of:**
- **a status on every answer** (`{:answered :ok :data …}`): also correct; every answer carries an envelope the browser's store already adds around it
- **all reasons in one app table** (Iron Rainbow's `response_reasons.clj`): a handler no longer shows what it can refuse, and the reason is said in a second place

## Sub-decisions

1. **doorman adds its own reasons** (`:doorman/signed-out`, `:doorman/not-found`, `:doorman/not-allowed`) to the `:refusals` of every handler it wraps; the app doesn't write them. doorman is the one that refuses, and one handler where the app forgot them would turn a real "signed out" into `:bridge/failed`. (The user's yes, 2026-10-23.)
2. **Params that don't fit are a refusal too:** `{:bridge/refused :bridge/invalid :bridge/data {:errors {…}}}` replaces today's bare `{:errors {…}}`, so every refusal has one shape and the browser checks one key. (The user's yes, 2026-10-23.)
3. **The request id is on every refusal and every failure,** `{:bridge/refused … :bridge/data … :bridge/request-id "…"}`, and on no normal answer, whose data stays bare. Support finds the log line for a "Not allowed." as well as for a crash. (The user's yes, 2026-10-23.)
4. **A crash is not an HTTP 500 for the whole request:** one POST carries several messages, so it stays 200; the crashed message answers `:bridge/failed` (with its request id), the meter counts it as `:failed` (alerts read the meter), and the log has it. A 500 would throw away the other messages' good answers. (The user's yes, 2026-10-23.)
5. **Every reason's text lives in the app's catalog,** bridge's and doorman's too: a library can't know the app's languages. The start is refused when any declared reason, a handler's or a library's, has no text in every language the app speaks. (The user's yes, 2026-10-23.)
6. **Back to the page after sign-in:** parlor's required `:on-signed-out` (an event) is dispatched once, with the page the person was on: `[:app/to-login {:page :flex/orders :params {…}}]`, so after signing in they land where they were. (The user's yes, 2026-10-23.)

## Settled while building

Choices made while building, not asked:

1. **`:bridge/failed` carries no data**; its request id is the refusal's `:bridge/request-id`, as on every refusal (sub-decision 3), not said a second time in `:bridge/data`.
2. **A reason of nil shape carries nil:** `:bridge/data` is always on the wire, `null` for such a reason; data for it is a bug.
3. **Every bug is `:bridge/failed`,** each with its own log event, `:error`: a step that threw (`:bridge/handler-failed`, outcome `:failed`), no step answered (`:bridge/no-step-answered`, `:failed`, a refusal before), an answer that doesn't fit its shape (`:bridge/answer-unfit`, `:unfit`), a refusal's data that doesn't fit its reason's (`:bridge/refusal-unfit`, `:unfit`), a reason the handler doesn't declare (`:bridge/undeclared-refusal`, `:failed`); a name no handler has, `:warn` `:bridge/no-handler`, outcome `:no-handler` (another version of the app, or a scanner).
4. **Every refusal is in the log,** `:info` `:bridge/refused {:bridge/message-name :bridge/refused}`, on the message's log, so the request id finds a line for a "Not allowed." too.
5. **A step refuses only with its handler's reasons;** bridge's own are made by bridge alone (a step refusing `:bridge/…` is an undeclared reason). A handler's `:refusals` may not name `:bridge/…` reasons, and every reason is a namespaced keyword: refused at the start, both sides.
6. **"An `:answer-shape` naming `:bridge/refused`"** is found anywhere in the shape's form, on both sides.
7. **`:retry-after-ms`** is the time until the asker's bucket holds one message again, at least 1 ms.
8. **The browser's own reasons are two:** `:bridge/no-answer {:http-status n}`, under each name of the message, for any response that isn't 200 with a JSON object and for no response at all (`:http-status nil`), so `send-message!` never fails for want of an answer; and `:bridge/unfit {:errors …}`, which replaces `{:bridge/errors … :data …}` (an answer that doesn't fit, a name without shapes, a reason the name doesn't declare, a refusal's data that doesn't fit). The data as it came is dropped. A refusal the browser makes carries `:bridge/request-id` only when the server sent one.
9. **`refusals`, on both sides, lists bridge's five,** for the catalog check; the browser takes them for every name.
10. **A refusal's data is shaped as an answer is,** by wire, every map closed; the reason travels as text and comes back a keyword.
11. **doorman: signed out means refused while no person asks** (nobody, a visitor, a wrong, ended or revoked token, which reads as nobody). A dead token on a handler open to anyone still runs as nobody: it isn't refused for the token alone.
12. **doorman: a request from another Origin, or with a cookie and a token at once, is `:doorman/not-allowed`;** its why goes to the log, `:info` `:doorman/request-refused {:because …}`, and `run-as-asker-of-request!`'s exception carries the `:reason`.
13. **doorman: a handler declaring one of doorman's reasons is refused** (doorman adds them). `make-my-actions-handler`'s `:refusals` are doorman's three.
14. **doorman's browser half adds them too:** `put-refusals-into-shapes`, over the app's shapes by name, refusing a name that declares one itself.
15. **doorman's leak test also takes `:doorman/signed-out`** as not reaching the record: a signed-out asker gets it for every record, so a visitor's leak test could otherwise never pass. A signed-in asker never gets it.
16. **doorman: `explain-refusal`'s `:answer`** is `:allowed` or the check's reason, `:doorman/signed-out` included; `:no-such-handler` stays.
17. **parlor: `:signed-out-reason`** beside `:on-signed-out` (`:doorman/signed-out` in groundwork; parlor doesn't depend on doorman), both nil or both set, checked at the start.
18. **parlor: once per `:parlor/start`;** after signing in the page loads anew, or the app starts parlor again.
19. **parlor: the page given is `{:page name :params path-params :query query}`,** the query added so the person lands on the same view; `{:page nil :params {} :query {}}` on an address no page has. A watched question counts while it is held, as for `:on-answer-failed`.
20. **parlor: a promise `:send-write!` fails is passed on,** not retried and not made an answer: bridge's `send-message!` fails none for want of an answer, so it is a bug. parlor's own `{:error "No answer from the server."}` is gone.
21. **dictionary: `check-texts-of-reasons texts reasons`,** reasons as `{reason data-shape}`; each text must be in every loaded catalog itself (the fallback doesn't count); it also checks that a text names only arguments its reason's data carries (nil: none; a `[:map …]`; other shapes unchecked) and that ICU reads it.
22. **dictionary: an args key names its ICU argument with each `-` read as `_`,** on both sides: ICU's argument names take no `-`, so `{retry-after-ms}` can't be written; `{:retry-after-ms 1500}` fills `{retry_after_ms}`. A key without `-` is named as before.
23. **The libraries pin each other at their tidy-up commits** by full `:git/sha`: doorman and parlor bridge's, parlor dictionary's.

Left for later: the composer (its `run_once.clj` texts as reasons, `:refusals` on its handlers, `:on-signed-out`, `check-texts-of-reasons` at the start, `put-refusals-into-shapes`, the repins); and doorbell, whose store still answers a rejected send `{:error "No answer from the server."}`, now data to parlor: with bridge's `send-message!` it comes only when the app's send function itself fails.

Built since, on tidy-up (2026-10-23): the composer's part, in groundwork's [The composer takes named refusals](https://github.com/p4ulcristian/groundwork/blob/tidy-up/decisions/2026-10-23-the-composer-takes-named-refusals.md); doorbell's store answers a failed send `:bridge/no-answer {:http-status nil}`, in doorbell's [An ask with no answer is bridge's :bridge/no-answer refusal](https://github.com/p4ulcristian/doorbell/blob/tidy-up/decisions/2026-10-23-an-ask-with-no-answer-is-bridges-no-answer-refusal.md).
