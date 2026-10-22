(ns mailroom.api
  "Named messages come in, a handler's steps answer them, the answers go
   out: how a browser and a server talk, the browser's side. Named messages
   over http, sse and ws, and the pages in the address bar (with their
   query, language and scrolling, and a question before leaving). The server's
   side is this namespace's .clj half.

   The addresses, the sse and ws reopen delays and the pages come from the server
   (the app puts them in the page); the browser uses the same shapes as
   the server's handlers, by name, both written out (nil: as it is), from
   a .cljc file of the app's:

     (def shapes-by-name {:orders/of-shop {:params-shape [:map [:shop/id :uuid]]
                                           :answer-shape [:vector [:map [:order/id :uuid] [:order/total :decimal]]]}
                          :sync/changed   {:params-shape nil :answer-shape nil}})

     ;; the app's wire, made once by the app from the browser's settings (mailroom never makes one)
     (def wire (wire.api/make-wire-from-settings {:most-shapes-kept 1000 :most-keys-kept 10000
                                                  :exact-decimals   wire.big/big}))

     (mailroom/send-message! (:http addresses) shapes-by-name {:orders/of-shop {:shop/id id}}
                             {:wire wire :headers {}})                            ; a promise of the shaped answer
     (mailroom/open-sse-line! (:sse addresses) shapes-by-name {:sync/changed on-change}
                              {:wire wire :on-open nil :reconnect-after-ms sse-reconnect-after-ms})   ; the server's setting, from the page
     (mailroom/open-ws-line! (:ws addresses) shapes-by-name {:chat/said on-said}
                             {:wire wire :reopen-after-ms ws-reopen-after-ms})   ; the server's setting, from the page

   Nothing has a default: the shapes and the options are checked first, and
   a missing key refuses, named (:mailroom/bad-shapes, :mailroom/bad-options).
   Every function that sends or reads a message takes the app's wire under
   :wire; a ws line keeps it for send-message-on-ws-line!."
  (:require
   [mailroom.answers :as answers]
   [mailroom.http :as http]
   [mailroom.routes :as routes]
   [mailroom.sse :as sse]
   [mailroom.ws :as ws]))

(defn send-message!
  "http: POSTs a named message, its data by :params-shape. A promise of the
   answer keyed by the same names, each shaped by its :answer-shape, and
   :mailroom/notes beside them when the server sent notes. A name the
   shapes don't have throws (:mailroom/no-shapes); nothing is sent.
   Options, every key required: :wire, the app's wire; :headers, more
   request headers by lowercase name, {} for none (accept and
   content-type are mailroom's own). A missing or bad option refuses,
   :mailroom/bad-options."
  [address shapes-by-name message options]
  (http/send-message! address shapes-by-name message options))

(defn is-error-answer?
  "Whether an answer is one of mailroom's error answers rather than data,
   the same on both sides: {:error …} alone (a refusal: the server's
   \"Not found.\", \"Too many requests.\", \"Something went wrong.\"),
   {:errors …} alone (params that didn't fit their shape), or one carrying
   :mailroom/errors (an answer that didn't fit its shape, or a name the
   shapes don't have, with the data as it came). Anything else is data."
  [answer]
  (answers/is-error-answer? answer))

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
   or bad option refuses, :mailroom/bad-options."
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
   :mailroom/bad-options."
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
   A missing or bad option refuses, :mailroom/bad-options."
  [pages options]
  (routes/start-following-address! pages options))

(defn go-to-page!
  "Moves to the page with this name, path params ({} for none) and query
   ({} for none; a nil value leaves its key out), in the language of the
   page shown, as a link would: Back returns. Another page asks
   :may-leave? first."
  [page-name path-params query]
  (routes/go-to-page! page-name path-params query))

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
