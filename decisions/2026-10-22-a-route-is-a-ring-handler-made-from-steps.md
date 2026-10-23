# A route is a Ring handler made from steps

> **Changed in part** by [`:not-found` is the app's](2026-10-23-not-found-is-the-apps.md) (2026-10-23, v0.7.7): when no route matches, `make-ring-handler-from-routes` answers by the app's `:not-found`, required; nil is the empty 404.

> **Added to** by [web has http, sse and ws](2026-10-22-web-has-http-sse-and-ws.md) (2026-10-22): routes of kind :sse and :ws, and two more functions to send on a line.

> Builds on [Ring at the edge, steps inside](2026-10-22-ring-at-the-edge-steps-inside.md) and [http-kit serves, reitit routes](2026-10-12-http-kit-and-reitit.md).

**Decision:** groundwork.web has four functions. `start-server!` runs http-kit on one Ring handler, every request on its own virtual thread; `stop-server!` stops it. `make-ring-handler-from-routes` makes that handler from reitit routes (404 when no route matches, 405 when the method doesn't). `make-ring-handler-from-steps` makes a route's handler from a vector of steps: the context starts as `{:groundwork.web/request request}`, and the answer a step adds is the Ring response; no answer is a 500.

**Why:** each of the four is one plain step: a server, a router, a handler. Routes stay reitit data and a handler stays Ring, so any Ring middleware and any reitit feature work. The steps don't know about HTTP beyond the request they read and the response they answer.

**The price:** a step writes the whole response map itself (status, headers, JSON text). Headers, files, the page shell, body limits and settings checks are not in yet.

**Instead of:**
- routes that hold `:steps` directly, turned into handlers by walking the route data: shorter routes, but one more hidden step to understand
- answers that are data and web turning them into JSON: shorter steps, but web would decide the response format before we need it
