(ns mailroom.routes-test
  (:require
   [clojure.test :refer [deftest is testing]]
   [mailroom.api :as mailroom])
  (:import
   (java.net URI)
   (java.net.http HttpClient HttpRequest HttpRequest$BodyPublishers HttpResponse$BodyHandlers)))

(def ^:private handlers
  {:greetings/hello {:steps [{:name :say-hello :enter #(mailroom/add-answer-to-context % "hello")}]}})

(def ^:private settings
  {:ip "127.0.0.1" :port 0 :client-address-header nil
   :addresses {:http "/mail/http" :sse "/mail/sse" :ws "/mail/ws"}
   :ping-every-ms 25000 :stop-wait-ms 10000 :ws-reopen-after-ms 1000
   :rate-limit-buckets-before-cleanup 10000})

(def ^:private pages
  {:orders/page {:address "/orders/:order-id" :title "Order"}})

(defn- page-handler [_request] {:status 200 :body "<html>the app</html>"})

(defn- send-request [port method path body]
  (let [response (.send (HttpClient/newHttpClient)
                        (-> (HttpRequest/newBuilder (URI. (str "http://localhost:" port path)))
                            (.method method (if body (HttpRequest$BodyPublishers/ofString body) (HttpRequest$BodyPublishers/noBody)))
                            (.build))
                        (HttpResponse$BodyHandlers/ofString))]
    [(.statusCode response) (.body response)]))

(deftest an-address-leads-to-its-pipe-or-its-page
  (let [server (mailroom/start-server!
                (mailroom/make-ring-handler-from-routes
                 (into (mailroom/make-routes-for-messages handlers settings {:find-rate-limit-key #(mailroom/find-client-address % nil)
                                                                                       :on-sse-open nil :on-sse-close nil
                                                                                       :on-ws-open nil :on-ws-close nil})
                       (mailroom/make-server-routes-for-pages pages page-handler))
                 {:files-folder nil})
                settings)
        port   (:port server)]
    (try
      (testing "a named message on the http address"
        (is (= [200 "{\"greetings/hello\":\"hello\"}"] (send-request port "POST" "/mail/http" "{\"greetings/hello\":{}}"))))
      (testing "a page opened fresh: the page handler"
        (is (= [200 "<html>the app</html>"] (send-request port "GET" "/orders/42" nil))))
      (testing "no route, no page: 404"
        (is (= 404 (first (send-request port "GET" "/nowhere" nil)))))
      (finally (mailroom/stop-server! server)))))

(deftest a-page-needs-a-namespaced-name-and-an-address
  (is (thrown? clojure.lang.ExceptionInfo (mailroom/make-server-routes-for-pages {:page {:address "/x"}} page-handler)))
  (is (thrown? clojure.lang.ExceptionInfo (mailroom/make-server-routes-for-pages {:orders/page {}} page-handler))))
