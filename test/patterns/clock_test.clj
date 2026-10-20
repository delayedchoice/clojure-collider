(ns patterns.clock-test
  "Tests for patterns.clock. play-take goldens (pbind with pseq/pwhite,
   seeded) plus stop/end semantics on task maps. Debug backend only;
   no running scsynth, no live timing (clocks built with nil tempo-clock)."
  (:require [clojure.test :refer [deftest is testing]]
            [patterns.backend :as b]
            [patterns.clock :as c]
            [patterns.patterns :as p]
            [patterns.pbind :as pb]
            [patterns.util :as u]))

(deftest play-take-pbind-pseq-golden
  (let [db (b/make-debug-backend)
        takes (c/play-take (pb/pbind :note (p/pseq [60 62 64]) :dur 1) 5 db)]
    (testing "exact beats"
      (is (= [0.0 1.0 2.0 3.0 4.0] (mapv :beat takes))))
    (testing "exact params"
      (is (= [60 62 64 60 62] (mapv (comp :note :event) takes))))
    (testing "everything routed through the backend"
      (is (= takes (b/captured-events db))))))

(deftest play-take-seeded-pwhite-golden
  (u/seed-rng! 7)
  (let [expected (vec (repeatedly 4 #(u/rand-range 0.0 1.0)))]
    (u/seed-rng! 7)
    (let [db (b/make-debug-backend)
          takes (c/play-take (pb/pbind :note (p/pwhite 0.0 1.0) :dur 1) 4 db)]
      (testing "exact beats"
        (is (= [0.0 1.0 2.0 3.0] (mapv :beat takes))))
      (testing "exact seeded params"
        (is (= expected (mapv (comp :note :event) takes)))))))

(deftest play-take-stops-at-eop
  (let [db (b/make-debug-backend)
        takes (c/play-take (p/pseq [10 20] 1) 5 db)]
    (is (= [0.0 1.0] (mapv :beat takes)))
    (is (= [10 20] (mapv (comp :value :event) takes)))))

(deftest quant-next-grid
  (is (= 4.0 (c/quant-next 4 0.0)))
  (is (= 8.0 (c/quant-next 4 5.0)))
  (is (= 8.0 (c/quant-next 4 7.0)))
  (is (= 3.5 (c/quant-next nil 3.5))))

(deftest stop-end-semantics-on-task-maps
  (let [clock (c/make-clock nil)
        pat (pb/pbind :note 60 :dur 1)
        t (c/play clock pat)]
    (testing "play registers the task"
      (is (c/playing-p clock t))
      (is (= 1 (count @(:tasks clock)))))
    (testing "end keeps the task but clears loop?"
      (is (true? @(:loop? t)))
      (c/end clock t)
      (is (false? @(:loop? t)))
      (is (c/playing-p clock t)))
    (testing "play-or-stop stops a playing task"
      (is (nil? (c/play-or-stop clock t)))
      (is (not (c/playing-p clock t))))
    (testing "play-or-stop plays a stopped pattern"
      (let [t2 (c/play-or-stop clock pat)]
        (is (map? t2))
        (is (c/playing-p clock t2))))
    (testing "stop with no args clears all tasks"
      (c/play clock pat)
      (is (= 2 (count @(:tasks clock))))
      (c/stop clock)
      (is (= [] @(:tasks clock))))))

 (deftest keyword-play-stop-via-pdef
   (let [k ::clock-test-pat]
     (pb/pdef k (pb/pbind :note 60 :dur 1))
     (let [clock (c/make-clock nil)
           t (c/play clock k)]
       (is (c/playing-p clock t))
       (is (c/playing-p clock k))
       (c/stop clock k)
       (is (not (c/playing-p clock k)))
       (is (= [] @(:tasks clock))))))
