(ns mailroom.channel
  "Whether a line's http-kit channel is still open, told by its socket too.
   http-kit 2.8.1 (#578, fixed in 2.9.0's betas) tells a browser's leaving
   to the first channel its connection had, not the one an sse line's
   request has: the socket is closed, but the line's channel says it is
   open, its :on-close isn't called and its send! returns true. The
   channel keeps its socket's selection key in a private field, key; a key
   is valid until its socket is closed."
  (:require
   [org.httpkit.server :as http-kit])
  (:import
   (java.lang.reflect Field)
   (java.nio.channels SelectionKey)
   (org.httpkit.server AsyncChannel)))

(def ^:private ^Field key-field
  ;; read once, when mailroom loads: an http-kit without the field fails here, not on a line
  (doto (.getDeclaredField AsyncChannel "key")
    (.setAccessible true)))

(defn is-channel-open?
  "Whether the channel is open: not closed by http-kit, and its socket not
   closed."
  [channel]
  (and (http-kit/open? channel)
       (.isValid ^SelectionKey (.get key-field channel))))
