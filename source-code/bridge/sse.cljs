(ns bridge.sse
  "sse in the browser: an EventSource, kept open until it is closed on
   purpose. A broken line comes back two ways:

   - the network broke: the browser reopens the line by itself, after the
     server's retry: field (its :sse-reconnect-after-ms), and sends the last
     message's id as Last-Event-ID;
   - the server answered with an HTTP error (a proxy's 502 during a
     deploy) or not with an event stream: the browser gives the line up for
     good (readyState CLOSED). bridge opens a new one after
     :reconnect-after-ms, the same setting handed to the browser by the
     app, and again after each failure. A new EventSource starts with no
     Last-Event-ID.

   Either way :on-break runs once, at the first error after an open, and
   :on-open runs again with again? true once it is back, so the app can
   show the line is down, and ask for what it missed."
  (:require
   [bridge.messages :as messages]
   [bridge.settings :as settings]
   [bridge.shapes :as shapes]
   [wire.api :as wire]))

(defn- hand-text! [{:keys [wire shapes-by-name handlers]} text]
  (->> (wire/turn-json-text-into-data wire text)
       (shapes/turn-answer-into-shaped-data wire shapes-by-name)
       (messages/hand-named-messages! handlers)))

(defn- has-given-up? [source]
  (= (.-CLOSED js/EventSource) (.-readyState source)))

(defn- connect! [{:keys [source closed? opened-before? open? address on-open on-break reconnect-after-ms] :as line}]
  ;; the address is a path on the page's own origin, which gets the page's
  ;; cookie anyway; withCredentials only matters across origins, which
  ;; bridge doesn't serve
  (let [event-source (js/EventSource. address #js {:withCredentials false})]
    (set! (.-onopen event-source) #(do (reset! open? true)
                                       (when on-open (on-open @opened-before?))
                                       (reset! opened-before? true)))
    (set! (.-onmessage event-source) #(hand-text! line (.-data %)))
    (set! (.-onerror event-source) #(when-not @closed?
                                      ;; the first error after an open is the break; the
                                      ;; failed tries after it aren't new ones
                                      (when (compare-and-set! open? true false)
                                        (when on-break (on-break)))
                                      ;; CONNECTING here: the browser reopens it by itself; CLOSED: it gave up
                                      (when (has-given-up? event-source)
                                        (js/setTimeout (fn [] (when-not @closed? (connect! line)))
                                                       reconnect-after-ms))))
    (reset! source event-source)))

(defn open-sse-line!
  "Opens the server stream; each named message, shaped by its :answer-shape,
   goes to its handler. Options, every key required: :wire, the app's
   wire, which each message is read by; :on-open, (on-open
   again?) each time it opens, again? true after a break, or nil;
   :on-break, (on-break) once per break, at the first error after an
   open, or nil;
   :reconnect-after-ms, how long it waits before it opens a new line when
   the server answered with an error (the server's :sse-reconnect-after-ms).
   The shapes and the options are checked first. Returns the line."
  [address shapes-by-name handlers options]
  (settings/check-shapes-by-name shapes-by-name)
  (let [{:keys [wire on-open on-break reconnect-after-ms]} (settings/check-sse-options options)]
    (doto {:source (atom nil) :closed? (atom false) :opened-before? (atom false) :open? (atom false)
           :address address :wire wire :on-open on-open :on-break on-break :reconnect-after-ms reconnect-after-ms
           :shapes-by-name shapes-by-name :handlers handlers}
      connect!)))

(defn close-sse-line!
  "Closes the line; it doesn't reopen."
  [{:keys [source closed?]}]
  (reset! closed? true)
  (.close @source))
