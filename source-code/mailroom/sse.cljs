(ns mailroom.sse
  "sse in the browser: an EventSource, kept open until it is closed on
   purpose. A broken line comes back two ways:

   - the network broke: the browser reopens the line by itself, after the
     server's retry: field (its :sse-reconnect-after-ms), and sends the last
     message's id as Last-Event-ID;
   - the server answered with an HTTP error (a proxy's 502 during a
     deploy) or not with an event stream: the browser gives the line up for
     good (readyState CLOSED). mailroom opens a new one after
     :reconnect-after-ms, the same setting handed to the browser by the
     app, and again after each failure. A new EventSource starts with no
     Last-Event-ID.

   Either way :on-open runs again with again? true, so the app asks for
   what it missed."
  (:require
   [mailroom.messages :as messages]
   [mailroom.settings :as settings]
   [mailroom.shapes :as shapes]
   [wire.api :as wire]))

(defn- hand-text! [{:keys [shapes-by-name handlers]} text]
  (->> (wire/turn-json-text-into-data text)
       (shapes/turn-answer-into-shaped-data shapes-by-name)
       (messages/hand-named-messages! handlers)))

(defn- has-given-up? [source]
  (= (.-CLOSED js/EventSource) (.-readyState source)))

(defn- connect! [{:keys [source closed? opened-before? address on-open reconnect-after-ms] :as line}]
  ;; the address is a path on the page's own origin, which gets the page's
  ;; cookie anyway; withCredentials only matters across origins, which
  ;; mailroom doesn't serve
  (let [event-source (js/EventSource. address #js {:withCredentials false})]
    (set! (.-onopen event-source) #(do (when on-open (on-open @opened-before?))
                                       (reset! opened-before? true)))
    (set! (.-onmessage event-source) #(hand-text! line (.-data %)))
    ;; CONNECTING here: the browser reopens it by itself; CLOSED: it gave up
    (set! (.-onerror event-source) #(when (and (not @closed?) (has-given-up? event-source))
                                      (js/setTimeout (fn [] (when-not @closed? (connect! line)))
                                                     reconnect-after-ms)))
    (reset! source event-source)))

(defn open-sse-line!
  "Opens the server stream; each named message, shaped by its :answer-shape,
   goes to its handler. Options, every key required: :on-open, (on-open
   again?) each time it opens, again? true after a break, or nil;
   :reconnect-after-ms, how long it waits before it opens a new line when
   the server answered with an error (the server's :sse-reconnect-after-ms).
   The shapes and the options are checked first. Returns the line."
  [address shapes-by-name handlers options]
  (settings/check-shapes-by-name shapes-by-name)
  (let [{:keys [on-open reconnect-after-ms]} (settings/check-sse-options options)]
    (doto {:source (atom nil) :closed? (atom false) :opened-before? (atom false)
           :address address :on-open on-open :reconnect-after-ms reconnect-after-ms
           :shapes-by-name shapes-by-name :handlers handlers}
      connect!)))

(defn close-sse-line!
  "Closes the line; it doesn't reopen."
  [{:keys [source closed?]}]
  (reset! closed? true)
  (.close @source))
