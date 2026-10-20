(ns patterns.util-test
  "Seeded goldens + edge cases for patterns.util RNG helpers."
  (:require [clojure.test :refer [deftest is testing]]
            [patterns.util :as u]))

(defn- seeded [n] (doto (java.util.Random.) (.setSeed n)))

(deftest eop-marker
  (testing "eop keyword identity and predicate"
    (is (= :patterns.util/eop u/eop))
    (is (u/eop? u/eop))
    (is (not (u/eop? :eop)))
    (is (not (u/eop? nil)))))

(deftest seeded-rand-range
  (testing "exact first 3 rand-range values with seed 42"
    (binding [u/*rng* (seeded 42)]
      (is (= [7.275636800328681 6.832234717598454 3.0871945533265976]
             (mapv (fn [_] (u/rand-range 0.0 10.0)) (range 3))))))
  (testing "stays within [lo, hi)"
    (binding [u/*rng* (seeded 7)]
      (doseq [_ (range 100)]
        (let [v (u/rand-range -5.0 5.0)]
          (is (and (<= -5.0 v) (< v 5.0))))))))

(deftest seeded-rand-exp
  (testing "exact first 3 rand-exp values with seed 42"
    (binding [u/*rng* (seeded 42)]
      (is (= [4.539996043049161 4.140113775349764 1.9002093420769222]
             (mapv (fn [_] (u/rand-exp 1.0 8.0)) (range 3))))))
  (testing "rejects zero or mixed-sign bounds"
    (is (thrown? AssertionError (u/rand-exp 0 5)))
    (is (thrown? AssertionError (u/rand-exp -2 3)))))

(deftest seeded-rand-gauss
  (testing "exact first 3 rand-gauss values with seed 42"
    (binding [u/*rng* (seeded 42)]
      (is (= [1.1419053154730547 0.9194079489827879 -0.9498666368908959]
             (mapv (fn [_] (u/rand-gauss 0.0 1.0)) (range 3)))))))

(deftest seeded-rand-int-range
  (testing "exact first 6 rand-int-range values with seed 42"
    (binding [u/*rng* (seeded 42)]
      (is (= [7 4 8 5 3 8]
             (mapv (fn [_] (u/rand-int-range 0 10)) (range 6))))))
  (testing "inclusive bounds"
    (binding [u/*rng* (seeded 1)]
      (is (= 5 (u/rand-int-range 5 5))))))

(deftest seed-rng!
  (testing "re-seeding reproduces the sequence"
    (binding [u/*rng* (java.util.Random.)]
      (u/seed-rng! 42)
      (let [a (u/rand-range 0.0 1.0)]
        (u/seed-rng! 42)
        (is (= a (u/rand-range 0.0 1.0)))))))

(deftest transpose-rerange
  (testing "transpose by semitones/octaves"
    (is (= 880.0 (u/transpose 440.0 12)))
    (is (= 880.0 (u/transpose 440.0 0 1))))
  (testing "rerange linear remap (cf. upstream: 64/127)"
    (is (< (Math/abs (- (/ 64.0 127.0) (u/rerange 64 [0 127] [0 1]))) 1e-9))))
