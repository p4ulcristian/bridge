(ns mailroom.messages
  "The browser's handler table: a named message from the server finds its
   handler by name.")

(defn hand-named-messages!
  "Calls (handler data) for each name of the message; a name with no
   handler is a warning in the console, never dropped silently."
  [handlers message]
  (doseq [[message-name data] message]
    (if-let [handler (get handlers message-name)]
      (handler data)
      (js/console.warn "No browser handler named" (str message-name)))))
