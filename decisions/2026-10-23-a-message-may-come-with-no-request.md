# A message may come with no request

> Built on tidy-up, not released. Asked for by groundwork's REPL tool "ask as this person", which had to fake a browser's request (`{:request-method :post :uri "/" :headers {"origin" …}}`) to get past an Origin check its stand-in for doorman's session step never made.

**Decision:** `answer-message`'s request may be nil, for a message that came with none (a REPL's). Its steps find `:bridge/request` nil. Its rate limits must then be `:no-rate-limits`: a limiter tells askers apart by their request, so a limiter with a nil request is refused, `:bridge/bad-options`.

**Why:** a request is what came over the wire; a message typed into a REPL came over none, and a made-up one says things that aren't so (an origin, a method), which a later step may believe.

**Instead of:**
- **a made-up request**: a fake Origin passes a check that should never have been asked
- **a second entry point, `answer-message-without-request`**: the same function with one argument fewer; nil written out says the same
