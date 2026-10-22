# An error answer is told from data by one function, the same on both sides

> Adds to [Answers are checked, errors stay in the log](2026-10-22-answers-are-checked-and-errors-stay-in-the-log.md) and [A handler says its shapes](2026-10-22-a-handler-says-its-shapes.md). Since v0.4.0.

**Decision:** `(mailroom/is-error-answer? answer)`, on both sides, says whether an answer is one of mailroom's error answers rather than data:

```clojure
{:error "Not found."}                       ; a refusal: a step's, or mailroom's own ("Too many requests.", "Something went wrong.", "No handler named …")
{:errors {:shop/id ["should be an id"]}}    ; params that didn't fit their shape, the server's
{:mailroom/errors ["…"] :data …}            ; an answer that didn't fit its shape, or a name without shapes, the browser's
```

The first two are the whole map, nothing beside them; anything else is data, a map with an `:error` key among others too. It is one definition, `mailroom.answers` (a `.cljc`), which both `mailroom.api` halves hand on. The server's rule for sending a refusal past the `:answer-shape`, `{:error …}` alone, is in the same file (`is-refusal?`) and stays as it was: `{:errors …}` from a step is still checked against the shape.

**Why:** a library on top (parlor, a screen's statuses) has to tell a failure from data, and wrote the three shapes out itself; when mailroom adds or changes one, a copy elsewhere goes wrong unseen. The server builds the first two, the browser the third, so the one definition lives where both sides read it.

**The price:** an app whose data is a map of only `:error` or only `:errors` reads as an error answer. A handler's answer shape that allows such a map is the app's to avoid.

**Instead of:**
- the function in the browser only: the server's tests (`answer-message`) tell the same answers apart
- sending `{:errors …}` from a step past the shape too, so "an error answer" and "sent as it is" were one rule: anything under `:errors` would leave unchecked, which the answer shape exists to stop
