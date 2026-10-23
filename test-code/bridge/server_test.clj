(ns bridge.server-test
  "Jetty by the settings: the PROXY protocol, the Server header, the
   request's size, the ws message's size, virtual threads."
  (:require
   [clojure.java.io :as io]
   [clojure.string :as str]
   [clojure.test :refer [deftest is testing]]
   [bridge.api :as bridge]
   [bridge.test-log :refer [log]]
   [bridge.test-meter :refer [with-new-meter]]
   [bridge.test-wire :refer [wire]])
  (:import
   (java.net Socket URI)
   (java.net.http HttpClient WebSocket$Listener)))

(def ^:private settings
  {:ip "127.0.0.1" :port 0 :proxy-protocol :disable
   :client-address-header nil :server-header nil
   :max-body-bytes 8388608 :max-request-head-bytes 8192 :max-ws-message-bytes 64
   :idle-timeout-ms 120000 :threads {:min 8 :max 50 :idle-timeout-ms 60000}
   :addresses {:http "/mail/http" :sse "/mail/sse" :ws "/mail/ws"}
   :ping-every-ms 25000 :sse-reconnect-after-ms 3000 :ws-reopen-after-ms 1000
   :stop-wait-ms 10000 :stop-wait-after-cut-ms 1000
   :rate-limit-buckets-before-cleanup 10000
   :metric-buckets {:answer-seconds [0.05 1]}})

(defn- say-where-and-how [request]
  {:status  200
   :headers {"content-type" "text/plain"}
   :body    (str (:remote-addr request) " " (.isVirtual (Thread/currentThread)))})

(defn- with-server [ring-handler settings f]
  (let [server (bridge/start-server! ring-handler settings {:log log})]
    (try (f (:port server)) (finally (bridge/stop-server! server)))))

(defn- ask
  "Writes text on a new socket; the answer's status line, its headers, and
   its last line. Nil when the server closed the socket without one."
  [port text]
  (with-open [socket (Socket. "127.0.0.1" (int port))]
    (.setSoTimeout socket 5000)
    (.write (.getOutputStream socket) (.getBytes ^String text))
    (let [reader ^java.io.BufferedReader (io/reader (.getInputStream socket))
          lines  (vec (take-while some? (repeatedly #(try (.readLine reader) (catch java.io.IOException _ nil)))))]
      (when (seq lines)
        {:status (first lines) :lines lines :last (peek lines)}))))

(defn- get-text [port & headers]
  (str "GET / HTTP/1.1\r\nHost: localhost\r\nConnection: close\r\n" (str/join (map #(str % "\r\n") headers)) "\r\n"))

(def ^:private proxy-line "PROXY TCP4 203.0.113.7 127.0.0.1 51000 80\r\n")

(deftest every-request-runs-on-a-virtual-thread
  (with-server say-where-and-how settings
    (fn [port]
      (is (= "127.0.0.1 true" (:last (ask port (get-text port))))))))

(deftest the-proxy-protocol-by-the-setting
  (testing ":disable: a PROXY line is no HTTP"
    (with-server say-where-and-how settings
      (fn [port]
        (is (str/includes? (:status (ask port (str proxy-line (get-text port)))) "400")))))
  (testing ":optional: both, the asker's address from the PROXY line"
    (with-server say-where-and-how (assoc settings :proxy-protocol :optional)
      (fn [port]
        (is (= "203.0.113.7 true" (:last (ask port (str proxy-line (get-text port))))))
        (is (= "127.0.0.1 true" (:last (ask port (get-text port))))))))
  (testing ":enable: only with the PROXY line; without it the socket is closed"
    (with-server say-where-and-how (assoc settings :proxy-protocol :enable)
      (fn [port]
        (is (= "203.0.113.7 true" (:last (ask port (str proxy-line (get-text port))))))
        (is (nil? (ask port (get-text port))))))))

(deftest the-server-header-is-the-setting-s
  (testing "nil: none, and not Jetty's"
    (with-server say-where-and-how settings
      (fn [port]
        (is (not-any? #(str/starts-with? (str/lower-case %) "server:") (:lines (ask port (get-text port))))))))
  (testing "a text: that"
    (with-server say-where-and-how (assoc settings :server-header "shop")
      (fn [port]
        (is (some #{"Server: shop"} (:lines (ask port (get-text port)))))))))

(deftest a-request-head-over-the-setting-is-refused
  (with-server say-where-and-how (assoc settings :max-request-head-bytes 1024)
    (fn [port]
      (is (str/includes? (:status (ask port (get-text port (str "x-long: " (apply str (repeat 2000 "a")))))) "431"))
      (is (str/includes? (:status (ask port (str "GET /" (apply str (repeat 2000 "a")) " HTTP/1.1\r\nHost: localhost\r\n\r\n"))) "414"))
      (is (str/includes? (:status (ask port (get-text port "x-short: a"))) "200") "within it, answered"))))

(deftest a-ws-message-over-the-setting-closes-the-line
  (let [opened  (promise)
        closed  (promise)
        options (with-new-meter {:wire wire :log log :find-rate-limit-key (constantly "one asker")
                                 :on-sse-open nil :on-sse-close nil
                                 :on-ws-open #(deliver opened %) :on-ws-close #(deliver closed %)})
        routes  (bridge/make-routes-for-messages {} settings options)]
    (with-server (bridge/make-ring-handler-from-routes routes {:files nil :not-found nil}) settings
      (fn [port]
        (let [code   (promise)
              socket (.join (.buildAsync (.newWebSocketBuilder (HttpClient/newHttpClient))
                                         (URI. (str "ws://localhost:" port "/mail/ws"))
                                         (reify WebSocket$Listener
                                           (onClose [_ _socket status _reason]
                                             (deliver code status)
                                             nil))))]
          (is (some? (deref opened 5000 nil)))
          (.join (.sendText socket (str "{\"x/y\":\"" (apply str (repeat 100 "a")) "\"}") true))
          (is (= 1009 (deref code 5000 nil)) "too big")
          (is (some? (deref closed 5000 nil)) "on-ws-close ran"))))))
