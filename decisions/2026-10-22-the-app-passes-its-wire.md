# The app passes its wire; mailroom never makes one

> Changes in part [Every value that sets behaviour is a setting; no hidden defaults](2026-10-22-every-value-that-sets-behaviour-is-a-setting.md) (`answer-message` takes options) and [web has http, sse and ws](2026-10-22-web-has-http-sse-and-ws.md) (a line carries the wire). Since v0.2.0, on wire v0.2.0.

**Decision:** wire v0.2.0 makes a wire from settings, and every one of its functions takes the wire first. The app makes its wire once on each side and passes the same value to every function of mailroom's that sends or reads a message, as a required key, `:wire`, in that function's options; mailroom never makes a wire and carries no wire settings.

| side | function | where the wire goes |
|---|---|---|
| server | `make-routes-for-messages` | its options' `:wire`: the three routes read and write every message by it |
| server | `answer-message` | a new options map, last: `(answer-message handlers :no-rate-limits request message {:wire wire})` |
| server | `send-message-on-sse-line!`, `send-message-on-ws-line!` | nothing new: a line is `{:mailroom/channel … :mailroom/request … :mailroom/wire …}`, the wire its routes were made with |
| browser | `send-message!` | a new options map, last: `(send-message! address shapes-by-name message {:wire wire})` |
| browser | `open-sse-line!`, `open-ws-line!` | their options' `:wire`; a ws line keeps it for `send-message-on-ws-line!` |

The key is checked by malli with the rest of the options (`settings/wire-entry`, `[:wire some?]`; wire has no predicate for a made wire): a missing one refuses, `:mailroom/bad-options`, naming `:wire`.

The settings, the options, the handlers, the pages and the browser's shapes are checked by malli itself, as cellar and showroom check theirs, not through wire's `find-errors-in-data-for-shape`: they are values the app hands in, not data crossing an edge, and checking them through wire would make every function, `start-server!` and `go-to-page!` too, take a wire. The error texts are malli's, which wire's were for these shapes.

```clojure
;; the server
(def wire (wire.api/make-wire-from-settings
           {:most-shapes-kept 1000
            :json-limits      {:max-nesting-depth 1000 :max-number-length 1000 :max-string-length 20000000
                               :max-name-length 50000 :max-document-length nil :max-token-count nil
                               :max-writing-nesting-depth 1000}}))
(mailroom/make-routes-for-messages handlers settings {:wire wire :find-rate-limit-key … :on-sse-open … …})

;; the browser
(def wire (wire.api/make-wire-from-settings {:most-shapes-kept 1000 :most-keys-kept 10000 :exact-decimals wire.big/big}))
(mailroom/send-message! (:http addresses) shapes-by-name {:orders/of-shop {:shop/id id}} {:wire wire})
```

**Why:** a wire is settings (Jackson's limits, how many shapes are kept, whether decimals are big.js) and what it keeps (each shape's tools). Made once by the app, one value is in effect everywhere, and the app's settings show it. A library that made its own would hide those settings again, keep its own tools beside the app's, and in the browser could hold decimals differently from the app. In the options, beside the other values each function requires, it is written out like them and refused like them. A line carries its wire because it is made by the routes, which have it, and the server sends on it from anywhere (doorbell's listener, an `:on-sse-open` hook): those callers need not hold a wire to send.

**The price:** every call that sends or reads a message writes `:wire`; `answer-message` and the browser's `send-message!` gain an options map. Every caller of v0.1 changes.

**Instead of:**
- a wire made inside mailroom from settings of its own: two wires in one app, settings in two places
- the wire as a first argument, as wire's own functions take it: unlike every other value mailroom requires, which are named options
- the wire passed to `send-message-on-sse-line!` and `send-message-on-ws-line!` too: every caller that sends on a line would need it, though the line already comes from routes that have it
- checking the settings through wire: every function, even those that send nothing, would take a wire
