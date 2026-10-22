# mailroom

Named messages come in, a handler's steps answer them, the answers go out:
how a browser and a Clojure server talk. A message is a map of names to
data, `{:orders/of-shop {:shop/id 7}}`; the server answers it from one
handler table, by name, over three pipes: **http** (a POST, answered at
once), **sse** (the server stream, to the browser) and **ws** (a websocket,
both ways). A handler's work is its **steps**, a list over one map, the
context, run until one adds an answer. Each handler may say its shapes, so
ids, decimals and dates arrive as real types and an answer leaves only as
its shape allows; a rate limit per asker; and the pages in the address bar,
by name, on both sides.

```clojure
io.github.p4ulcristian/mailroom {:git/tag "v0.1.0" :git/sha "…"}
```

Needs http-kit, reitit (reitit-ring on the server, reitit-frontend in the
browser) and [wire](https://github.com/p4ulcristian/wire) (data keeps its
types across the edge). One public namespace, `mailroom.api`: a `.clj` for
the server, a `.cljs` for the browser.

## The api

| side | name | does |
|---|---|---|
| server | `settings-shape` | the settings as a malli shape, every key required |
| server | `make-routes-for-messages` | the three routes, at the settings' addresses, from the handler table; a handler's `:rate-limit` counted per asker |
| server | `make-server-routes-for-pages` `make-ring-handler-from-routes` | each page answered when opened fresh; the one Ring handler, files from `:files-folder` (nil for none) |
| server | `start-server!` `stop-server!` | http-kit, every request on a virtual thread |
| server | `send-message-on-sse-line!` `send-message-on-ws-line!` `is-line-open?` | the server sends on an open line by itself |
| server | `answer-message` | a message's answer without a server: tests, the REPL |
| server | `find-client-address` | the asker's address, behind a proxy too |
| server | `run-steps-until-answered` `add-answer-to-context` `does-context-have-answer?` | steps: `{:name … :enter (fn [context] context)}` in order, until one answers (`:mailroom/answer`) |
| browser | `send-message!` | http: a promise of the answer, shaped |
| browser | `open-sse-line!` `close-sse-line!` `open-ws-line!` `send-message-on-ws-line!` `close-ws-line!` | sse and ws lines, reopened after a break |
| browser | `start-following-address!` `go-to-page!` `make-address-of-page` | the address bar leads to a page, by name |

Nothing has a default: every setting is written out, and the asker key is
given; a missing one refuses, named, with every other problem at once.

```clojure
(require '[mailroom.api :as mailroom])

(def settings
  {:ip "0.0.0.0" :port 8080
   :client-address-header "x-forwarded-for"            ; nil when no proxy is in front
   :addresses {:http "/mail/http" :sse "/mail/sse" :ws "/mail/ws"}
   :ping-every-ms 25000})                              ; so proxies don't cut idle lines

(def find-orders-of-shop
  {:name  :find-orders-of-shop
   :enter (fn [context]
            (assoc context :orders (find-orders db (get-in context [:mailroom/message-data :shop/id]))))})

(def add-orders-answer
  {:name  :add-orders-answer
   :enter #(mailroom/add-answer-to-context % (:orders %))})

(def handlers
  {:orders/of-shop {:params-shape [:map [:shop/id :uuid]]
                    :answer-shape [:vector [:map [:order/id :uuid] [:order/total :decimal]]]
                    :rate-limit   {:max 10 :per-ms 60000}
                    :steps        [find-orders-of-shop add-orders-answer]}})

(def server
  (mailroom/start-server!
   (mailroom/make-ring-handler-from-routes
    (into (mailroom/make-routes-for-messages
           handlers settings
           {:find-rate-limit-key #(mailroom/find-client-address % (:client-address-header settings))
            :on-sse-open         remember-line!
            :on-sse-close        forget-line!})
          (mailroom/make-server-routes-for-pages pages page-handler))
    {:files-folder "target/public"})
   settings))

;; the browser, with the same shapes from a .cljc file
(-> (mailroom/send-message! (:http addresses) shapes-by-name {:orders/of-shop {:shop/id shop-id}})
    (.then (fn [{:orders/keys [of-shop]}] …)))
```

## Tests

```sh
clojure -X:test                              # the server, on the JVM
npm install && npx shadow-cljs compile test  # the browser, on node
```

Why it is the way it is: [`decisions/`](decisions/README.md).

MIT license.
