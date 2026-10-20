(ns clojure-collider.server.server-test
  (:require [clojure.test :refer [deftest is testing]]
            [clojure-collider.server.options :as opts]
            [clojure-collider.server.server :as server]))

(def live-host "127.0.0.1")
(def live-port 57120)
(def private-port 57711)
(deftest ntp-timetag-golden
  (testing "unix-scale times encode exact NTP bytes (no 2262 saturation)"
    ;; 1758000000 unix + 2208988800 epoch = 3966988800 = 0xEC737200
    (let [tt (server/ntp-timetag 1758000000.0 0.0)
          bb (doto (java.nio.ByteBuffer/allocate 8)
               (.order java.nio.ByteOrder/BIG_ENDIAN))]
      (.putLong bb tt)
      (is (= "ec73720000000000"
             (apply str (map #(format "%02x" (bit-and % 255)) (.array bb))))))
    (is (= "3966988800"
           (Long/toUnsignedString
            (unsigned-bit-shift-right (server/ntp-timetag 1758000000.0 0.0) 32))))))

(deftest construction
  (is (thrown? clojure.lang.ExceptionInfo
               (server/make-external-server "noport")))
  (let [s (server/make-external-server "u" :port 1)]
    (is (= 1000 (server/get-next-id s)))
    (is (= 1001 (server/get-next-id s)))
    (is (false? (server/boot? s)))))

(deftest just-connect-cycle
  (let [probe (try
                (let [s (server/make-external-server "test" :port live-port
                                                     :just-connect? true)]
                  (server/server-boot s)
                  s)
                (catch Exception e (println "SKIP live server tests:" (.getMessage e)) nil))]
    ;; try/finally: a mid-test failure must still /notify 0 (just-connect)
    ;; or kill scsynth (private), or client slots leak server-side.
    (when probe
      (try
        (binding [server/*s* probe]
          (testing "sync + status"
            (is (= probe (server/sync probe)))
            (is (do (server/server-status probe) true)))
          (testing "groups"
            (let [g (server/make-group :server probe)]
              (is (pos? (:id g)))
              ;; /n_go arrives async after /sync completes; poll for it
              (is (loop [i 0]
                    (cond (server/is-playing? g) true
                          (> i 100) false
                          :else (do (Thread/sleep 20) (recur (inc i))))))
              (server/group-free-all g)
              (server/node-free g)))
          (testing "control set/get"
            (server/control-set probe 0 0.5)
            (is (= 0.5 (server/control-get probe 0))))
          (testing "callback fires"
            (let [p (promise)]
              (server/callback (+ (server/now) 0.1) (fn [] (deliver p :cb)))
              (is (= :cb (deref p 3000 :timeout))))))
        (finally
          (when (server/boot? probe)
            (server/server-quit probe))
          (is (false? (server/boot? probe))))))))

(deftest private-boot-cycle
  (let [s (try
            (let [sv (server/make-external-server
                      "private" :port private-port
                      :options (opts/make-server-options {:num-output-bus 2 :num-input-bus 2}))]
              (server/server-boot sv)
              sv)
            (catch Exception e (println "SKIP private boot:" (.getMessage e)) nil))]
    (when s
      (try
        (testing "booted and synced"
          (is (true? (server/boot? s)))
          (is (= s (server/sync s)))
          (is (some? @(:sample-rate s))))
        (testing "buses reserved"
          (is (= 0 (:busnum (first (filter some? @(:audio-buses s)))))))
        (finally
          (when (server/boot? s)
            (server/server-quit s))
          (is (false? (server/boot? s))))))))
