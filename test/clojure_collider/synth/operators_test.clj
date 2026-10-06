(ns clojure-collider.synth.operators-test
  "operators.lisp parity: numeric fallback, constant folding,
   UGen construction, multichannel expansion, core overloads."
  (:require [clojure.test :refer [deftest is]]
            [clojure-collider.synth.ugen :as ugen]
            [clojure-collider.synth.synthdef :as synthdef]
            [clojure-collider.synth.operators :as op]
            [clojure-collider.ugens.osc :as osc]))

(defn- build [name f]
  (binding [ugen/*synthdef* (synthdef/make-synthdef name)]
    (let [res (f)
          s @(doto ugen/*synthdef* synthdef/build-synthdef)]
      [res s])))

(defn- ar [freq]
  (osc/sin-osc:ar freq 0 1 0))

(deftest numeric-fallback
  (is (= -5 (op/neg 5)))
  (is (= 5 (op/add 2 3)))
  (is (= 6 (op/+ 1 2 3)))
  (is (= 0 (op/+)))
  (is (= 1 (op/*)))
  (is (= -5 (op/- 5)))
  (is (= 3 (op/- 5 2)))
  (is (= 6 (op/* 2 3)))
  (is (= 3.0 (op// 6 2)))
  (is (= 0.5 (op// 2)))
  (is (< (Math/abs (- 440.0 (op/midicps 69))) 1e-9))
  (is (< (Math/abs (- 1.0 (op/dbamp 0))) 1e-9))
  (is (true? (op/== 1 1)))
  (is (true? (op/< 1 2 3)))
  (is (= 1 (op/min 1 2 3)))
  (is (= 3 (op/abs -3)))
  (is (= 1 (op/mod 7 3)))
  (is (= 2 (op/inc 1)))
  (is (= 2 (op/min 2 3))))
(deftest constant-folding
  ;; folding is pure value plumbing; no synthdef needed.
  (let [u (osc/sin-osc:ar 440 0 1 0)]
    (is (identical? u (op/add 0 u)))
    (is (identical? u (op/add u 0)))
    (is (identical? u (op/+ u 0)))
    (is (identical? u (op/mul 1 u)))
    (is (identical? u (op/mul u 1)))
    (is (= 0.0 (op/mul 0 u)))
    (is (identical? u (op/madd u 1.0 0.0)))))

(deftest ugen-construction
  (let [[u s] (build "ops" (fn []
                              (let [a (ar 440)
                                    b (ar 441)]
                                [(op/add a b) (op/neg a) (op/mul a b)])))]
    (let [[sum neg prod] u]
      (is (= "BinaryOpUGen" (:name sum)))
      (is (= 0 (:special-index sum)))
      (is (= :audio (:rate sum)))
      (is (= "UnaryOpUGen" (:name neg)))
      (is (= 0 (:special-index neg)))
      (is (= 2 (:special-index prod))))
    ;; returned values are real, but the unconsumed op tails go first and
    ;; orphaned SinOscs follow (fixpoint cascade = upstream recursion)
    (is (= [] (:children s)))))

(deftest overload-builds-ugens
  (let [[u _] (build "over" (fn []
                               (let [a (ar 440)]
                                 [(op/+ a 1) (op/- a) (op/< a 5)])))]
    (is (= "BinaryOpUGen" (:name (first u))))
    (is (= "UnaryOpUGen" (:name (second u))))
    (is (= 8 (:special-index (nth u 2))))))

(deftest binary-rate-picks-audio
  (let [[r _] (build "rate" (fn []
                               (let [a (ar 440)
                                     k (osc/sin-osc:kr 440 0 1 0)]
                                 (op/+ a k))))]
    (is (= :audio (:rate r)))))

(deftest multichannel-expansion
  (let [[res _] (build "multi" (fn []
                                  (let [a (ar 440)
                                        b (ar 441)]
                                    (op/+ [a b] 5))))]
    (is (= 2 (count res)))
    (is (every? #(= "BinaryOpUGen" (:name %)) res))))

(deftest composite-helpers
  (let [[r s] (build "comp" (fn []
                               (let [a (ar 440)]
                                 [(op/madd a 2.0 3.0)
                                  (op/range a 0 1)
                                  (op/mix [a a])])))]
    (is (= "MulAdd" (:name (first r))))
    (is (= 3 (count (:inputs (first r)))))
    (is (= "BinaryOpUGen" (:name (last r)))) ; mix of 2 -> sum -> add
    (is (pos? (count (:children s))))))

(deftest plumbing
  (is (= [5] (op/bubble 5)))
  (is (= [[1 3] [2 4]] (op/flop [[1 2] [3 4]])))
  (is (= [[1 2] [3]] (op/clump [1 2 3] 2)))
  (is (= [7 7] (op/dup 7)))
  (is (= [0 1] (op/dup identity 2)))
  (is (= 2.0 (op/mean [1 2 3])))
  (is (= 24 (op/product [2 3 4]))))
