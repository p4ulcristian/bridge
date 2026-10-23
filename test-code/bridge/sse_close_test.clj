(ns bridge.sse-close-test
  "An sse line is seen as closed once, however it closes: the browser
   leaving (seen when its socket ends, or by the next write at the
   latest), the server closing it, or a stop. These drop real sockets."
  (:require
   [clojure.java.io :as io]
   [clojure.test :refer [deftest is testing]]
   [bridge.api :as bridge]
   [bridge.test-log :refer [log]]
   [bridge.test-meter :refer [make-test-meter find-metric-lines]]
   [bridge.test-wire :refer [wire]])
  (:import
   (java.net Socket)))

(defn- make-settings [ping-every-ms]
  {:ip "127.0.0.1" :port 0 :proxy-protocol :disable
   :client-address-header nil :server-header nil
   :max-body-bytes 8388608 :max-request-head-bytes 8192 :max-ws-message-bytes 4194304
   :idle-timeout-ms 120000 :threads {:min 8 :max 50 :idle-timeout-ms 60000}
   :addresses {:http "/mail/http" :sse "/mail/sse" :ws "/mail/ws"}
   :ping-every-ms ping-every-ms :sse-reconnect-after-ms 3000 :ws-reopen-after-ms 1000
   :stop-wait-ms 10000 :stop-wait-after-cut-ms 1000
   :rate-limit-buckets-before-cleanup 10000
   :metric-buckets {:answer-seconds [0.05 1]}})

(def ^:private handlers
  {:t/ok {:steps        [{:name :answer :enter #(bridge/add-answer-to-context % {})}]
          :params-shape nil :answer-shape nil :rate-limit nil :run-steps-in nil}})

(defn- start!
  "A server whose sse lines are kept in opened and, each time one closes,
   added to closed. Returns {:port :server :meter :opened :closed}."
  [ping-every-ms]
  (let [settings (make-settings ping-every-ms)
        meter    (make-test-meter)
        opened   (atom [])
        closed   (atom [])
        routes   (bridge/make-routes-for-messages handlers settings
                                                    {:wire wire :log log :meter meter
                                                     :find-rate-limit-key (constantly "one asker")
                                                     :on-sse-open  #(swap! opened conj %)
                                                     :on-sse-close #(swap! closed conj %)
                                                     :on-ws-open nil :on-ws-close nil})
        server   (bridge/start-server! (bridge/make-ring-handler-from-routes routes {:files nil}) settings {:log log})]
    {:port (:port server) :server server :meter meter :opened opened :closed closed}))

(defn- open-socket!
  "A plain socket that has opened an sse line and read its first line, so
   closing it is a browser leaving."
  [port]
  (let [socket (Socket. "localhost" (int port))]
    (.write (.getOutputStream socket) (.getBytes "GET /mail/sse HTTP/1.1\r\nHost: localhost\r\n\r\n"))
    (.readLine (io/reader (.getInputStream socket)))
    socket))

(defn- wait-until
  "Waits, up to most-ms, until (done?); whether it was."
  [most-ms done?]
  (let [until (+ (System/currentTimeMillis) most-ms)]
    (loop []
      (cond (done?)                                 true
            (> (System/currentTimeMillis) until)    false
            :else                                   (do (Thread/sleep 10) (recur))))))

(defn- open-sse-lines [meter]
  (first (find-metric-lines meter "bridge_open_lines{pipe=\"sse\"}")))

(deftest a-line-the-browser-left-is-closed-by-the-next-ping
  (let [{:keys [port server meter opened closed]} (start! 200)]
    (try
      (let [socket (open-socket! port)]
        (is (wait-until 5000 #(= 1 (count @opened))))
        (is (= "bridge_open_lines{pipe=\"sse\"} 1" (open-sse-lines meter)))
        (let [left-at (System/currentTimeMillis)]
          (.close socket)
          (testing "on-sse-close runs within a ping interval (and the time the test's waits take)"
            (is (wait-until 1000 #(= 1 (count @closed))))
            (is (< (- (System/currentTimeMillis) left-at) 400))))
        (is (= (first @opened) (first @closed)))
        (is (not (bridge/is-line-open? (first @closed))))
        (is (= "bridge_open_lines{pipe=\"sse\"} 0" (open-sse-lines meter)))
        (testing "once: later pings and sends don't run it again"
          (is (false? (bridge/send-message-on-sse-line! (first @closed) {:bells/changed {}} nil)))
          (Thread/sleep 600)
          (is (= 1 (count @closed)))
          (is (= "bridge_open_lines{pipe=\"sse\"} 0" (open-sse-lines meter)))))
      (finally (bridge/stop-server! server)))))

(deftest a-line-the-browser-left-is-closed-at-once-without-a-write
  ;; a ping a minute away and nothing sent: the socket's end is what is seen
  (let [{:keys [port server meter opened closed]} (start! 60000)]
    (try
      (let [socket (open-socket! port)
            line   (do (is (wait-until 5000 #(= 1 (count @opened)))) (first @opened))
            left-at (System/currentTimeMillis)]
        (.close socket)
        (is (wait-until 1000 #(= 1 (count @closed))))
        (is (< (- (System/currentTimeMillis) left-at) 500))
        (is (not (bridge/is-line-open? line)))
        (testing "a send on it says it was closed, and runs nothing again"
          (is (false? (bridge/send-message-on-sse-line! line {:bells/changed {}} nil)))
          (is (= 1 (count @closed)))
          (is (= "bridge_open_lines{pipe=\"sse\"} 0" (open-sse-lines meter)))))
      (finally (bridge/stop-server! server)))))

(deftest a-line-the-server-closes-runs-on-sse-close-once
  (let [{:keys [port server meter opened closed]} (start! 200)]
    (try
      (let [socket (open-socket! port)]
        (is (wait-until 5000 #(= 1 (count @opened))))
        (bridge/close-line! (first @opened))
        (is (= 1 (count @closed)))
        (is (= "bridge_open_lines{pipe=\"sse\"} 0" (open-sse-lines meter)))
        (testing "then the browser leaves too, and pings come: still once"
          (.close socket)
          (Thread/sleep 600)
          (is (= 1 (count @closed)))
          (is (= "bridge_open_lines{pipe=\"sse\"} 0" (open-sse-lines meter)))))
      (finally (bridge/stop-server! server)))))

(deftest many-lines-opened-and-left-leave-none
  (let [{:keys [port server meter opened closed]} (start! 200)
        how-many 200]
    (try
      (let [sockets (doall (repeatedly how-many #(open-socket! port)))]
        (is (wait-until 5000 #(= how-many (count @opened))))
        (is (= (str "bridge_open_lines{pipe=\"sse\"} " how-many) (open-sse-lines meter)))
        (run! #(.close ^Socket %) sockets)
        (is (wait-until 2000 #(= how-many (count @closed))))
        (Thread/sleep 600)
        (testing "each line closed once"
          (is (= how-many (count @closed) (count (set @closed)))))
        (is (= (set @opened) (set @closed)))
        (is (= "bridge_open_lines{pipe=\"sse\"} 0" (open-sse-lines meter))))
      (finally (bridge/stop-server! server)))))

(deftest a-stop-closes-the-open-lines-once
  (let [{:keys [port server meter opened closed]} (start! 60000)
        sockets (doall (repeatedly 3 #(open-socket! port)))]
    (try
      (is (wait-until 5000 #(= 3 (count @opened))))
      (let [began (System/currentTimeMillis)]
        (is (true? (bridge/stop-server! server)))
        (is (< (- (System/currentTimeMillis) began) 2000) "the lines don't hold the stop for :stop-wait-ms"))
      (is (= 3 (count @closed) (count (set @closed))))
      (is (= "bridge_open_lines{pipe=\"sse\"} 0" (open-sse-lines meter)))
      (finally (run! #(.close ^Socket %) sockets)))))
