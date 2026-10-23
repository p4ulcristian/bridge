# A refusal is a named reason, declared on its handler

> The user's decision, 2026-10-23, from Iron Rainbow's port (GROUNDWORK-GAPS.md: P12, G10). Changes in part [An error answer is told by one function](2026-10-23-an-error-answer-is-told-by-one-function.md) and [Answers are checked, and errors stay in the log](2026-10-22-answers-are-checked-and-errors-stay-in-the-log.md). Not built yet; its sub-decisions are asked one at a time and added below.

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
