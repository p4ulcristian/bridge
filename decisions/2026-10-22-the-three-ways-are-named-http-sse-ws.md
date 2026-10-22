# The three ways are named http, sse and ws

> Adds to [Three ways between browser and server, each with one job](2026-10-22-three-ways-between-browser-and-server.md).

**Decision:** in files, code and talk the three ways are **http** (a request and its answer), **sse** (the server stream, Server-Sent Events) and **ws** (a websocket). In web: `server_code/http.clj`, `sse.clj`, `ws.clj` and the same three in `browser_code/`; a route of each kind says `:get`/`:post`, `:sse` or `:ws`.

**Why:** "requests" is loose (all three begin with an HTTP request), and the exact long names are too long to read in every file name. The three short names are the ones the web already uses.

**Instead of:** requests / server stream / websocket: plain words, but the first isn't exact.
