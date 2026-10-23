(ns groundwork-bridge.metrics-test
  "bridge's metrics on the app's meter: each named message by name, pipe
   and outcome, its answer time, the open lines; a name no handler has is
   \"other\". answer-message counts as the http address does."
  (:require
   [clojure.java.io :as io]
   [clojure.test :refer [deftest is testing]]
   [groundwork-bridge.api :as bridge]
   [groundwork-bridge.test-log :refer [log]]
   [groundwork-bridge.test-meter :refer [make-test-meter find-metric-lines]]
   [groundwork-bridge.test-shaper :refer [shaper]])
  (:import
   (java.net Socket URI)
   (java.net.http HttpClient HttpRequest HttpRequest$BodyPublishers HttpResponse$BodyHandlers WebSocket$Listener)))

(def ^:private settings
  {:ip "127.0.0.1" :port 0 :proxy-protocol :disable
   :client-address-header nil :server-header nil
   :max-body-bytes 8388608 :max-request-head-bytes 8192 :max-ws-message-bytes 4194304
   :idle-timeout-ms 120000 :threads {:min 8 :max 50 :idle-timeout-ms 60000}
   :addresses {:http "/mail/http" :sse "/mail/sse" :ws "/mail/ws"}
   :ping-every-ms 25000 :sse-reconnect-after-ms 3000 :ws-reopen-after-ms 1000
   :stop-wait-ms 10000 :stop-wait-after-cut-ms 1000
   :rate-limit-buckets-before-cleanup 10000
   :metric-buckets {:answer-seconds [0.05 1]}})

(defn- make-handler [steps]
  {:steps steps :params-shape nil :answer-shape nil :refusals {} :rate-limit nil :run-steps-in nil})

(defn- answer-with [answer]
  [{:name :answer :enter #(bridge/add-answer-to-context % answer)}])

(def ^:private handlers
  {:t/ok       (make-handler (answer-with {:hello "Anna"}))
   :t/invalid  (assoc (make-handler (answer-with {})) :params-shape [:map [:shop/id :uuid]])
   :t/refused  (assoc (make-handler [{:name :refuse :enter #(bridge/refuse % :t/closed nil)}]) :refusals {:t/closed nil})
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
        routes (bridge/make-routes-for-messages handlers settings
                                                  {:shaper shaper :log log :meter meter
                                                   :find-rate-limit-key (constantly "one asker")
                                                   :on-sse-open nil :on-sse-close nil :on-ws-open nil :on-ws-close nil})
        server (bridge/start-server! (bridge/make-ring-handler-from-routes routes {:files nil :not-found nil}) settings {:log log})
        port   (:port server)]
    (try
      (testing "before any message, only the declarations"
        (is (= [] (find-metric-lines meter "bridge_"))))
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
          (is (= ["groundwork_bridge_open_lines{pipe=\"sse\"} 1" "groundwork_bridge_open_lines{pipe=\"ws\"} 1"]
                 (find-metric-lines meter "groundwork_bridge_open_lines"))))
        (.join (.sendClose socket 1000 "done"))
        (.close sse)
        ;; only ws here: a browser leaving an sse line is seen at its next
        ;; write, the ping 25 s away here (groundwork-bridge.sse-close-test)
        (wait-until #(= "groundwork_bridge_open_lines{pipe=\"ws\"} 0" (second (find-metric-lines meter "groundwork_bridge_open_lines"))))
        (testing "and no longer once closed"
          (is (= "groundwork_bridge_open_lines{pipe=\"ws\"} 0" (second (find-metric-lines meter "groundwork_bridge_open_lines"))))))
      (testing "each outcome, by pipe; a name no handler has is other"
        (is (= ["groundwork_bridge_messages_total{name=\"other\",outcome=\"no-handler\",pipe=\"http\"} 2"
                "groundwork_bridge_messages_total{name=\"t/failed\",outcome=\"failed\",pipe=\"http\"} 1"
                "groundwork_bridge_messages_total{name=\"t/invalid\",outcome=\"invalid\",pipe=\"http\"} 1"
                "groundwork_bridge_messages_total{name=\"t/ok\",outcome=\"ok\",pipe=\"http\"} 2"
                "groundwork_bridge_messages_total{name=\"t/ok\",outcome=\"ok\",pipe=\"ws\"} 1"
                "groundwork_bridge_messages_total{name=\"t/refused\",outcome=\"refused\",pipe=\"http\"} 1"
                "groundwork_bridge_messages_total{name=\"t/too-many\",outcome=\"ok\",pipe=\"http\"} 1"
                "groundwork_bridge_messages_total{name=\"t/too-many\",outcome=\"too-many\",pipe=\"http\"} 1"
                "groundwork_bridge_messages_total{name=\"t/unfit\",outcome=\"unfit\",pipe=\"http\"} 1"]
               (find-metric-lines meter "groundwork_bridge_messages_total"))))
      (testing "each answer's time, by name, in the settings' buckets"
        (is (= ["groundwork_bridge_answer_seconds_count{name=\"other\"} 2"
                "groundwork_bridge_answer_seconds_count{name=\"t/failed\"} 1"
                "groundwork_bridge_answer_seconds_count{name=\"t/invalid\"} 1"
                "groundwork_bridge_answer_seconds_count{name=\"t/ok\"} 3"
                "groundwork_bridge_answer_seconds_count{name=\"t/refused\"} 1"
                "groundwork_bridge_answer_seconds_count{name=\"t/too-many\"} 2"
                "groundwork_bridge_answer_seconds_count{name=\"t/unfit\"} 1"]
               (find-metric-lines meter "groundwork_bridge_answer_seconds_count")))
        (is (= ["groundwork_bridge_answer_seconds_bucket{name=\"t/ok\",le=\"0.05\"} 3"
                "groundwork_bridge_answer_seconds_bucket{name=\"t/ok\",le=\"1.0\"} 3"
                "groundwork_bridge_answer_seconds_bucket{name=\"t/ok\",le=\"+Inf\"} 3"]
               (find-metric-lines meter "groundwork_bridge_answer_seconds_bucket{name=\"t/ok\""))))
      (finally (bridge/stop-server! server)))))

(defn- answer-on [meter limiter message]
  (bridge/answer-message handlers limiter {:token "one asker"} message
                         {:shaper shaper :log log :meter meter :metric-buckets (:metric-buckets settings)}))

(deftest answer-message-counts-as-the-http-address-does
  (let [meter   (make-test-meter)
        limiter (bridge/make-message-limiter {:find-rate-limit-key #(:token %) :buckets-before-cleanup 10})]
    (answer-on meter limiter {:t/ok {} :t/invalid {:shop/id "seven"} :t/refused {} :t/too-many {}
                              :t/failed {} :t/unfit {} :nobody/home {}})
    (answer-on meter limiter {:t/too-many {}})
    (testing "each outcome under pipe http, refusals and failures too; a name no handler has is other"
      (is (= ["groundwork_bridge_messages_total{name=\"other\",outcome=\"no-handler\",pipe=\"http\"} 1"
              "groundwork_bridge_messages_total{name=\"t/failed\",outcome=\"failed\",pipe=\"http\"} 1"
              "groundwork_bridge_messages_total{name=\"t/invalid\",outcome=\"invalid\",pipe=\"http\"} 1"
              "groundwork_bridge_messages_total{name=\"t/ok\",outcome=\"ok\",pipe=\"http\"} 1"
              "groundwork_bridge_messages_total{name=\"t/refused\",outcome=\"refused\",pipe=\"http\"} 1"
              "groundwork_bridge_messages_total{name=\"t/too-many\",outcome=\"ok\",pipe=\"http\"} 1"
              "groundwork_bridge_messages_total{name=\"t/too-many\",outcome=\"too-many\",pipe=\"http\"} 1"
              "groundwork_bridge_messages_total{name=\"t/unfit\",outcome=\"unfit\",pipe=\"http\"} 1"]
             (find-metric-lines meter "groundwork_bridge_messages_total"))))
    (testing "each answer's time, by name, in the buckets given"
      (is (= ["groundwork_bridge_answer_seconds_bucket{name=\"t/ok\",le=\"0.05\"} 1"
              "groundwork_bridge_answer_seconds_bucket{name=\"t/ok\",le=\"1.0\"} 1"
              "groundwork_bridge_answer_seconds_bucket{name=\"t/ok\",le=\"+Inf\"} 1"]
             (find-metric-lines meter "groundwork_bridge_answer_seconds_bucket{name=\"t/ok\""))))))

(deftest answer-message-and-the-routes-count-into-the-same-series
  (let [meter  (make-test-meter)
        routes (bridge/make-routes-for-messages handlers settings
                                                  {:shaper shaper :log log :meter meter
                                                   :find-rate-limit-key (constantly "one asker")
                                                   :on-sse-open nil :on-sse-close nil :on-ws-open nil :on-ws-close nil})
        server (bridge/start-server! (bridge/make-ring-handler-from-routes routes {:files nil :not-found nil}) settings {:log log})]
    (try
      (post-json (:port server) "{\"t/refused\":{}}")
      (answer-on meter :no-rate-limits {:t/refused {}})
      (is (= ["groundwork_bridge_messages_total{name=\"t/refused\",outcome=\"refused\",pipe=\"http\"} 2"]
             (find-metric-lines meter "groundwork_bridge_messages_total"))
          "the same message, by the http address or by answer-message: one series")
      (finally (bridge/stop-server! server)))))

(deftest answer-message-takes-the-meter-the-routes-declared
  (let [meter        (make-test-meter)
        refused-type #(try (%) nil (catch clojure.lang.ExceptionInfo e (:type (ex-data e))))
        options      {:shaper shaper :log log :meter meter :metric-buckets (:metric-buckets settings)}]
    (answer-on meter :no-rate-limits {:t/ok {}})
    (is (nil? (refused-type #(answer-on meter :no-rate-limits {:t/ok {}})))
        "the same table and buckets again: no change")
    (is (= :groundwork-metrics/bad-metrics
           (refused-type #(bridge/answer-message {:t/other (make-handler (answer-with {}))} :no-rate-limits {} {:t/other {}} options)))
        "another table of names on the same meter")
    (is (= :groundwork-metrics/bad-metrics
           (refused-type #(bridge/answer-message handlers :no-rate-limits {} {:t/ok {}}
                                                 (assoc options :metric-buckets {:answer-seconds [0.5]}))))
        "other buckets on the same meter")
    (is (= :groundwork-metrics/not-a-meter
           (refused-type #(bridge/answer-message handlers :no-rate-limits {} {:t/ok {}} (assoc options :meter {}))))
        "a meter meter didn't make")
    (is (= ["groundwork_bridge_messages_total{name=\"t/ok\",outcome=\"ok\",pipe=\"http\"} 2"]
           (find-metric-lines meter "groundwork_bridge_messages_total"))
        "a refused call counts nothing")))
