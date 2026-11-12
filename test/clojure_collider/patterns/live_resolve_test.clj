(ns clojure-collider.patterns.live-resolve-test
  "Tasks re-resolve their pdef every step: re-pdef mid-play and the running
   task picks up the new pattern without stop/play, even for streams that
   never end. Withdrawing the name keeps the old stream (stopping stays
   explicit); a looping finite pattern restarts while registered and ends
   once withdrawn."
  (:require [clojure.test :refer [deftest is testing]]
            [patterns.clock :as clock]
            [pattern :as p]))

(defn- wait-for [pred ms]
  (loop [t 0]
    (cond (pred) true
          (>= t ms) false
          :else (do (Thread/sleep 50) (recur (+ t 50))))))

(defn- task-source [clock]
  (:source (first @(:tasks clock))))

(deftest re-pdef-swaps-running-source
  (let [clock (clock/start-clock-loop :tempo 60)]
    (try
      (p/pdef :live-probe (p/pbind :x 1 :dur 0.2))
      (clock/play clock :live-probe)
      (testing "task starts on the registered pattern"
        (is (true? (wait-for #(identical? (task-source clock)
                                                   (p/find-pdef :live-probe))
                                       2000))))
      (testing "re-pdef swaps the running source, no restart"
        (p/pdef :live-probe (p/pbind :x 2 :dur 0.2))
        (is (= 1 (count @(:tasks clock))))
        (is (true? (wait-for #(identical? (task-source clock)
                                                   (p/find-pdef :live-probe))
                                       3000))))
      (finally
        (clock/stop clock :all)))))

(deftest exhausted-loop-restarts-while-registered
  (let [clock (clock/start-clock-loop :tempo 60)]
    (try
      (p/pdef :live-loop (p/pbind :x 1 :dur 0.2 :pfin 3))
      (clock/play clock :live-loop)
      (let [s0 (do (wait-for #(some? (:stream (first @(:tasks clock)))) 2000)
                   (:stream (first @(:tasks clock))))]
        (testing "finite pattern cycles into a fresh stream"
          (is (some? s0))
          (is (true? (wait-for #(and (= 1 (count @(:tasks clock)))
                                              (not (identical? s0 (:stream (first @(:tasks clock))))))
                                         3000)))))
      (finally
        (clock/stop clock :all)))))

(deftest withdrawn-finite-pattern-ends-task
  (let [clock (clock/start-clock-loop :tempo 60)]
    (try
      (p/pdef :live-fin (p/pbind :x 1 :dur 0.2 :pfin 3))
      (clock/play clock :live-fin)
      (p/pdef :live-fin nil)
      (testing "exhaustion with no pattern to rebuild from drops the task"
        (is (true? (wait-for #(empty? @(:tasks clock)) 4000))))
      (finally
        (clock/stop clock :all)))))
