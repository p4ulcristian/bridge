# mailroom decisions

One file per decision: `YYYY-MM-DD-what-we-decided.md`. The title is the decision. Each file says what we decided, why, and what we chose against. A decision changes by a new file; the old one says so at the top (`Replaced by` or `Changed in part by`), and nothing else in it is rewritten.

All but the last were made while mailroom was three libraries inside Groundwork, a Clojure framework (groundwork.server.transport, groundwork.browser.transport and groundwork.server.steps, named groundwork.web and ground-steps before); they name its parts (its settings, its sessions, access and sync, `/groundwork/...` addresses) where they came from. The ones they name that stayed with Groundwork are named by title, in plain text: "Every endpoint declares its answer", "The server puts the browser's settings in the page", "Postgres rings the bell", "The first cut is thirteen libraries".

## In force

- [http-kit serves, reitit routes](2026-10-12-http-kit-and-reitit.md)
- [Ring at the edge, steps inside](2026-10-22-ring-at-the-edge-steps-inside.md)
- [Steps are a list over one context](2026-10-22-steps-are-a-list-over-one-context.md), changed in part by [mailroom is its own library](2026-10-22-mailroom-is-its-own-library.md)
- [Three ways between browser and server, each with one job](2026-10-22-three-ways-between-browser-and-server.md)
- [The three ways are named http, sse and ws](2026-10-22-the-three-ways-are-named-http-sse-ws.md)
- [A route is a Ring handler made from steps](2026-10-22-a-route-is-a-ring-handler-made-from-steps.md), added to by [web has http, sse and ws](2026-10-22-web-has-http-sse-and-ws.md)
- [web has http, sse and ws, a file each, and a route says its kind](2026-10-22-web-has-http-sse-and-ws.md), changed in part by [web becomes transport and routes](2026-10-22-transport-and-routes.md) and [mailroom is its own library](2026-10-22-mailroom-is-its-own-library.md)
- [web becomes transport and routes](2026-10-22-transport-and-routes.md), changed in part by [Named messages over three addresses](2026-10-22-named-messages-over-three-addresses.md) and [mailroom is its own library](2026-10-22-mailroom-is-its-own-library.md)
- [Named messages over three addresses](2026-10-22-named-messages-over-three-addresses.md), changed in part by "The server puts the browser's settings in the page" (Groundwork's) and [mailroom is its own library](2026-10-22-mailroom-is-its-own-library.md)
- [A handler says its shapes](2026-10-22-a-handler-says-its-shapes.md)
- [Answers are checked, errors stay in the log, and a handler may run its steps inside something](2026-10-22-answers-are-checked-and-errors-stay-in-the-log.md), changed in part by [An answer may carry notes](2026-10-22-an-answer-may-carry-notes.md)
- [An answer may carry notes, and a handler's run returns its context](2026-10-22-an-answer-may-carry-notes.md), changed in part by [mailroom is its own library](2026-10-22-mailroom-is-its-own-library.md)
- [mailroom is its own library](2026-10-22-mailroom-is-its-own-library.md)

## Replaced

- [Work is a vector of steps over one map](2026-10-22-work-is-a-vector-of-steps-over-one-map.md), replaced by [Steps are a list over one context](2026-10-22-steps-are-a-list-over-one-context.md)
