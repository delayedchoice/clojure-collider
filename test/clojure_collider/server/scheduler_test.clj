(ns clojure-collider.server.scheduler-test
  (:require [clojure.test :refer [deftest is testing]]
            [clojure-collider.server.scheduler :as sched]
            [clojure-collider.server.server :as server]))


(defn- fixed-sched
  ([] (fixed-sched 100.0 nil))
  ([now server] (sched/make-scheduler "test" server :timestamp-fn (fn [] now))))
(deftest sched-conveys-bindings
  (testing "worker thread sees the scheduling thread's dynamic bindings
            (like CL globals; callbacks use *s*-dependent fns)"
    (let [s (fixed-sched)
          seen (promise)
          marker (Object.)]
      (binding [server/*s* marker]
        (sched/sched-add s 50.0 (fn [] (deliver seen server/*s*))))
      (sched/sched-run s)
      (is (= marker (deref seen 2000 :timeout)))
      (sched/sched-stop s))))

(deftest clocks
  (is (pos? (sched/unix-time)))
  (is (pos? (sched/monotonic-time)))
  (is (nil? (sched/set-realtime-priority))))

(deftest sched-quant-math
  (let [s (fixed-sched 100.0 nil)]
    ;; t=100, q=4: 100 mod 4 = 0 -> rounds UP to 104 (upstream bias)
    (is (= 104.0 (sched/sched-quant s 4)))
    (is (= 104.0 (sched/sched-quant s 4 0.5)))
    (is (= :stop (sched/sched-status s)))))

(deftest sched-add-ahead
  (let [s (fixed-sched 100.0 {:sched-ahead 0.2})]
    (sched/sched-add s 101.0 (fn [] nil))
    (is (= 100.8 (:timestamp (.peek (:queue s)))))
    (sched/sched-clear s)
    (is (nil? (.peek (:queue s))))))

(deftest sched-run-stop
  (let [s (fixed-sched)
        log (atom [])
        _ (sched/sched-add s 50.0 (fn [] (swap! log conj :a)))
        _ (sched/sched-add s 51.0 (fn [] (swap! log conj :b)))]
    ;; both stored in the past (100.0 clock) -> fire on run
    (is (= :running (sched/sched-run s)))
    (let [ok (loop [i 0]
               (cond (= [:a :b] @log) true
                     (> i 100) false
                     :else (do (Thread/sleep 20) (recur (inc i)))))]
      (is ok)
      (sched/sched-stop s)
      (is (= :stop (sched/sched-status s))))))

(deftest sched-orders-by-time
  (let [s (fixed-sched)
        log (atom [])]
    (sched/sched-add s 10.0 (fn [] (swap! log conj 3)))
    (sched/sched-add s 5.0 (fn [] (swap! log conj 1)))
    (sched/sched-add s 7.0 (fn [] (swap! log conj 2)))
    (sched/sched-run s)
    (loop [i 0]
      (when (and (not= [1 2 3] @log) (< i 100))
        (Thread/sleep 20) (recur (inc i))))
    (is (= [1 2 3] @log))
    (sched/sched-stop s)))

(deftest tempo-math
  (let [c (sched/make-tempo-clock "clk" nil :bpm 60.0
                                  :timestamp-fn (fn [] 1000.0)
                                  :time-sync? false)]
    ;; base: 0 beats @ 1000.0s, 1 beat/sec
    (is (= 1005.0 (sched/beats-to-secs c 5.0)))
    (is (= 5.0 (sched/secs-to-beats c 1005.0)))
    (is (= 0.0 (sched/tempo-clock-beats c)))
    (sched/tempo-clock-set-bpm c 120.0)
    (is (= 120.0 (sched/tempo-clock-bpm c)))
    ;; re-anchored: beat 0 still maps to 1000.0, beat 2 -> 1001.0
    (is (< (Math/abs (- 1000.0 (sched/beats-to-secs c 0.0))) 1e-9))
    (is (< (Math/abs (- 1001.0 (sched/beats-to-secs c 2.0))) 1e-9))
    ;; b = beats(1000.0 + 0.2 ahead) @120bpm = 0.4 -> 0.4 + (4 - 0.4)
    (is (= 4.0 (sched/tempo-clock-quant c 4)))))

(deftest tempo-fires
  (let [c (sched/make-tempo-clock "clk" {:sched-ahead 0.0}
                                  :timestamp-fn sched/unix-time
                                  :time-sync? false)
        p (promise)]
    (sched/tempo-clock-run c)
    ;; beat far in the past -> due immediately
    (sched/tempo-clock-add c -100.0 (fn [] (deliver p :fired)))
    (is (= :fired (deref p 2000 :timeout)))
    (sched/tempo-clock-stop c)
    (is (= :stop (sched/sched-status c)))))
