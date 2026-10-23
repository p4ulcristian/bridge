(ns bridge.server
  "Jetty 12, through rj9a (ring-jetty9-adapter, Jetty without its servlet
   layer), serving one Ring handler, every request on its own virtual
   thread. Jetty's own platform threads (accepting, selecting) are a pool
   by the settings' :threads. In front of the app's handler, in order:
   Jetty's GracefulHandler (a stop lets running requests finish), its
   SizeLimitHandler (a body over :max-body-bytes is refused, 413), and the
   settings' Server header when there is one."
  (:require
   [bridge.sse :as sse]
   [ring.adapter.jetty9 :as jetty])
  (:import
   (java.util.concurrent ConcurrentHashMap TimeoutException)
   (java.util.concurrent.atomic AtomicBoolean)
   (org.eclipse.jetty.http HttpException)
   (org.eclipse.jetty.io EndPoint)
   (org.eclipse.jetty.server Connector ConnectionFactory Handler Handler$Wrapper HttpConfiguration
                             HttpConnectionFactory ProxyConnectionFactory Request Response Server ServerConnector)
   (org.eclipse.jetty.server.handler GracefulHandler SizeLimitHandler)
   (org.eclipse.jetty.util Callback VirtualThreads)
   (org.eclipse.jetty.util.thread QueuedThreadPool)))

(def ^:private http-1-1 "HTTP/1.1")

(defn- make-proxy-protocol-factory
  "nil for :disable. :optional: Jetty's PROXY detector, which hands a
   connection without the PROXY line to plain HTTP. :enable: the same, but
   a connection without it is closed."
  [proxy-protocol]
  (case proxy-protocol
    :disable  nil
    :optional (ProxyConnectionFactory. http-1-1)
    :enable   (proxy [ProxyConnectionFactory] [http-1-1]
                (nextProtocol [_connector ^EndPoint end-point _buffer]
                  (.close end-point)))))

(defn- make-connector
  "The one plain HTTP/1.1 connector: where it listens, how big a request's
   line and headers may be, how long a connection may stay silent, the
   PROXY protocol before HTTP when the settings say so. Jetty's version is
   not sent; the Server header is the settings' own."
  [^Server server {:keys [ip port proxy-protocol max-request-head-bytes idle-timeout-ms]}]
  (let [http-config (doto (HttpConfiguration.)
                      (.setRequestHeaderSize (int max-request-head-bytes))
                      (.setIdleTimeout (long idle-timeout-ms))
                      (.setSendServerVersion false)
                      (.setSendXPoweredBy false))
        factories   (remove nil? [(make-proxy-protocol-factory proxy-protocol) (HttpConnectionFactory. http-config)])]
    (doto (ServerConnector. server ^"[Lorg.eclipse.jetty.server.ConnectionFactory;" (into-array ConnectionFactory factories))
      (.setHost ip)
      (.setPort (int port))
      (.setIdleTimeout (long idle-timeout-ms)))))

(defn- handle-on-a-virtual-thread
  "Hands the request to the app's handler on a virtual thread of its own,
   always: Jetty runs most requests on one by itself, but not the first of
   a connection that came through the PROXY protocol detector. Every
   answer carries the Server header, when the settings have one. While
   the handler runs (rj9a writes the answer on the same thread), its sse
   lines can find Jetty's request, to see the browser leave."
  [^Handler handler ^String server-header]
  (proxy [Handler$Wrapper] [handler]
    (handle [^Request request ^Response response ^Callback callback]
      (when server-header
        (.put (.getHeaders response) "Server" server-header))
      (let [handle! (fn []
                      (.set ^ThreadLocal sse/current-jetty-request request)
                      (try
                        (.handle handler request response callback)
                        (catch Throwable e
                          (.failed callback e))
                        (finally
                          (.remove ^ThreadLocal sse/current-jetty-request))))]
        (if (.isVirtual (Thread/currentThread))
          (handle!)
          (Thread/startVirtualThread ^Runnable handle!))
        true))))

(defn- wrap-jetty-handler [{:keys [max-body-bytes server-header]}]
  (fn [^Handler ring-handler]
    (doto (GracefulHandler.)
      (.setHandler (doto (SizeLimitHandler. (long max-body-bytes) -1)
                     (.setHandler ^Handler (handle-on-a-virtual-thread ring-handler server-header)))))))

(defn- make-thread-pool
  "Jetty's own threads, a pool by :threads; every request's work runs on a
   virtual thread of its own."
  [{{:keys [min max idle-timeout-ms]} :threads} stop-wait-after-cut-ms]
  (doto (QueuedThreadPool. (int max) (int min) (int idle-timeout-ms))
    (.setName "bridge")
    (.setStopTimeout stop-wait-after-cut-ms)
    (.setVirtualThreadsExecutor (VirtualThreads/getDefaultVirtualThreadsExecutor))))

(defn- answer-in-tracked-thread
  "The app's handler, its thread kept among the running ones while it runs
   (a stop cuts those left), each request knowing the server's sse lines.
   A failure no step caught is written to the log, :bridge/server-error, and
   answered 500; Jetty's own (a body over the limit) goes back to Jetty,
   which answers it."
  [ring-handler ^ConcurrentHashMap running lines log]
  (fn [request]
    (let [thread (Thread/currentThread)]
      (.put running thread true)
      (try
        (ring-handler (assoc request :bridge/sse-lines lines))
        (catch Throwable e
          ;; HttpException is an interface: Jetty's runtime ones carry it
          (when (instance? HttpException e)
            (throw e))
          (log :error :bridge/server-error {:error e})
          {:status 500 :headers {"content-type" "text/plain"} :body "Something went wrong."})
        (finally
          (.remove running thread))))))

(defn start-server!
  "Starts Jetty with the settings: where it listens, what it takes, how
   long a silent connection lives, its threads; failures no step caught go
   to the options' :log. Returns {:port the-port …}."
  [ring-handler {:keys [ip port max-ws-message-bytes idle-timeout-ms stop-wait-ms stop-wait-after-cut-ms] :as settings} {:keys [log]}]
  (let [running (ConcurrentHashMap.)
        lines   (sse/make-sse-lines)
        server  (jetty/run-jetty (answer-in-tracked-thread ring-handler running lines log)
                                 {:host                       ip
                                  :port                       port
                                  :join?                      false
                                  :thread-pool                (make-thread-pool settings stop-wait-after-cut-ms)
                                  :wrap-jetty-handler         (wrap-jetty-handler settings)
                                  :ws-max-idle-time           idle-timeout-ms
                                  :ws-max-frame-size          max-ws-message-bytes
                                  :ws-max-text-message-size   max-ws-message-bytes
                                  :ws-max-binary-message-size max-ws-message-bytes
                                  ;; rj9a's own connector is replaced by bridge's (the PROXY
                                  ;; protocol first, when it is on); the app stops the server,
                                  ;; not a JVM shutdown hook
                                  :configurator               (fn [^Server server]
                                                                (.setStopAtShutdown server false)
                                                                (.setStopTimeout server stop-wait-ms)
                                                                (.setConnectors server (into-array Connector [(make-connector server settings)])))})]
    {:port                          (.getLocalPort ^ServerConnector (first (.getConnectors ^Server server)))
     :bridge/jetty                  server
     :bridge/running                running
     :bridge/sse-lines              lines
     :bridge/log                    log
     :bridge/stopping               (AtomicBoolean.)
     :bridge/stop-wait-after-cut-ms stop-wait-after-cut-ms}))

(defn- is-timeout? [^Throwable e]
  (some #(instance? TimeoutException %) (take-while some? (iterate #(.getCause ^Throwable %) e))))

(defn stop-server!
  "Closes the open sse lines (each browser reopens its own, on another
   server), stops taking requests and lets running ones finish, up to
   :stop-wait-ms; then Jetty closes the connections left, and the requests
   still running are cut (their threads interrupted), waited for up to
   :stop-wait-after-cut-ms. Returns whether every request's thread had
   ended; nil when the server was already stopping."
  [{:bridge/keys [jetty running sse-lines log stopping stop-wait-after-cut-ms]}]
  (when (.compareAndSet ^AtomicBoolean stopping false true)
    (sse/close-every-sse-line! sse-lines)
    (try (.stop ^Server jetty)
         (catch Exception e
           ;; past :stop-wait-ms Jetty stops anyway and says it timed out: that is the cut
           (when-not (is-timeout? e)
             (log :error :bridge/server-error {:error e}))))
    (let [until (+ (System/currentTimeMillis) stop-wait-after-cut-ms)]
      (run! #(.interrupt ^Thread %) (.keySet ^ConcurrentHashMap running))
      (loop []
        (cond (.isEmpty ^ConcurrentHashMap running)          true
              (>= (System/currentTimeMillis) until)          false
              :else                                          (do (Thread/sleep 5) (recur)))))))
