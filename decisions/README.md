# bridge decisions

One file per decision: `YYYY-MM-DD-what-we-decided.md`. The title is the decision. Each file says what we decided, why, and what we chose against. A decision changes by a new file; the old one says so at the top (`Replaced by` or `Changed in part by`), and nothing else in it is rewritten.

bridge was named mailroom until 2026-10-23 ([mailroom is renamed bridge](2026-10-23-mailroom-is-renamed-bridge.md)); the decisions before it keep that name.

All but the last eleven were made while mailroom was three libraries inside Groundwork, a Clojure framework (groundwork.server.transport, groundwork.browser.transport and groundwork.server.steps, named groundwork.web and ground-steps before); they name its parts (its settings, its sessions, access and sync, `/groundwork/...` addresses) where they came from. The ones they name that stayed with Groundwork are named by title, in plain text: "Every endpoint declares its answer", "The server puts the browser's settings in the page", "Postgres rings the bell", "The first cut is thirteen libraries". Groundwork now names both the family of libraries this one belongs to and the composer that puts them together; the decisions that stayed with Groundwork are in the composer's `decisions/`.

## In force

- [http-kit serves, reitit routes](2026-10-12-http-kit-and-reitit.md), changed in part by [The server is Jetty 12 through rj9a](2026-10-23-the-server-is-jetty-12-through-rj9a.md)
- [Ring at the edge, steps inside](2026-10-22-ring-at-the-edge-steps-inside.md)
- [Steps are a list over one context](2026-10-22-steps-are-a-list-over-one-context.md), changed in part by [mailroom is its own library](2026-10-22-mailroom-is-its-own-library.md)
- [Three ways between browser and server, each with one job](2026-10-22-three-ways-between-browser-and-server.md)
- [The three ways are named http, sse and ws](2026-10-22-the-three-ways-are-named-http-sse-ws.md)
- [A route is a Ring handler made from steps](2026-10-22-a-route-is-a-ring-handler-made-from-steps.md), added to by [web has http, sse and ws](2026-10-22-web-has-http-sse-and-ws.md), changed in part by [`:not-found` is the app's](2026-10-23-not-found-is-the-apps.md)
- [web has http, sse and ws, a file each, and a route says its kind](2026-10-22-web-has-http-sse-and-ws.md), changed in part by [web becomes transport and routes](2026-10-22-transport-and-routes.md), [mailroom is its own library](2026-10-22-mailroom-is-its-own-library.md) and [The app passes its wire](2026-10-22-the-app-passes-its-wire.md)
- [web becomes transport and routes](2026-10-22-transport-and-routes.md), changed in part by [Named messages over three addresses](2026-10-22-named-messages-over-three-addresses.md), [mailroom is its own library](2026-10-22-mailroom-is-its-own-library.md) and [`:not-found` is the app's](2026-10-23-not-found-is-the-apps.md)
- [Named messages over three addresses](2026-10-22-named-messages-over-three-addresses.md), changed in part by "The server puts the browser's settings in the page" (Groundwork's), [mailroom is its own library](2026-10-22-mailroom-is-its-own-library.md) and [The app passes its log](2026-10-22-the-app-passes-its-log.md)
- [A handler says its shapes](2026-10-22-a-handler-says-its-shapes.md), changed in part by [Every value that sets behaviour is a setting; no hidden defaults](2026-10-22-every-value-that-sets-behaviour-is-a-setting.md)
- [Answers are checked, errors stay in the log, and a handler may run its steps inside something](2026-10-22-answers-are-checked-and-errors-stay-in-the-log.md), changed in part by [An answer may carry notes](2026-10-22-an-answer-may-carry-notes.md), added to by [An error answer is told from data by one function](2026-10-23-an-error-answer-is-told-by-one-function.md)
- [An answer may carry notes, and a handler's run returns its context](2026-10-22-an-answer-may-carry-notes.md), changed in part by [mailroom is its own library](2026-10-22-mailroom-is-its-own-library.md)
- [mailroom is its own library](2026-10-22-mailroom-is-its-own-library.md), changed in part by [The hooks are written out, and the stop wait, the ws reopen delay and the rate-limit cleanup are settings](2026-10-22-hooks-are-written-out-and-three-numbers-are-settings.md) and [Every value that sets behaviour is a setting; no hidden defaults](2026-10-22-every-value-that-sets-behaviour-is-a-setting.md)
- [The hooks are written out, and the stop wait, the ws reopen delay and the rate-limit cleanup are settings](2026-10-22-hooks-are-written-out-and-three-numbers-are-settings.md), changed in part by [Every value that sets behaviour is a setting; no hidden defaults](2026-10-22-every-value-that-sets-behaviour-is-a-setting.md)
- [Every value that sets behaviour is a setting; no hidden defaults](2026-10-22-every-value-that-sets-behaviour-is-a-setting.md), changed in part by [An sse line reopens after an HTTP error too](2026-10-22-sse-reopens-after-an-http-error.md), [The app passes its wire](2026-10-22-the-app-passes-its-wire.md), [The app passes its log](2026-10-22-the-app-passes-its-log.md), [A message sent over http may carry more headers](2026-10-23-a-message-may-carry-more-headers.md), [A page has its query and language](2026-10-23-a-page-has-its-query-and-language-a-move-says-how-to-scroll.md), [Each message and open line is counted on the app's meter](2026-10-23-messages-and-lines-are-counted-on-the-apps-meter.md), [The server is Jetty 12 through rj9a](2026-10-23-the-server-is-jetty-12-through-rj9a.md), [A message answered off the server's addresses is limited by the caller's limiter](2026-10-23-a-message-answered-off-the-servers-addresses-is-limited-by-the-callers-limiter.md) and [`:not-found` is the app's](2026-10-23-not-found-is-the-apps.md)
- [An sse line reopens after an HTTP error too, after the reconnect setting the browser is handed](2026-10-22-sse-reopens-after-an-http-error.md)
- [The app passes its wire; mailroom never makes one](2026-10-22-the-app-passes-its-wire.md)
- [The app passes its log; each message gets one that knows its request](2026-10-22-the-app-passes-its-log.md), changed in part by [Each message and open line is counted on the app's meter](2026-10-23-messages-and-lines-are-counted-on-the-apps-meter.md) and [The server is Jetty 12 through rj9a](2026-10-23-the-server-is-jetty-12-through-rj9a.md)
- [An error answer is told from data by one function, the same on both sides](2026-10-23-an-error-answer-is-told-by-one-function.md), added to by [The browser passes the server's error answers past the answer shape](2026-10-23-the-browser-passes-the-servers-error-answers-past-the-shape.md)
- [A message sent over http may carry more headers, written out](2026-10-23-a-message-may-carry-more-headers.md)
- [A page has its query, #fragment and language; a move says how to scroll; leaving another page is asked first](2026-10-23-a-page-has-its-query-and-language-a-move-says-how-to-scroll.md)
- [Each message and open line is counted on the app's meter](2026-10-23-messages-and-lines-are-counted-on-the-apps-meter.md), changed in part by [An sse line the browser left is closed by its next ping or send](2026-10-23-an-sse-line-the-browser-left-is-closed-by-the-next-ping.md)
- [An sse line the browser left is closed by its next ping or send, and its close is run once](2026-10-23-an-sse-line-the-browser-left-is-closed-by-the-next-ping.md), changed in part by [The server is Jetty 12 through rj9a](2026-10-23-the-server-is-jetty-12-through-rj9a.md)
- [A plain route is rate-limited by the same buckets, through the api](2026-10-23-a-plain-route-is-rate-limited-by-the-same-buckets.md)
- [mailroom is renamed bridge](2026-10-23-mailroom-is-renamed-bridge.md)
- [The server is Jetty 12 through rj9a](2026-10-23-the-server-is-jetty-12-through-rj9a.md)
- [A message answered off the server's addresses is limited by the caller's limiter](2026-10-23-a-message-answered-off-the-servers-addresses-is-limited-by-the-callers-limiter.md) (built 2026-10-23, v0.7.6, recorded later)
- [`:not-found` is the app's](2026-10-23-not-found-is-the-apps.md) (built 2026-10-23, v0.7.7, recorded later)
- [A message may come with no request](2026-10-23-a-message-may-come-with-no-request.md) (built on tidy-up, not released)
- [The browser passes the server's error answers past the answer shape](2026-10-23-the-browser-passes-the-servers-error-answers-past-the-shape.md) (built on tidy-up, not released)

## Replaced

- [Work is a vector of steps over one map](2026-10-22-work-is-a-vector-of-steps-over-one-map.md), replaced by [Steps are a list over one context](2026-10-22-steps-are-a-list-over-one-context.md)
