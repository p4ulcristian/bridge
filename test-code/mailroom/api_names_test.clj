(ns mailroom.api-names-test
  "The public names of mailroom's api. Adding or removing one fails here,
   so the api changes on purpose."
  (:require
   [clojure.test :refer [deftest is]]
   [mailroom.api]))

(deftest the-api-names
  (is (= '#{settings-shape
           find-client-address
           make-routes-for-messages
           start-server!
           stop-server!
           send-message-on-sse-line!
           send-message-on-ws-line!
           is-line-open?
           answer-message
           make-server-routes-for-pages
           make-ring-handler-from-routes
           run-steps-until-answered
           add-answer-to-context
           does-context-have-answer?}
         (set (keys (ns-publics 'mailroom.api))))))
