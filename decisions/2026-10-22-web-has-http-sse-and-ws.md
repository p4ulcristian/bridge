# web has http, sse and ws, a file each, and a route says its kind

> **Changed in part** by [The app passes its wire; mailroom never makes one](2026-10-22-the-app-passes-its-wire.md) (2026-10-22, v0.2.0): a line carries the app's wire too, `:mailroom/wire`, which messages sent on it are written by.

> **Changed in part** by [mailroom is its own library](2026-10-22-mailroom-is-its-own-library.md) (2026-10-22): the library is mailroom; the ping's interval is the required setting `:ping-every-ms`, and ws pings too.

> **Changed in part** by [web becomes transport and routes](2026-10-22-transport-and-routes.md) (2026-10-22): the library is groundwork.transport, and the routes are groundwork.routes.

> Builds on [Three ways between browser and server](2026-10-22-three-ways-between-browser-and-server.md) and [The three ways are named http, sse and ws](2026-10-22-the-three-ways-are-named-http-sse-ws.md). Adds to [A route is a Ring handler made from steps](2026-10-22-a-route-is-a-ring-handler-made-from-steps.md).

**Decision:**

- **One file per way, on each side:** `server_code/http.clj`, `sse.clj`, `ws.clj` and `browser_code/http.cljs`, `sse.cljs`, `ws.cljs`; the server itself in `server_code/server.clj`, the routes in `server_code/routes.clj`. The api is a pair: `api.clj` for the server, `api.cljs` for the browser.
- **A route says its kind:** `:get`/`:post`/… with a `:handler` (http), `:sse {:on-open :on-close}` or `:ws {:on-open :on-message :on-close}`. A way is on exactly when a route of its kind exists; there is no switch.
- **A line** (an open sse or ws connection) is a map with its channel and request; the server sends on it with `send-message-on-sse-line!` or `send-message-on-ws-line!`. Messages travel as JSON through wire.
- **sse** answers 200 with `text/event-stream`, never finishes, writes a ping comment every 25 s, and gives the line the browser's `Last-Event-ID`. **ws** answers 400 to a request that isn't a websocket.

**Why:** the folder shows the three ways; the route data shows which an app uses. Each way's server and browser halves have the same name.

**The price:** web now uses wire, for the JSON of messages and of the browser's requests. The browser half isn't tested yet (no browser builds while the libraries are cut). ws has no ping yet.

**Instead of:** a switch per way in the settings: a route already says it, so a switch could only disagree with it.
