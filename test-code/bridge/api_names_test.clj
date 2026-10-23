(ns bridge.api-names-test
  "The public names of bridge's api. Adding or removing one fails here,
   so the api changes on purpose."
  (:require
   [clojure.test :refer [deftest is]]
   [bridge.api]))

(deftest the-api-names
  (is (= '#{settings-shape
           find-client-address
           make-rate-limited-ring-handler
           make-routes-for-messages
           start-server!
           stop-server!
           send-message-on-sse-line!
           send-message-on-ws-line!
           is-line-open?
           answer-message
           is-error-answer?
           make-server-routes-for-pages
           make-ring-handler-from-routes
           run-steps-until-answered
           add-answer-to-context
           does-context-have-answer?}
         (set (keys (ns-publics 'bridge.api))))))
