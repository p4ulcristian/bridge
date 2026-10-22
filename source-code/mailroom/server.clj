(ns mailroom.server
  "http-kit, serving one Ring handler, every request on its own virtual
   thread."
  (:require
   [org.httpkit.server :as http-kit])
  (:import
   (java.util.concurrent ExecutorService Executors TimeUnit)))

(defn start-server!
  "Starts http-kit at the settings' :ip and :port, port 0 picks a free one;
   the server keeps :stop-wait-ms for stop-server!. Returns {:port the-port …}."
  [ring-handler {:keys [ip port stop-wait-ms]}]
  (let [workers (Executors/newVirtualThreadPerTaskExecutor)
        server  (http-kit/run-server ring-handler {:ip                   ip
                                                   :port                 port
                                                   :worker-pool          workers
                                                   :legacy-return-value? false})]
    {:port                  (http-kit/server-port server)
     :mailroom/http-kit     server
     :mailroom/workers      workers
     :mailroom/stop-wait-ms stop-wait-ms}))

(defn stop-server!
  "Stops taking requests, lets running ones finish (up to the settings'
   :stop-wait-ms), then stops."
  [{:mailroom/keys [stop-wait-ms] :as server}]
  ;; the deref waits a second past http-kit's own timeout, so a stop that
  ;; hangs can't hold the caller for ever
  (deref (http-kit/server-stop! (:mailroom/http-kit server) {:timeout stop-wait-ms}) (+ stop-wait-ms 1000) nil)
  (let [^ExecutorService workers (:mailroom/workers server)]
    (.shutdown workers)
    (.awaitTermination workers 1 TimeUnit/SECONDS)))
