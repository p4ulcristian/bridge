(ns mailroom.server
  "http-kit, serving one Ring handler, every request on its own virtual
   thread."
  (:require
   [org.httpkit.server :as http-kit])
  (:import
   (java.lang System$Logger System$Logger$Level)
   (java.util.concurrent ExecutorService Executors TimeUnit)))

(def ^:private ^System$Logger logger (System/getLogger "mailroom"))

(defn- log-at [level]
  (fn [message error]
    (.log logger ^System$Logger$Level level ^String (str message) ^Throwable error)))

(defn start-server!
  "Starts http-kit with the settings: where it listens, what it takes, what
   it says. The server keeps the two stop waits for stop-server!. Returns
   {:port the-port …}."
  [ring-handler {:keys [ip port proxy-protocol server-header max-body-bytes max-header-line-bytes max-ws-message-bytes
                        stop-wait-ms stop-wait-after-cut-ms]}]
  (let [workers (Executors/newVirtualThreadPerTaskExecutor)
        server  (http-kit/run-server ring-handler {:ip                   ip
                                                   :port                 port
                                                   :proxy-protocol       proxy-protocol
                                                   :server-header        server-header
                                                   :max-body             max-body-bytes
                                                   :max-line             max-header-line-bytes
                                                   :max-ws               max-ws-message-bytes
                                                   :worker-pool          workers
                                                   ;; http-kit's own errors and warnings go to the same
                                                   ;; log as a handler's failures; its per-request events
                                                   ;; are not logged
                                                   :error-logger         (log-at System$Logger$Level/ERROR)
                                                   :warn-logger          (log-at System$Logger$Level/WARNING)
                                                   :ring-async?          false
                                                   :legacy-return-value? false})]
    {:port                            (http-kit/server-port server)
     :mailroom/http-kit               server
     :mailroom/workers                workers
     :mailroom/stop-wait-ms           stop-wait-ms
     :mailroom/stop-wait-after-cut-ms stop-wait-after-cut-ms}))

(defn stop-server!
  "Stops taking requests and lets running ones finish, up to :stop-wait-ms;
   then cuts the ones left (their threads are interrupted) and waits up to
   :stop-wait-after-cut-ms for those threads to end. Returns whether every
   request's thread had ended; nil when the server was already stopping."
  [{:mailroom/keys [http-kit stop-wait-ms stop-wait-after-cut-ms] :as server}]
  ;; http-kit's stop returns once it has waited and cut (it shuts the
  ;; workers down, then interrupts them); its promise is delivered when its
  ;; own thread has ended
  (when-let [stopped (http-kit/server-stop! http-kit {:timeout stop-wait-ms})]
    (let [cut-at                   (System/currentTimeMillis)
          ^ExecutorService workers (:mailroom/workers server)]
      (deref stopped stop-wait-after-cut-ms nil)
      (.shutdown workers)
      (.awaitTermination workers (max 0 (- (+ cut-at stop-wait-after-cut-ms) (System/currentTimeMillis))) TimeUnit/MILLISECONDS))))
