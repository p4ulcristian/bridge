# http-kit serves, reitit routes

> **Changed in part** by [The server is Jetty 12 through rj9a](2026-10-23-the-server-is-jetty-12-through-rj9a.md) (2026-10-23, v0.7.5): the server is Jetty 12 through rj9a, every request still on a virtual thread; reitit stays.

**Decision:** the web server is http-kit, with every request on a virtual thread. Routes are reitit data: the app's pages next to Groundwork's own endpoints.

**Why:** http-kit is small and fast and has websockets built in, which live updates need. reitit keeps routes as plain data, is widely used and actively maintained.

**Instead of:** Jetty 12 (websockets need an extra adapter), our own router.
