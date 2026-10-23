(ns bridge.api
  "Named messages come in, a handler's steps answer them, the answers go
   out: how a browser and a server talk, the browser's side. Named messages
   over http, sse and ws, and the pages in the address bar (with their
   query, language and scrolling, and a question before leaving). The server's
   side is this namespace's .clj half.

   The addresses, the sse and ws reopen delays and the pages come from the server
   (the app puts them in the page); the browser uses the same shapes as
   the server's handlers, by name, each written out (nil: as it is; {}:
   no reasons), from a .cljc file of the app's:

     (def shapes-by-name {:orders/of-shop {:params-shape [:map [:shop/id :uuid]]
                                           :answer-shape [:vector [:map [:order/id :uuid] [:order/total :decimal]]]
                                           :refusals     {:shop/closed [:map [:shop/opens-at :time/instant]]}}
                          :sync/changed   {:params-shape nil :answer-shape nil :refusals {}}})

     ;; the app's wire, made once by the app from the browser's settings (bridge never makes one)
     (def wire (wire.api/make-wire-from-settings {:most-shapes-kept 1000 :most-keys-kept 10000
                                                  :exact-decimals   wire.big/big}))

     (bridge/send-message! (:http addresses) shapes-by-name {:orders/of-shop {:shop/id id}}
                             {:wire wire :headers {} :keep-alive? false})        ; a promise of the shaped answer
     (bridge/open-sse-line! (:sse addresses) shapes-by-name {:sync/changed on-change}
                              {:wire wire :on-open nil :reconnect-after-ms sse-reconnect-after-ms})   ; the server's setting, from the page
     (bridge/open-ws-line! (:ws addresses) shapes-by-name {:chat/said on-said}
                             {:wire wire :reopen-after-ms ws-reopen-after-ms})   ; the server's setting, from the page

   Nothing has a default: the shapes and the options are checked first, and
   a missing key refuses, named (:bridge/bad-shapes, :bridge/bad-options).
   Every function that sends or reads a message takes the app's wire under
   :wire; a ws line keeps it for send-message-on-ws-line!."
  (:require
   [bridge.answers :as answers]
   [bridge.http :as http]
   [bridge.routes :as routes]
   [bridge.sse :as sse]
   [bridge.ws :as ws]))

(defn send-message!
  "http: POSTs a named message, its data by :params-shape. A promise of the
   answer keyed by the same names, each shaped by its :answer-shape or a
   refusal, its data by its reason's shape, and :bridge/notes beside them
   when the server sent notes. It never fails for want of an answer: no
   response (the network), or one that isn't the server's JSON (a proxy's
   502, a 413), is {:bridge/refused :bridge/no-answer :bridge/data
   {:http-status n}} under each name, n nil when no response came. A name
   the shapes don't have throws (:bridge/no-shapes); nothing is sent.
   Options, every key required: :wire, the app's wire; :headers, more
   request headers by lowercase name, {} for none (accept and
   content-type are bridge's own); :keep-alive?, true for a request that
   finishes after the page closes (fetch's keepalive: the browser refuses
   a body that, with the other keepalive requests in flight, passes 64 KB,
   and that is :bridge/no-answer), false for a plain one. A missing or
   bad option refuses, :bridge/bad-options."
  [address shapes-by-name message options]
  (http/send-message! address shapes-by-name message options))

(defn is-error-answer?
  "Whether an answer is a refusal rather than data, the same on both
   sides: a map carrying :bridge/refused, the reason, with :bridge/data
   and the server's :bridge/request-id (none on :bridge/no-answer, which
   no server sent). Anything else is data."
  [answer]
  (answers/is-error-answer? answer))

(def refusals
  "bridge's own reasons, each with the shape of its data (nil: none), the
   same as on the server: :bridge/too-many, :bridge/invalid,
   :bridge/failed, the server's; :bridge/no-answer {:http-status n} and
   :bridge/unfit {:errors …}, the browser's own. Every name may be refused
   with them; its :refusals add its handler's."
  answers/refusals)

(defn open-sse-line!
  "sse: opens the stream; each named message, shaped by its :answer-shape,
   goes to its handler by name. It stays open until close-sse-line!: after
   a break in the network the browser reopens it, after the server's
   :sse-reconnect-after-ms (sent on the line); after an HTTP error (a
   proxy's 502 during a deploy), which the browser doesn't reopen, a new
   line opens after :reconnect-after-ms, and again after each failure.
   Options, every key required: :wire, the app's wire, which each message
   is read by. :on-open, (on-open again?) each time it
   opens, or nil; again? is true after a break, when messages may have
   been missed. :reconnect-after-ms, the server's :sse-reconnect-after-ms
   setting, which the app puts in the page with the addresses. A missing
   or bad option refuses, :bridge/bad-options."
  [address shapes-by-name handlers options]
  (sse/open-sse-line! address shapes-by-name handlers options))

(defn close-sse-line!
  "sse: closes the line; it doesn't reopen."
  [line]
  (sse/close-sse-line! line))

(defn open-ws-line!
  "ws: opens the websocket; each named message, answers included, shaped by
   its :answer-shape, goes to its handler by name. Options, every key
   required: :wire, the app's wire, which the line keeps for
   send-message-on-ws-line!; :reopen-after-ms, how long it waits before it reopens after a
   break (the server's :ws-reopen-after-ms setting, which the app puts in
   the page with the addresses). A missing or bad option refuses,
   :bridge/bad-options."
  [address shapes-by-name handlers options]
  (ws/open-ws-line! address shapes-by-name handlers options))

(defn send-message-on-ws-line!
  "ws: sends a named message on the line, its data by :params-shape,
   written by the line's wire. Whether it was open."
  [line message]
  (ws/send-message-on-ws-line! line message))

(defn close-ws-line!
  "ws: closes the line; it doesn't reopen."
  [line]
  (ws/close-ws-line! line))

;; -----------------------------------------------------------------------------
;; Pages: the address in the bar leads to a page, by name

(defn start-following-address!
  "Follows the address bar: (on-page page scroll) now and on every move,
   page {:name :page :path-params :query :query-errors :fragment :language}
   or nil for an address no page has; scroll says how the page should
   scroll once drawn (scroll-as-told!): a new page from the top or at its
   #fragment, a change of only the query where it is, Back and Forward
   where that entry was left. The page's :title becomes the tab's title (nil:
   the app sets it). pages is the map the server put in the page,
   {:orders/page {:address \"/orders/:order-id\" :title \"Order\"}}.
   Options, every key required:
   :on-page;
   :query-shapes-of-pages, for every page and no other a malli map its
   query's text values are read by (a value that doesn't fit is left out
   and named in :query-errors), or nil to keep them text;
   :may-leave?, (fn []) asked before a move to another page by a link,
   go-to-page! or Back and Forward (no: nothing moves), or nil;
   :languages, nil, or {:split-language-from-path f :make-path-in-language f
   :language-of-page \"en\"}: the app's functions for an address's language
   prefix (dictionary's), and the language the page came in, which an
   address without a prefix is in. A move keeps the language.
   A missing or bad option refuses, :bridge/bad-options."
  [pages options]
  (routes/start-following-address! pages options))

(defn go-to-page!
  "Moves to the page with this name, path params ({} for none) and query
   ({} for none; a nil value leaves its key out), in the language of the
   page shown, as a link would. way, written out: :push adds a history
   entry, which Back returns from; :replace takes the place of the entry
   shown (a picker rewriting the query on every tap). Another page asks
   :may-leave? first. Any other way throws, :bridge/bad-way."
  [page-name path-params query way]
  (routes/go-to-page! page-name path-params query way))

(defn make-address-of-page
  "A page's address, for a link, with its path params and query ({} for
   none), in the language of the page shown:
   (make-address-of-page :orders/page {:order-id 42} {:tab :notes}) -> \"/de/orders/42?tab=notes\"."
  [page-name path-params query]
  (routes/make-address-of-page page-name path-params query))

(defn scroll-as-told!
  "Scrolls the window as a move told start-following-address!'s on-page:
   :top, [:y y], [:element id], or :keep (not at all). The app calls it
   once the page is drawn."
  [scroll]
  (routes/scroll-as-told! scroll))
