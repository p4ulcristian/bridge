# http-kit serves, reitit routes

**Decision:** the web server is http-kit, with every request on a virtual thread. Routes are reitit data: the app's pages next to Groundwork's own endpoints.

**Why:** http-kit is small and fast and has websockets built in, which live updates need. reitit keeps routes as plain data, is widely used and actively maintained.

**Instead of:** Jetty 12 (websockets need an extra adapter), our own router.
