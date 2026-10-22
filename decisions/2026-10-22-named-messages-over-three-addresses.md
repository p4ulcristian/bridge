# Named messages over three addresses Groundwork owns

> **Changed in part** by [mailroom is its own library](2026-10-22-mailroom-is-its-own-library.md) (2026-10-22): the three addresses are a required setting, `:addresses`, with no default; they are the app's to write, not Groundwork's.

> **Changed in part** by Groundwork's decision "The server puts the browser's settings in the page" (2026-10-22): the addresses and the pages are said once, on the server, and reach the browser in the page; the `contract.cljc` copies are gone.

> Changes in part [web becomes transport and routes](2026-10-22-transport-and-routes.md): data never travels to an address an app writes; only pages have those.

**Decision:**

- **Every message is a map of names to data:** `{:orders/of-shop {:shop/id 7}}`, the name a namespaced keyword. One message may carry several names; the answer is keyed by the same names.
- **One handler table on the server, by name:** `{:orders/of-shop {:steps [find-orders-of-shop add-orders-answer]}}`. A message arriving over http or over ws finds the same handler. Each name runs apart: one failing answers `{:error …}` under its name and the others still answer.
- **One handler table in the browser,** by name, for what the server sends over sse or ws: `{:bells/changed on-bell}`.
- **Three addresses, Groundwork's, under one prefix:** `/groundwork/http` (a POST of a message, answered at once), `/groundwork/sse` (the stream), `/groundwork/ws` (the websocket). They are a setting with these as the default; an app never writes an address for data.
- **Only pages have addresses an app writes**, kept by groundwork.routes, by name too: `{:orders/page {:address "/orders/:order-id" :title "Order"}}`.

**Why:** one shape for every way of talking, and every handler reached by a name, as parquery's asks already were. Three addresses instead of one keep a proxy rule per pipe and moving one pipe to other servers possible, at no cost to an app, which only uses names.

**The price:** a message's name is inside its body, so a proxy sees only which pipe, not which handler; our own logs carry the names.

**Instead of:**
- one address for all three, told apart by method and headers: tidier, but no rule per pipe at a proxy or load balancer
- each ask, stream and socket at an address the app writes: names and addresses said twice, and our own route kinds to translate
