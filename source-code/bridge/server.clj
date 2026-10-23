(ns bridge.server
  "http-kit, serving one Ring handler, every request on its own virtual
   thread."
  (:require
   [org.httpkit.server :as http-kit])
  (:import
   (java.util.concurrent ExecutorService Executors TimeUnit)))

(defn- write-to-log [log level event]
  (fn [message error]
    (log level event (cond-> {:bridge/http-kit-message (str message)}
                       (some? error) (assoc :error error)))))

(defn start-server!
  "Starts http-kit with the settings: where it listens, what it takes, what
   it says; its own errors and warnings go to the options' :log. The
   server keeps the two stop waits for stop-server!. Returns
   {:port the-port …}."
  [ring-handler {:keys [ip port proxy-protocol server-header max-body-bytes max-header-line-bytes max-ws-message-bytes
                        stop-wait-ms stop-wait-after-cut-ms]} {:keys [log]}]
  (let [workers (Executors/newVirtualThreadPerTaskExecutor)
        server  (http-kit/run-server ring-handler {:ip                   ip
                                                   :port                 port
                                                   :proxy-protocol       proxy-protocol
                                                   :server-header        server-header
                                                   :max-body             max-body-bytes
                                                   :max-line             max-header-line-bytes
                                                   :max-ws               max-ws-message-bytes
                                                   :worker-pool          workers
                                                   ;; http-kit's own errors and warnings go to the app's
                                                   ;; log; its per-request events are not logged
                                                   :error-logger         (write-to-log log :error :bridge/server-error)
                                                   :warn-logger          (write-to-log log :warn :bridge/server-warning)
                                                   :ring-async?          false
                                                   :legacy-return-value? false})]
    {:port                            (http-kit/server-port server)
     :bridge/http-kit               server
     :bridge/workers                workers
     :bridge/stop-wait-ms           stop-wait-ms
     :bridge/stop-wait-after-cut-ms stop-wait-after-cut-ms}))

(defn stop-server!
  "Stops taking requests and lets running ones finish, up to :stop-wait-ms;
   then cuts the ones left (their threads are interrupted) and waits up to
   :stop-wait-after-cut-ms for those threads to end. Returns whether every
   request's thread had ended; nil when the server was already stopping."
  [{:bridge/keys [http-kit stop-wait-ms stop-wait-after-cut-ms] :as server}]
  ;; http-kit's stop returns once it has waited and cut (it shuts the
  ;; workers down, then interrupts them); its promise is delivered when its
  ;; own thread has ended
  (when-let [stopped (http-kit/server-stop! http-kit {:timeout stop-wait-ms})]
    (let [cut-at                   (System/currentTimeMillis)
          ^ExecutorService workers (:bridge/workers server)]
      (deref stopped stop-wait-after-cut-ms nil)
      (.shutdown workers)
      (.awaitTermination workers (max 0 (- (+ cut-at stop-wait-after-cut-ms) (System/currentTimeMillis))) TimeUnit/MILLISECONDS))))
