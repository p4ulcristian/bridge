(ns mailroom.sse-test
  "The browser's sse line, on node, with a stand-in EventSource that does
   what a browser's does: after a network break it reopens by itself
   (readyState CONNECTING, then onopen again); after an HTTP error it gives
   up (readyState CLOSED) and mailroom opens a new one."
  (:require
   [cljs.test :refer [async deftest is testing]]
   [mailroom.api :as mailroom]))

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

(def ^:private shapes-by-name {:sync/changed {:params-shape nil :answer-shape nil}})

(defn- after-ms [ms f] (js/setTimeout f ms))

(deftest an-http-error-opens-a-new-line-and-on-open-says-again
  (async done
         (install-stand-in-event-source!)
         (let [opens (atom [])
               line  (mailroom/open-sse-line! "/mail/sse" shapes-by-name {}
                                              {:on-open #(swap! opens conj %) :reconnect-after-ms 20})]
           (open! (first @opened))
           (is (= [false] @opens) "the first open")
           (is (false? (.-withCredentials (first @opened))))
           (testing "a network break: the browser reopens it itself, mailroom opens nothing"
             (break-network! (first @opened))
             (open! (first @opened))
             (is (= [false true] @opens))
             (is (= 1 (count @opened))))
           (testing "an HTTP error: nothing at once"
             (answer-with-http-error! (first @opened))
             (is (= 1 (count @opened))))
           (after-ms 60
                     (fn []
                       (is (= 2 (count @opened)) "a new line after :reconnect-after-ms")
                       (is (= "/mail/sse" (.-address (second @opened))))
                       (testing "and again after each failure"
                         (answer-with-http-error! (second @opened)))
                       (after-ms 60
                                 (fn []
                                   (is (= 3 (count @opened)))
                                   (open! (nth @opened 2))
                                   (is (= [false true true] @opens) "on-open runs again, again? true")
                                   (mailroom/close-sse-line! line)
                                   (is (= 2 (.-readyState (nth @opened 2))) "closed")
                                   (done))))))))

(deftest a-closed-line-stays-closed
  (async done
         (install-stand-in-event-source!)
         (let [line (mailroom/open-sse-line! "/mail/sse" shapes-by-name {}
                                             {:on-open nil :reconnect-after-ms 20})]
           (testing "closed while a reopen waits: it doesn't open"
             (answer-with-http-error! (first @opened))
             (mailroom/close-sse-line! line))
           (after-ms 60
                     (fn []
                       (is (= 1 (count @opened)))
                       (done))))))

(deftest an-sse-line-says-how-long-it-waits
  (let [problems (try (mailroom/open-sse-line! "/mail/sse" shapes-by-name {} {:on-open nil})
                      nil
                      (catch :default e (ex-data e)))]
    (is (= :mailroom/bad-options (:type problems)) "no :reconnect-after-ms: refused before the line opens")
    (is (contains? (:errors problems) :reconnect-after-ms))))
