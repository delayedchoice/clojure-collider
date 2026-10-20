(ns patterns.pbind-test
  "pbind/pmono/prest/pb/pdef: event yields, child advance + end, *event*."
  (:require [clojure.test :refer [deftest is testing]]
            [patterns.pbind :as pb]
            [patterns.patterns :as p]
            [patterns.event :as event]))

(defn- seq-stream
  "Finite stream yielding VALS left to right, then eop. Stands in for
  catalog patterns (pseq etc.) owned by sibling slices."
  [vals]
  (p/->Stream (atom {:vals (vec vals)})
              (fn [{:keys [vals] :as st}]
                (if (empty? vals)
                  :patterns.util/eop
                  [(first vals) (assoc st :vals (vec (rest vals)))]))))

(defn- run
  "Build a pbind stream for PATTERN and take up to N events."
  [pattern n]
  (p/next-n (p/as-pstream pattern) n))

(deftest const-pairs-yield-event-maps
  (is (= [{:a 1 :b 2} {:a 1 :b 2} {:a 1 :b 2}]
         (run (pb/pbind :a 1 :b 2) 3))))

(deftest children-advance-independently
  (testing "uneven children: whole pbind ends when ANY child eops"
    (is (= [{:a 1 :b :x} {:a 2 :b :y}]
           (run (pb/pbind :a (seq-stream [1 2])
                          :b (seq-stream [:x :y :z]))
                5)))))

(deftest fn-values-see-partial-event
  (testing "fn of the event map itself"
    (let [[ev] (run (pb/pbind :dur 2 :half (fn [e] (/ (e :dur) 2))) 1)]
      (is (= {:dur 2 :half 1} ev))))
  (testing "dynamic *event* bound to the partial map during pulls"
    (let [[ev] (run (pb/pbind :dur 3 :dyn (fn [_] (:dur event/*event*))) 1)]
      (is (= 3 (:dyn ev))))))

(deftest prest-marks-rest
  (testing "direct prest value"
    (let [[ev] (run (pb/pbind :degree (pb/prest 2)) 1)]
      (is (= {:degree 2 :type :rest} ev))))
  (testing "prest nested inside a child stream"
    (is (= [{:degree 0} {:degree 2 :type :rest} {:degree 3}]
           (run (pb/pbind :degree (seq-stream [0 (pb/prest 2) 3])) 5))))
  (testing "default prest value is 1"
    (is (= 1 (:value (pb/prest))))))

(deftest embed-splices-maps
  (testing "plain map spliced at build"
    (let [[ev] (run (pb/pbind :a 1 :embed {:b 2}) 1)]
      (is (= {:a 1 :b 2} ev))))
  (testing "streamed map merged per step"
    (let [evs (run (pb/pbind :a (seq-stream [1 2])
                             :embed (seq-stream [{:b 10} {:b 20}])) 5)]
      (is (= [{:a 1 :b 10} {:a 2 :b 20}] evs)))))

(deftest pmono-adds-instrument-and-type
  (let [pat (pb/pmono :default :a 1)]
    (is (= :patterns.patterns/pbind (:ptype pat)))
    (let [[ev] (run pat 1)]
      (is (= :default (:instrument ev)))
      (is (= :mono (:type ev)))
      (is (= 1 (:a ev))))))

(deftest pb-registers-and-retrieves
  (testing "multi-pair form builds a pbind and registers it"
    (pb/pb :pbind-test-pattern :a 1 :b 2)
    (let [found (pb/find-pdef :pbind-test-pattern)]
      (is (= :patterns.patterns/pbind (:ptype found)))
      (is (= found (pb/pdef-pattern :pbind-test-pattern)))
      (is (= found (pb/pdef :pbind-test-pattern)))))
  (testing "single map arg registers directly"
    (let [m (pb/pbind :solo 9)]
      (pb/pb :pbind-test-single m)
      (is (= m (pb/find-pdef :pbind-test-single)))))
  (testing "all-pdefs reflects registrations"
    (is (contains? (pb/all-pdefs) :pbind-test-pattern))))

(deftest bad-input-throws
  (testing "odd arg count"
    (is (thrown? clojure.lang.ExceptionInfo (pb/pbind :a))))
  (testing "deferred wrap keys throw with a :deferred marker"
    (try
      (pb/pbind :pfor 2 :a 1)
      (is false "should have thrown")
      (catch clojure.lang.ExceptionInfo ex
        (is (true? (:deferred (ex-data ex))))))))
