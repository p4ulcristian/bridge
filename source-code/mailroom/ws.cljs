(ns mailroom.ws
  "ws in the browser: a WebSocket that reopens :reopen-after-ms after a
   break (the server's :ws-reopen-after-ms setting, which the app hands to
   the browser), until it is closed on purpose. Each named message from the
   server goes to its handler, answers included. Every message is read and
   written by the app's wire, which the line keeps."
  (:require
   [mailroom.messages :as messages]
   [mailroom.settings :as settings]
   [mailroom.shapes :as shapes]
   [wire.api :as wire]))

(defn- make-full-address
  "The address on the page's own origin; wss when the page came over https."
  [address]
  (str (if (= "https:" (.. js/location -protocol)) "wss://" "ws://") (.. js/location -host) address))

(defn- hand-text!
  "The server's ping (as often as its settings say, so proxies keep the
   line) is dropped."
  [{:keys [wire shapes-by-name handlers]} text]
  (->> (dissoc (wire/turn-json-text-into-data wire text) :mailroom/ping)
       (shapes/turn-answer-into-shaped-data wire shapes-by-name)
       (messages/hand-named-messages! handlers)))

(defn- connect! [{:keys [socket closed? address reopen-after-ms] :as line}]
  (let [websocket (js/WebSocket. (make-full-address address))]
    (set! (.-onmessage websocket) #(hand-text! line (.-data %)))
    (set! (.-onclose websocket) #(when-not @closed? (js/setTimeout (fn [] (connect! line)) reopen-after-ms)))
    (reset! socket websocket)))

(defn open-ws-line!
  "Opens the websocket; each named message, answers included, shaped by its
   :answer-shape, goes to its handler. Options, every key required: :wire,
   the app's wire, kept by the line; :reopen-after-ms. The shapes and the
   options are checked first. Returns the line."
  [address shapes-by-name handlers options]
  (settings/check-shapes-by-name shapes-by-name)
  (let [{:keys [wire reopen-after-ms]} (settings/check-ws-options options)]
    (doto {:socket (atom nil) :closed? (atom false) :address address :wire wire :reopen-after-ms reopen-after-ms
           :shapes-by-name shapes-by-name :handlers handlers}
      connect!)))

(defn send-message-on-ws-line!
  "Sends a named message on the line, by its :params-shape, written by the
   line's wire. Whether it was open."
  [{:keys [socket wire shapes-by-name]} message]
  (let [open? (= js/WebSocket.OPEN (.-readyState @socket))]
    (when open? (.send @socket (wire/turn-data-into-json-text wire (shapes/turn-message-into-json-data wire shapes-by-name message))))
    open?))

(defn close-ws-line!
  "Closes the line; it doesn't reopen."
  [{:keys [socket closed?]}]
  (reset! closed? true)
  (.close @socket))
