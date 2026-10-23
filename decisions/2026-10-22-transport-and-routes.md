# web becomes transport and routes

> **Changed in part** by [`:not-found` is the app's](2026-10-23-not-found-is-the-apps.md) (2026-10-23, v0.7.7): any other address is answered by the app's `:not-found`, required; nil is the empty 404.

> **Changed in part** by [mailroom is its own library](2026-10-22-mailroom-is-its-own-library.md) (2026-10-22): transport, with its routes and steps, is the library mailroom (`mailroom.api`).

> **Changed in part** by [Named messages over three addresses](2026-10-22-named-messages-over-three-addresses.md) (2026-10-22): data travels as named messages to three addresses Groundwork owns; routes keeps only pages.

> Changes in part [web has http, sse and ws](2026-10-22-web-has-http-sse-and-ws.md) and Groundwork's decision "The first cut is thirteen libraries": groundwork.web is split in two, and the planned browser routing becomes the browser half of groundwork.routes.

**Decision:**

- **groundwork.transport** is how the browser and the server talk: the server (http-kit, one Ring handler) and http, sse and ws, each a file on both sides. It knows no addresses.
- **groundwork.routes** is which address goes where, on both sides, with reitit: on the server an address leads to its work (`:get`/`:post` with a handler, `:sse`, `:ws`) or to the page; in the browser (reitit-frontend) the address in the bar leads to a page, with Back and Forward and the page's title. The app's pages are one list both sides read (`shared_code/pages.cljc`); `make-server-routes-for-pages` answers each page opened fresh with the app's page handler, and any other address is a 404.
- routes uses transport; transport knows nothing of routes.

**Why:** each library is one idea, and each folder reads like it. Pages exist on both sides (an address in the bar can always be opened fresh), so their routes live together; "web" said nothing about what the library is.

**The price:** two libraries where there was one. The browser half of routes doesn't yet scroll, ask a page's data on arrival or guard unsaved changes (decided 2026-10-14); it, and transport's browser half, aren't tested yet (no browser builds while the libraries are cut).

**Instead of:**
- routes inside web, on both sides: one library with two ideas
- `communication` or `server-client-communication` for the name: loose, or long
