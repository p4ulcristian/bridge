(ns mailroom.api
  "Named messages come in, a handler's steps answer them, the answers go
   out: how a browser and a server talk, the browser's side. Named messages
   over http, sse and ws, and the pages in the address bar. The server's
   side is this namespace's .clj half.

   The addresses, the ws reopen delay and the pages come from the server
   (the app puts them in the page); the browser uses the same shapes as the server's handlers, by
   name, from a .cljc file of the app's:

     (def shapes-by-name {:orders/of-shop {:params-shape [:map [:shop/id :uuid]]
                                           :answer-shape [:vector [:map [:order/id :uuid] [:order/total :decimal]]]}})

     (mailroom/send-message! (:http addresses) shapes-by-name {:orders/of-shop {:shop/id id}})   ; a promise of the shaped answer
     (mailroom/open-sse-line! (:sse addresses) shapes-by-name {:sync/changed on-change})
     (mailroom/open-ws-line! (:ws addresses) shapes-by-name {:chat/said on-said}
                             {:reopen-after-ms ws-reopen-after-ms})              ; the server's setting, from the page"
  (:require
   [mailroom.http :as http]
   [mailroom.routes :as routes]
   [mailroom.sse :as sse]
   [mailroom.ws :as ws]))

(defn send-message!
  "http: POSTs a named message, its data by :params-shape. A promise of the
   answer keyed by the same names, each shaped by its :answer-shape, and
   :mailroom/notes beside them when the server sent notes."
  [address shapes-by-name message]
  (http/send-message! address shapes-by-name message))

(defn open-sse-line!
  "sse: opens the stream; each named message, shaped by its :answer-shape,
   goes to its handler by name. It reopens by itself after a break.
   Options: :on-open, (on-open again?) each time it opens; again? is true
   after a break, when messages may have been missed."
  ([address shapes-by-name handlers] (open-sse-line! address shapes-by-name handlers {}))
  ([address shapes-by-name handlers options] (sse/open-sse-line! address shapes-by-name handlers options)))

(defn close-sse-line!
  "sse: closes the line; it doesn't reopen."
  [line]
  (sse/close-sse-line! line))

(defn open-ws-line!
  "ws: opens the websocket; each named message, answers included, shaped by
   its :answer-shape, goes to its handler by name. Options, every key
   required: :reopen-after-ms, how long it waits before it reopens after a
   break (the server's :ws-reopen-after-ms setting, which the app puts in
   the page with the addresses). A missing or bad option refuses,
   :mailroom/bad-options."
  [address shapes-by-name handlers options]
  (ws/open-ws-line! address shapes-by-name handlers options))

(defn send-message-on-ws-line!
  "ws: sends a named message on the line, its data by :params-shape.
   Whether it was open."
  [line message]
  (ws/send-message-on-ws-line! line message))

(defn close-ws-line!
  "ws: closes the line; it doesn't reopen."
  [line]
  (ws/close-ws-line! line))

;; -----------------------------------------------------------------------------
;; Pages: the address in the bar leads to a page, by name

(defn start-following-address!
  "Follows the address bar: (on-page {:name … :page … :path-params … :query …})
   now and on every move, nil for an address no page has. The page's :title
   becomes the tab's title. pages is the map the server put in the page,
   {:orders/page {:address \"/orders/:order-id\" :title \"Order\"}}."
  [pages on-page]
  (routes/start-following-address! pages on-page))

(defn go-to-page!
  "Moves to the page with this name and path params; Back returns."
  ([page-name] (routes/go-to-page! page-name))
  ([page-name path-params] (routes/go-to-page! page-name path-params)))

(defn make-address-of-page
  "A page's address, for a link."
  ([page-name] (routes/make-address-of-page page-name))
  ([page-name path-params] (routes/make-address-of-page page-name path-params)))
