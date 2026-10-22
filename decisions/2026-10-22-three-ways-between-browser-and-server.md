# Three ways between browser and server, each with one job

> Changes in part Groundwork's decisions "Postgres rings the bell" and "The first cut is thirteen libraries": bells reach the browser over a server stream, not a websocket.

**Decision:** the browser and the server talk in three ways:

1. **Requests:** the browser asks or writes, the server answers. All data travels this way. Every app.
2. **A server stream** (Server-Sent Events: one HTTP response per tab that stays open): the server says that something changed, and the browser asks again with a request. It carries bells, never data. Every app with live screens.
3. **A websocket:** both sides talk many times a second, for a feature that needs it (live cursors, "is typing", shared drawing). Groundwork has it ready; an app uses it only for such a feature.

**Why:** bells only go from server to browser, and a server stream is exactly that, while staying HTTP: the same proxies, the same login cookie, reconnecting built into the browser, and catching up after a break through the last bell's id (Last-Event-ID). A websocket is the right tool only when the browser also talks fast, so it is kept for those features instead of carrying the bells.

**The price:** two kinds of open line instead of one when an app also has a websocket feature. Over HTTP/1.1 a browser holds at most six connections per site, and each tab's stream takes one; over HTTP/2 they share one connection. Some proxies hold a stream back unless a header says not to.

**Instead of:**
- bells over a websocket (the decision before): two-way where one way is used, its own protocol, reconnect and catching up to write ourselves
- polling: simplest, but late and many requests
