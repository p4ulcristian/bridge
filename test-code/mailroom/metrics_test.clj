(ns mailroom.metrics-test
  "mailroom's metrics on the app's meter: each named message by name, pipe
   and outcome, its answer time, the open lines; a name no handler has is
   \"other\"."
  (:require
   [clojure.java.io :as io]
   [clojure.test :refer [deftest is testing]]
   [mailroom.api :as mailroom]
   [mailroom.test-log :refer [log]]
   [mailroom.test-meter :refer [make-test-meter find-metric-lines]]
   [mailroom.test-wire :refer [wire]])
  (:import
   (java.net Socket URI)
   (java.net.http HttpClient HttpRequest HttpRequest$BodyPublishers HttpResponse$BodyHandlers WebSocket$Listener)))

(def ^:private settings
  {:ip "127.0.0.1" :port 0 :proxy-protocol :disable
   :client-address-header nil :server-header nil
   :max-body-bytes 8388608 :max-header-line-bytes 8192 :max-ws-message-bytes 4194304
   :addresses {:http "/mail/http" :sse "/mail/sse" :ws "/mail/ws"}
   :ping-every-ms 25000 :sse-reconnect-after-ms 3000 :ws-reopen-after-ms 1000
   :stop-wait-ms 10000 :stop-wait-after-cut-ms 1000
   :rate-limit-buckets-before-cleanup 10000
   :metric-buckets {:answer-seconds [0.05 1]}})

(defn- make-handler [steps]
  {:steps steps :params-shape nil :answer-shape nil :rate-limit nil :run-steps-in nil})

(defn- answer-with [answer]
  [{:name :answer :enter #(mailroom/add-answer-to-context % answer)}])

(def ^:private handlers
  {:t/ok       (make-handler (answer-with {:hello "Anna"}))
   :t/invalid  (assoc (make-handler (answer-with {})) :params-shape [:map [:shop/id :uuid]])
   :t/refused  (make-handler (answer-with {:error "Not found."}))
   :t/too-many (assoc (make-handler (answer-with {})) :rate-limit {:max 1 :per-ms 60000})
   :t/failed   (make-handler [{:name :fail :enter (fn [_] (throw (ex-info "it broke" {})))}])
   :t/unfit    (assoc (make-handler (answer-with {:extra 1})) :answer-shape [:map [:total :int]])})

(defn- post-json [port body]
  (.body (.send (HttpClient/newHttpClient)
                (-> (HttpRequest/newBuilder (URI. (str "http://localhost:" port "/mail/http")))
                    (.POST (HttpRequest$BodyPublishers/ofString body))
                    (.build))
                (HttpResponse$BodyHandlers/ofString))))

(defn- wait-until [done?]
  (loop [n 0]
    (when (and (not (done?)) (< n 100))
      (Thread/sleep 20)
      (recur (inc n)))))

(deftest each-message-is-counted-by-name-pipe-and-outcome
  (let [meter  (make-test-meter)
        routes (mailroom/make-routes-for-messages handlers settings
                                                  {:wire wire :log log :meter meter
                                                   :find-rate-limit-key (constantly "one asker")
                                                   :on-sse-open nil :on-sse-close nil :on-ws-open nil :on-ws-close nil})
        server (mailroom/start-server! (mailroom/make-ring-handler-from-routes routes {:files nil}) settings {:log log})
        port   (:port server)]
    (try
      (testing "before any message, only the declarations"
        (is (= [] (find-metric-lines meter "mailroom_"))))
      (post-json port (str "{\"t/ok\":{},\"t/invalid\":{\"shop/id\":\"seven\"},\"t/refused\":{},\"t/too-many\":{},"
                           "\"t/failed\":{},\"t/unfit\":{},\"nobody/home\":{},\"wp/admin\":{}}"))
      (post-json port "{\"t/too-many\":{},\"t/ok\":{}}")
      (let [received (promise)
            socket   (.join (.buildAsync (.newWebSocketBuilder (HttpClient/newHttpClient))
                                         (URI. (str "ws://localhost:" port "/mail/ws"))
                                         (reify WebSocket$Listener
                                           (onText [_ socket text _last]
                                             (deliver received (str text))
                                             (.request socket 1)
                                             nil))))
            ;; a plain socket, so closing it closes the line
            sse      (doto (Socket. "localhost" (int port))
                       (-> .getOutputStream (.write (.getBytes "GET /mail/sse HTTP/1.1\r\nHost: localhost\r\n\r\n"))))
            reader   (io/reader (.getInputStream sse))]
        (.readLine reader)
        (.join (.sendText socket "{\"t/ok\":{}}" true))
        (deref received 5000 nil)
        (testing "an open line is counted while it is open"
          (is (= ["mailroom_open_lines{pipe=\"sse\"} 1" "mailroom_open_lines{pipe=\"ws\"} 1"]
                 (find-metric-lines meter "mailroom_open_lines"))))
        (.join (.sendClose socket 1000 "done"))
        (.close sse)
        ;; only ws: http-kit 2.8.1 calls no :on-close when a browser leaves an
        ;; sse line (its server closes the socket, but tells an older channel)
        (wait-until #(= "mailroom_open_lines{pipe=\"ws\"} 0" (second (find-metric-lines meter "mailroom_open_lines"))))
        (testing "and no longer once closed"
          (is (= "mailroom_open_lines{pipe=\"ws\"} 0" (second (find-metric-lines meter "mailroom_open_lines"))))))
      (testing "each outcome, by pipe; a name no handler has is other"
        (is (= ["mailroom_messages_total{name=\"other\",outcome=\"no-handler\",pipe=\"http\"} 2"
                "mailroom_messages_total{name=\"t/failed\",outcome=\"failed\",pipe=\"http\"} 1"
                "mailroom_messages_total{name=\"t/invalid\",outcome=\"invalid\",pipe=\"http\"} 1"
                "mailroom_messages_total{name=\"t/ok\",outcome=\"ok\",pipe=\"http\"} 2"
                "mailroom_messages_total{name=\"t/ok\",outcome=\"ok\",pipe=\"ws\"} 1"
                "mailroom_messages_total{name=\"t/refused\",outcome=\"refused\",pipe=\"http\"} 1"
                "mailroom_messages_total{name=\"t/too-many\",outcome=\"ok\",pipe=\"http\"} 1"
                "mailroom_messages_total{name=\"t/too-many\",outcome=\"too-many\",pipe=\"http\"} 1"
                "mailroom_messages_total{name=\"t/unfit\",outcome=\"unfit\",pipe=\"http\"} 1"]
               (find-metric-lines meter "mailroom_messages_total"))))
      (testing "each answer's time, by name, in the settings' buckets"
        (is (= ["mailroom_answer_seconds_count{name=\"other\"} 2"
                "mailroom_answer_seconds_count{name=\"t/failed\"} 1"
                "mailroom_answer_seconds_count{name=\"t/invalid\"} 1"
                "mailroom_answer_seconds_count{name=\"t/ok\"} 3"
                "mailroom_answer_seconds_count{name=\"t/refused\"} 1"
                "mailroom_answer_seconds_count{name=\"t/too-many\"} 2"
                "mailroom_answer_seconds_count{name=\"t/unfit\"} 1"]
               (find-metric-lines meter "mailroom_answer_seconds_count")))
        (is (= ["mailroom_answer_seconds_bucket{name=\"t/ok\",le=\"0.05\"} 3"
                "mailroom_answer_seconds_bucket{name=\"t/ok\",le=\"1.0\"} 3"
                "mailroom_answer_seconds_bucket{name=\"t/ok\",le=\"+Inf\"} 3"]
               (find-metric-lines meter "mailroom_answer_seconds_bucket{name=\"t/ok\""))))
      (finally (mailroom/stop-server! server)))))

(deftest answer-message-counts-nothing
  (is (= {:t/ok {:hello "Anna"}}
         (mailroom/answer-message handlers :no-rate-limits {} {:t/ok {}} {:wire wire :log log}))))
