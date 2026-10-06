(ns clojure-collider.osc.transmit
  "UDP OSC device. Mirrors ../cl-collider/osc/transmit.lisp osc-device / send-message / send-bundle / listener.
   Uses java.net.DatagramSocket + ConcurrentHashMap dispatch.
   No usocket/bordeaux-threads dep — plain Java."
  (:require [clojure-collider.osc.encode :as enc])
  (:import [java.net DatagramSocket DatagramPacket InetAddress]
           [java.util.concurrent ConcurrentHashMap]
           [java.util Arrays]))

(def ^:const max-datagram 65507)

(defrecord OscDevice [host port socket reply-table trace local-port listening-thread status])

(defn- make-socket
  ^DatagramSocket [local-host local-port]
  (let [sock (if (some? local-port)
               (DatagramSocket. (int local-port) (InetAddress/getByName (or local-host "0.0.0.0")))
               (DatagramSocket.))]
    (.setSoTimeout sock 0) ; blocking receive
    (try (.setSendBufferSize sock max-datagram) (catch Exception _))
    (try (.setReceiveBufferSize sock max-datagram) (catch Exception _))
    sock))

(defn osc-device
  "Create device. Args: host port & {:local-host \"0.0.0.0\" :local-port 0 :trace false}
   Mirrors CL (osc-device host port &key local-host local-port trace).
   :local-port 0 (default) binds an OS-assigned ephemeral port and starts the
   receive thread, matching CL server.lisp which passes :local-port 0
   (0 is truthy in CL, so upstream always listens). Pass :local-port nil
   explicitly for a send-only device with no listener."
  [host port & {:keys [local-host local-port trace] :or {local-host "0.0.0.0" local-port 0 trace false}}]
  (let [sock (make-socket local-host local-port)
        table (ConcurrentHashMap.)
        status (atom :running)
        dev (map->OscDevice {:host host :port (int port) :socket sock :reply-table table :trace trace :local-port (when (some? local-port) (.getLocalPort sock)) :listening-thread (atom nil) :status status})]
    (when (some? local-port)

      (let [thr (Thread.
                 (fn []
                   (let [buf (byte-array max-datagram)]
                     (loop []
                       (when (= @status :running)
                         (let [pkt (DatagramPacket. buf (alength buf))]
                           (try
                             (.receive sock pkt)
                             (let [len (.getLength pkt)
                                   data (Arrays/copyOfRange (.getData pkt) 0 len)
                                   decoded (try (enc/decode-bundle data) (catch Exception _ nil))]
                               (when decoded
                                 (doseq [[addr vals] (:messages decoded)]
                                   (let [handler (.get table addr)]
                                     (when handler
                                       (try (apply handler vals)
                                            (catch Exception e
                                              (println "OSC handler error for" addr ":" (.getMessage e))))))
                                   (when (and (= addr "/done")
                                              (= (first vals) "/quit"))
                                     (reset! status :not-running)))))
                             (catch java.net.SocketException e
                               (when (= @status :running)
                                 (println "OSC socket exception" (.getMessage e))))
                             (catch Exception e
                               (when @trace (println "OSC receive error" e))))
                           (when (= @status :running) (recur)))))))
                 )]
        (.setName thr "OSC device receive thread")
        (.setDaemon thr true)
        (.start thr)
        (reset! (:listening-thread dev) thr)))
    dev))

(defn add-osc-responder [^OscDevice dev cmd-name f]
  (.put ^ConcurrentHashMap (:reply-table dev) (str cmd-name) f))

(defn remove-osc-responder [^OscDevice dev cmd-name]
  (.remove ^ConcurrentHashMap (:reply-table dev) (str cmd-name)))

(defn send-message [^OscDevice dev & msg]
  ;; addr passes through raw: integer command ids (e.g. 9 = /s_new) must
  ;; reach the wire as int32 per sc-encode-address, not "9" (SC rejects).
  (let [addr (first msg)
        args (rest msg)
        data (apply enc/encode-message addr args)
        pkt (DatagramPacket. data (alength data) (InetAddress/getByName (:host dev)) (int (:port dev)))]
    (.send ^DatagramSocket (:socket dev) pkt)))

(defn send-bundle [^OscDevice dev timetag & messages]
  ;; messages are each [addr & args] vectors
  (let [tt (or timetag :now)
        data (enc/encode-bundle (vec messages) tt)
        pkt (DatagramPacket. data (alength data) (InetAddress/getByName (:host dev)) (int (:port dev)))]
    (.send ^DatagramSocket (:socket dev) pkt)))

(defn close-device [^OscDevice dev]
  (when (= @(:status dev) :running)
    (when @(:listening-thread dev)
      ;; unblock receive by sending /done /quit to self
      (let [sock ^DatagramSocket (:socket dev)
            local-port (.getLocalPort sock)
            msg (enc/encode-message "/done" "/quit")
            pkt (DatagramPacket. msg (alength msg) (InetAddress/getByName "127.0.0.1") (int local-port))]
        (try (.send sock pkt) (catch Exception _)))
      (try (.join ^Thread @(:listening-thread dev) 1000) (catch Exception _)))
    (try (.close ^DatagramSocket (:socket dev)) (catch Exception _))
    (reset! (:status dev) :not-running))
  dev)

(defn osc-trace [^OscDevice dev] (:trace dev))
