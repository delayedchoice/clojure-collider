(ns clojure-collider.ugens.env-gen-test
  "Env builder + EnvGen parity with ugens/EnvGen.lisp."
  (:require [clojure.test :refer [deftest is testing]]
            [clojure-collider.ugens.env-gen :as e]
            [clojure-collider.synth.ugen :as ugen]))

(deftest shape-numbers
  (is (= 1 (e/env-shape-number :lin)))
  (is (= 2 (e/env-shape-number :exp)))
  (is (= 5 (e/env-shape-number 2.5)))
  (is (thrown? clojure.lang.ExceptionInfo (e/env-shape-number :bogus)))
  (testing "control proxies count as custom curves (FM-2 index envelopes)"
    (is (= 5 (e/env-shape-number
              (ugen/->ProxyOutput nil :control :bipolar 0 0 -1 nil nil))))))

(deftest env-array-layout
  ;; [init num-segs release loop seg...], segs [level time shape curve]
  (let [[arr] (e/make-env-array-from-env (e/env [0 1 0] [1 1]))]
    (is (= [0 2 -99 -99 1 1 1 0 0 1 1 0] (:values arr))))
  (testing "release node threads through"
    (let [[arr] (e/make-env-array-from-env (e/env [0 1 0] [1 1] :lin 1))]
      (is (= [0 2 1 -99 1 1 1 0 0 1 1 0] (:values arr)))))
  (testing "all-UGen list wraps singly"
    (let [g (e/env-gen:kr (e/env [0 1 0] [1 1]))
          [arr] (e/make-env-array-from-env [g])]
      (is (= 1 (count (:values arr)))))))

(deftest env-gen-inputs
  (let [g (e/env-gen:kr (e/env [0 1 0] [1 1]) :act :free)]
    (is (= "EnvGen" (:name g)))
    (is (= :control (:rate g)))
    ;; gate level-scale level-bias time-scale act + 12 env values
    (is (= 17 (count (:inputs g))))
    (is (= [1 1 0 1 2] (take 5 (:inputs g))))
    (is (= [1 1 1 0 0 1 1 0] (take-last 8 (:inputs g))))))

(deftest ugen-levels-stay-symbolic
  ;; var-lag feeds UGen levels; they must survive as inputs, not constants
  (let [lvl (ugen/map->UGen {:name "X" :rate :control :inputs [] :synth-index -1
                             :special-index 0 :signal-range :bipolar :synthdef nil
                             :antecedents [] :descendants [] :width-first-antecedents []
                             :check-fn nil :ugen-class :ugen})
        [arr] (e/make-env-array-from-env (e/env [lvl 1] [0.1]))]
    (is (identical? lvl (first (:values arr))))))
