(ns mailroom.sse-close-test
  "An sse line is seen as closed once, however it closes: the browser
   leaving (seen by the next ping, or the next send), or the server
   closing it. http-kit 2.8.1 calls no :on-close when a browser leaves an
   sse line (#578), so these drop real sockets."
  (:require
   [clojure.java.io :as io]
   [clojure.test :refer [deftest is testing]]
   [mailroom.api :as mailroom]
   [mailroom.test-log :refer [log]]
   [mailroom.test-meter :refer [make-test-meter find-metric-lines]]
   [mailroom.test-wire :refer [wire]]
   [org.httpkit.server :as http-kit])
  (:import
   (java.net Socket)))

(defn- make-settings [ping-every-ms]
  {:ip "127.0.0.1" :port 0 :proxy-protocol :disable
   :client-address-header nil :server-header nil
   :max-body-bytes 8388608 :max-header-line-bytes 8192 :max-ws-message-bytes 4194304
   :addresses {:http "/mail/http" :sse "/mail/sse" :ws "/mail/ws"}
   :ping-every-ms ping-every-ms :sse-reconnect-after-ms 3000 :ws-reopen-after-ms 1000
   :stop-wait-ms 10000 :stop-wait-after-cut-ms 1000
   :rate-limit-buckets-before-cleanup 10000
   :metric-buckets {:answer-seconds [0.05 1]}})

(def ^:private handlers
  {:t/ok {:steps        [{:name :answer :enter #(mailroom/add-answer-to-context % {})}]
          :params-shape nil :answer-shape nil :rate-limit nil :run-steps-in nil}})

(defn- start!
  "A server whose sse lines are kept in opened and, each time one closes,
   added to closed. Returns {:port :server :meter :opened :closed}."
  [ping-every-ms]
  (let [settings (make-settings ping-every-ms)
        meter    (make-test-meter)
        opened   (atom [])
        closed   (atom [])
        routes   (mailroom/make-routes-for-messages handlers settings
                                                    {:wire wire :log log :meter meter
                                                     :find-rate-limit-key (constantly "one asker")
                                                     :on-sse-open  #(swap! opened conj %)
                                                     :on-sse-close #(swap! closed conj %)
                                                     :on-ws-open nil :on-ws-close nil})
        server   (mailroom/start-server! (mailroom/make-ring-handler-from-routes routes {:files nil}) settings {:log log})]
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
  (first (find-metric-lines meter "mailroom_open_lines{pipe=\"sse\"}")))

(deftest a-line-the-browser-left-is-closed-by-the-next-ping
  (let [{:keys [port server meter opened closed]} (start! 200)]
    (try
      (let [socket (open-socket! port)]
        (is (wait-until 5000 #(= 1 (count @opened))))
        (is (= "mailroom_open_lines{pipe=\"sse\"} 1" (open-sse-lines meter)))
        (let [left-at (System/currentTimeMillis)]
          (.close socket)
          (testing "on-sse-close runs within a ping interval (and the time the test's waits take)"
            (is (wait-until 1000 #(= 1 (count @closed))))
            (is (< (- (System/currentTimeMillis) left-at) 400))))
        (is (= (first @opened) (first @closed)))
        (is (not (mailroom/is-line-open? (first @closed))))
        (is (= "mailroom_open_lines{pipe=\"sse\"} 0" (open-sse-lines meter)))
        (testing "once: later pings and sends don't run it again"
          (is (false? (mailroom/send-message-on-sse-line! (first @closed) {:bells/changed {}} nil)))
          (Thread/sleep 600)
          (is (= 1 (count @closed)))
          (is (= "mailroom_open_lines{pipe=\"sse\"} 0" (open-sse-lines meter)))))
      (finally (mailroom/stop-server! server)))))

(deftest a-send-on-a-line-the-browser-left-closes-it
  ;; a ping a minute away: the send is what finds it
  (let [{:keys [port server meter opened closed]} (start! 60000)]
    (try
      (let [socket (open-socket! port)]
        (is (wait-until 5000 #(= 1 (count @opened))))
        (.close socket)
        (is (wait-until 2000 #(not (mailroom/is-line-open? (first @opened)))))
        (is (= [] @closed))
        (is (false? (mailroom/send-message-on-sse-line! (first @opened) {:bells/changed {}} nil)))
        (is (= 1 (count @closed)))
        (is (= "mailroom_open_lines{pipe=\"sse\"} 0" (open-sse-lines meter))))
      (finally (mailroom/stop-server! server)))))

(deftest a-line-the-server-closes-runs-on-sse-close-once
  (let [{:keys [port server meter opened closed]} (start! 200)]
    (try
      (let [socket (open-socket! port)]
        (is (wait-until 5000 #(= 1 (count @opened))))
        (http-kit/close (:mailroom/channel (first @opened)))
        (is (= 1 (count @closed)))
        (is (= "mailroom_open_lines{pipe=\"sse\"} 0" (open-sse-lines meter)))
        (testing "then the browser leaves too, and pings come: still once"
          (.close socket)
          (Thread/sleep 600)
          (is (= 1 (count @closed)))
          (is (= "mailroom_open_lines{pipe=\"sse\"} 0" (open-sse-lines meter)))))
      (finally (mailroom/stop-server! server)))))

(deftest many-lines-opened-and-left-leave-none
  (let [{:keys [port server meter opened closed]} (start! 200)
        how-many 200]
    (try
      (let [sockets (doall (repeatedly how-many #(open-socket! port)))]
        (is (wait-until 5000 #(= how-many (count @opened))))
        (is (= (str "mailroom_open_lines{pipe=\"sse\"} " how-many) (open-sse-lines meter)))
        (run! #(.close ^Socket %) sockets)
        (is (wait-until 2000 #(= how-many (count @closed))))
        (Thread/sleep 600)
        (testing "each line closed once"
          (is (= how-many (count @closed) (count (set @closed)))))
        (is (= (set @opened) (set @closed)))
        (is (= "mailroom_open_lines{pipe=\"sse\"} 0" (open-sse-lines meter))))
      (finally (mailroom/stop-server! server)))))
