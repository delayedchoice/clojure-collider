(ns clojure-collider.osc.transmit-test
  (:require [clojure.test :refer [deftest is testing]]
            [clojure-collider.osc.transmit :as tx]))

(def sc-host "127.0.0.1")
(def sc-port 57120) ; jack dummy from Phase 1 setup

;; NOTE: devices bind :local-port 0 (OS-assigned ephemeral) by default.
;; Never hardcode local ports here — fixed ports collided across runs
;; (TIME_WAIT / parallel checkouts) and caused intermittent bind failures.

(defn sc-alive? []
  (try
    (let [d (tx/osc-device sc-host sc-port)
          p (promise)]
      (tx/add-osc-responder d "/status.reply" (fn [& _] (deliver p true)))
      (tx/send-message d "/status")
      (let [ok (= true (deref p 1500 false))]
        (tx/close-device d)
        ok))
    (catch Exception _ false)))

(deftest device-lifecycle
  (let [d (tx/osc-device sc-host sc-port)]
    (is (= :running @(:status d)))
    (is (some? @(:listening-thread d)))
    (is (pos? (:local-port d)))
    (tx/close-device d)
    (is (= :not-running @(:status d)))))

(deftest send-only-device
  (let [d (tx/osc-device sc-host sc-port :local-port nil)]
    (is (= :running @(:status d)))
    (is (nil? @(:listening-thread d)))
    (tx/close-device d)
    (is (= :not-running @(:status d)))))

(deftest int-address-reaches-wire
  ;; 9 = /s_new as int32. send-message must not stringify it to "9"
  ;; (scsynth rejects string "9" with /fail Command not found).
  (let [recv (java.net.DatagramSocket.)
        port (.getLocalPort recv)
        _ (.setSoTimeout recv 2000)
        d (tx/osc-device sc-host port)]
    (try
      (tx/send-message d 9 "saw" (int 1) (int 0) (int 1))
      (let [buf (byte-array 65507)
            pkt (java.net.DatagramPacket. buf (alength buf))]
        (.receive recv pkt)
        (let [got (vec (take 4 (.getData pkt)))]
          (is (= [0 0 0 9] (mapv #(bit-and % 255) got)))))
      (finally
        (tx/close-device d)
        (.close recv)))))

(deftest osc-roundtrip-when-server-up
  (when (sc-alive?)
    (testing "/sync -1 roundtrip"
      (let [d (tx/osc-device sc-host sc-port)
            p (promise)]
        (tx/add-osc-responder d "/synced" (fn [id] (deliver p id)))
        (tx/send-message d "/sync" (int -1))
        (is (= -1 (deref p 1500 :timeout)))
        (tx/close-device d)))
    (testing "bundle /sync 42"
      (let [d (tx/osc-device sc-host sc-port)
            p (promise)]
        (tx/add-osc-responder d "/synced" (fn [id] (deliver p id)))
        (tx/send-bundle d :now ["/sync" (int 42)])
        (is (= 42 (deref p 1500 :timeout)))
        (tx/close-device d)))
    (testing "/notify"
      (let [d (tx/osc-device sc-host sc-port)
            p (promise)]
        (tx/add-osc-responder d "/done" (fn [& args] (when (= "/notify" (first args)) (deliver p args))))
        (tx/send-message d "/notify" (int 1))
        (let [v (deref p 1500 :timeout)]
          (is (not= :timeout v) "no /done for /notify")
          (when (not= :timeout v)
            (is (= "/notify" (first v)))))
        (tx/close-device d)))))
