(ns mailroom.ws
  "ws in the browser: a WebSocket that reopens a second after a break,
   until it is closed on purpose. Each named message from the server goes
   to its handler, answers included."
  (:require
   [mailroom.messages :as messages]
   [mailroom.shapes :as shapes]
   [wire.api :as wire]))

(defn- make-full-address [address]
  (str (if (= "https:" (.. js/location -protocol)) "wss://" "ws://") (.. js/location -host) address))

(defn- hand-text!
  "The server's ping (as often as its settings say, so proxies keep the
   line) is dropped."
  [{:keys [shapes-by-name handlers]} text]
  (->> (dissoc (wire/turn-json-text-into-data text) :mailroom/ping)
       (shapes/turn-answer-into-shaped-data shapes-by-name)
       (messages/hand-named-messages! handlers)))

(defn- connect! [{:keys [socket closed? address] :as line}]
  (let [websocket (js/WebSocket. (make-full-address address))]
    (set! (.-onmessage websocket) #(hand-text! line (.-data %)))
    (set! (.-onclose websocket) #(when-not @closed? (js/setTimeout (fn [] (connect! line)) 1000)))
    (reset! socket websocket)))

(defn open-ws-line!
  "Opens the websocket; each named message, answers included, shaped by its
   :answer-shape, goes to its handler. Returns the line."
  [address shapes-by-name handlers]
  (doto {:socket (atom nil) :closed? (atom false) :address address
         :shapes-by-name shapes-by-name :handlers handlers}
    connect!))

(defn send-message-on-ws-line!
  "Sends a named message on the line, by its :params-shape. Whether it was open."
  [{:keys [socket shapes-by-name]} message]
  (let [open? (= 1 (.-readyState @socket))]
    (when open? (.send @socket (wire/turn-data-into-json-text (shapes/turn-message-into-json-data shapes-by-name message))))
    open?))

(defn close-ws-line!
  "Closes the line; it doesn't reopen."
  [{:keys [socket closed?]}]
  (reset! closed? true)
  (.close @socket))
