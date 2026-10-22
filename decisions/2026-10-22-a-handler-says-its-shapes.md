# A handler says its shapes

> Adds to [Named messages over three addresses Groundwork owns](2026-10-22-named-messages-over-three-addresses.md).

**Decision:** a handler may say a `:params-shape` and an `:answer-shape`, the same two a background job kind has:

```clojure
{:shops/total {:params-shape [:map [:shop/id :uuid]]
               :answer-shape [:map [:shop/id :uuid] [:total :decimal]]
               :steps        [find-shop-total add-total-answer]}}
```

Coming in, transport turns a name's JSON data into shaped data by `:params-shape` (real UUIDs, decimals, dates), or answers `{:errors …}` by key before any step runs. Going out, the answer becomes JSON data by `:answer-shape`. The browser takes the same shapes by name (from a `.cljc` file both sides read) to send params and to read answers and server messages back into real types. A name without shapes passes its JSON data as it is.

**Why:** JSON has no UUIDs, decimals or dates, so without a shape a handler got `"120.50"` where it meant `120.50M`. The shape is said once, next to the handler, and both ends use it.

**The price:** a handler without shapes still gets JSON data; nothing forces a shape yet. Messages the server sends on its own over sse or ws go out as given (only answers use `:answer-shape`). The browser half isn't tested yet.

**Instead of:** shapes in the browser-queries library: but http and ws share the one handler table, which is transport's.
