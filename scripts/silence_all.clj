;; Find every live scsynth on this machine and silence it - no REPL state,
;; no bindings, no registry needed. Run while sound is playing:
;;   clojure -M scripts/silence_all.clj
;; Prints the responding ports, then /clearSched + /g_deepFree 0 to each.
;; Servers stay up (unlike killall); truly foreign boxes are out of reach.
(require '[clojure-collider.osc.encode :as enc]
         '[clojure-collider.server.server :as server])
(import '(java.net DatagramSocket DatagramPacket InetAddress))

(def scan-ports (concat (range 57100 57201) [57711]))
(def localhost (InetAddress/getByName "127.0.0.1"))

(defn- status-probe [sock port]
  (try
    (let [data (enc/encode-message "/status")
          pkt (DatagramPacket. data (alength data) localhost (int port))]
      (.send sock pkt)
      (let [buf (byte-array 65535)
            rp (DatagramPacket. buf (alength buf))]
        (.receive sock rp)
        (= port (.getPort rp))))
    (catch java.net.SocketTimeoutException _ false)
    (catch Exception _ false)))

(let [sock (DatagramSocket.)]
  (.setSoTimeout sock 150)
  (try
    (let [live (filterv #(status-probe sock %) scan-ports)]
      (println :live-servers live)
      (doseq [p live]
        (try (server/panic-port! p)
             (catch Exception e (println :spray-failed p (.getMessage e)))))
      (println :silenced live)
      (shutdown-agents)
      (System/exit 0))
    (finally (.close sock))))
