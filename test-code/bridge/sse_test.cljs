(ns bridge.sse-test
  "The browser's sse line, on node, with a stand-in EventSource that does
   what a browser's does: after a network break it reopens by itself
   (readyState CONNECTING, then onopen again); after an HTTP error it gives
   up (readyState CLOSED) and bridge opens a new one."
  (:require
   [cljs.test :refer [async deftest is testing]]
   [bridge.api :as bridge]
   [bridge.test-wire :refer [wire]]))

(def ^:private opened (atom []))

(defn- install-stand-in-event-source! []
  (reset! opened [])
  (let [stand-in (fn [address options]
                   (this-as this
                            (set! (.-address this) address)
                            (set! (.-withCredentials this) (.-withCredentials options))
                            (set! (.-readyState this) 0)
                            (set! (.-close this) #(set! (.-readyState this) 2))
                            (swap! opened conj this)
                            this))]
    (set! (.-CONNECTING stand-in) 0)
    (set! (.-OPEN stand-in) 1)
    (set! (.-CLOSED stand-in) 2)
    (set! (.-EventSource js/globalThis) stand-in)))

(defn- open! [source]
  (set! (.-readyState source) 1)
  ((.-onopen source) #js {}))

(defn- break-network! [source]
  (set! (.-readyState source) 0)
  ((.-onerror source) #js {}))

(defn- answer-with-http-error! [source]
  (set! (.-readyState source) 2)
  ((.-onerror source) #js {}))

(def ^:private shapes-by-name {:sync/changed {:params-shape nil :answer-shape nil :refusals {:sync/stopped nil}}})

(defn- after-ms [ms f] (js/setTimeout f ms))

(deftest an-http-error-opens-a-new-line-and-on-open-says-again
  (async done
         (install-stand-in-event-source!)
         (let [opens  (atom [])
               breaks (atom 0)
               line   (bridge/open-sse-line! "/mail/sse" shapes-by-name {}
                                               {:wire wire :on-open #(swap! opens conj %) :on-break #(swap! breaks inc)
                                                :reconnect-after-ms 20})]
           (testing "a first try that fails is no break: it was never open"
             (break-network! (first @opened))
             (is (= 0 @breaks)))
           (open! (first @opened))
           (is (= [false] @opens) "the first open")
           (is (false? (.-withCredentials (first @opened))))
           (testing "a network break: the browser reopens it itself, bridge opens nothing"
             (break-network! (first @opened))
             (is (= 1 @breaks) "the break told once")
             (break-network! (first @opened))
             (is (= 1 @breaks) "a failed try after it is the same break")
             (open! (first @opened))
             (is (= [false true] @opens))
             (is (= 1 (count @opened))))
           (testing "an HTTP error: the break told, nothing opened at once"
             (answer-with-http-error! (first @opened))
             (is (= 2 @breaks))
             (is (= 1 (count @opened))))
           (after-ms 60
                     (fn []
                       (is (= 2 (count @opened)) "a new line after :reconnect-after-ms")
                       (is (= "/mail/sse" (.-address (second @opened))))
                       (testing "and again after each failure, the same break"
                         (answer-with-http-error! (second @opened))
                         (is (= 2 @breaks)))
                       (after-ms 60
                                 (fn []
                                   (is (= 3 (count @opened)))
                                   (open! (nth @opened 2))
                                   (is (= [false true true] @opens) "on-open runs again, again? true")
                                   (bridge/close-sse-line! line)
                                   (is (= 2 (.-readyState (nth @opened 2))) "closed")
                                   (done))))))))

(deftest a-closed-line-stays-closed
  (async done
         (install-stand-in-event-source!)
         (let [breaks (atom 0)
               closed (bridge/open-sse-line! "/mail/sse" shapes-by-name {}
                                             {:wire wire :on-open nil :on-break #(swap! breaks inc) :reconnect-after-ms 20})]
           (open! (first @opened))
           (bridge/close-sse-line! closed)
           ((.-onerror (first @opened)) #js {})
           (is (= 0 @breaks) "closed on purpose is no break"))
         (install-stand-in-event-source!)
         (let [line (bridge/open-sse-line! "/mail/sse" shapes-by-name {}
                                           {:wire wire :on-open nil :on-break nil :reconnect-after-ms 20})]
           (testing "closed while a reopen waits: it doesn't open"
             (answer-with-http-error! (first @opened))
             (bridge/close-sse-line! line))
           (after-ms 60
                     (fn []
                       (is (= 1 (count @opened)))
                       (done))))))

(deftest an-sse-line-says-how-long-it-waits
  (let [problems (try (bridge/open-sse-line! "/mail/sse" shapes-by-name {} {:wire wire :on-open nil :on-break nil})
                      nil
                      (catch :default e (ex-data e)))]
    (is (= :bridge/bad-options (:type problems)) "no :reconnect-after-ms: refused before the line opens")
    (is (contains? (:errors problems) :reconnect-after-ms))))

(deftest a-refusal-on-the-line-is-shaped-as-over-http
  (install-stand-in-event-source!)
  (let [heard (atom [])
        line  (bridge/open-sse-line! "/mail/sse" shapes-by-name {:sync/changed #(swap! heard conj %)}
                                     {:wire wire :on-open nil :on-break nil :reconnect-after-ms 20})]
    ((.-onmessage (first @opened)) #js {:data "{\"sync/changed\":{\"bridge/refused\":\"sync/stopped\",\"bridge/data\":null,\"bridge/request-id\":\"r\"}}"})
    ((.-onmessage (first @opened)) #js {:data "{\"sync/changed\":{\"bridge/refused\":\"sync/unknown\",\"bridge/data\":null,\"bridge/request-id\":\"r\"}}"})
    (is (= [{:bridge/refused :sync/stopped :bridge/data nil :bridge/request-id "r"} :bridge/unfit]
           [(first @heard) (:bridge/refused (second @heard))])
        "a declared reason as a keyword; one the name doesn't declare, :bridge/unfit")
    (bridge/close-sse-line! line)))
