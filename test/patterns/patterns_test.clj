(ns patterns.patterns-test
  (:require [clojure.test :refer [deftest is testing]]
            [patterns.patterns :as p]
            [patterns.util :as u]))

(deftest core-stream
  (testing "as-pstream coercions"
    (is (= [5 5 5] (p/next-n 5 3)))
    (is (= [7 8 7 8] (p/next-n [7 8] 4)))
    (let [s (p/as-pstream (p/pseq [1 2] 1))]
      (is (identical? s (p/as-pstream s)))))
  (testing "pnext!/ppeek/peek-n"
    (let [s (p/as-pstream (p/pseq [1 2 3] 1))]
      (is (= 1 (p/ppeek s)))
      (is (= 1 (p/ppeek s)))
      (is (= [1 2 3] (p/peek-n s 3)))
      (is (= 1 (p/pnext! s)))
      (is (= [2 3] (p/peek-n s 10)))
      (is (= 2 (p/pnext! s)))
      (is (= 3 (p/pnext! s)))
      (is (p/eop? (p/pnext! s)))
      (is (p/eop? (p/pnext! s)))
      (is (= [] (p/peek-n s 5)))))
  (testing "take-events mirrors next-n"
    (is (= [5 6 7 5] (p/take-events (p/pseq [5 6 7] 2) 4)))))

(deftest pseq-pser
  (testing "pseq cycles whole list per repeat"
    (is (= [5 6 7 5 6 7] (p/next-n (p/pseq [5 6 7] 2) 7)))
    (is (= [5 6 7 5 6 7] (p/next-n (p/pseq [5 6 7] 2) 100))))
  (testing "pser counts total outputs"
    (is (= [5 6] (p/next-n (p/pser [5 6 7] 2) 3)))
    (is (= [5 6] (p/next-n (p/pser [5 6 7] 2) 100))))
  (testing "offset shifts the index"
    (is (= [6 7 5] (p/next-n (p/pseq [5 6 7] 1 1) 3)))))

(deftest series-geom
  (testing "pseries arithmetic"
    (is (= [1 3 5 7] (p/next-n (p/pseries 1 2 4) 5)))
    (is (= [0 1 2] (p/next-n (p/pseries) 3))))
  (testing "pseries* linear interpolation"
    (let [out (p/next-n (p/pseries* 0 10 16) 16)]
      (is (= 16 (count out)))
      (is (= 0 (first out)))
      (is (= 10 (last out)))
      (is (= 2/3 (second out)))))
  (testing "pgeom geometric"
    (is (= [1 2 4 8] (p/next-n (p/pgeom 1 2 4) 5))))
  (testing "pgeom* exponential interpolation"
    (let [out (p/next-n (p/pgeom* 1 100 8) 8)]
      (is (= 8 (count out)))
      (is (= 1 (first out)))
      (is (< (Math/abs (- 100.0 (double (last out)))) 1e-6)))))

(deftest random-choice
  (testing "prand membership + length"
    (u/seed-rng! 7)
    (let [out (p/next-n (p/prand [1 2 3] 50) 60)]
      (is (= 50 (count out)))
      (is (every? #{1 2 3} out))))
  (testing "pxrand never repeats immediately"
    (u/seed-rng! 7)
    (let [out (p/next-n (p/pxrand [1 2 3] 200) 300)]
      (is (= 200 (count out)))
      (is (every? #{1 2 3} out))
      (is (not-any? true? (map = out (rest out))))))
  (testing "pwrand membership + weights bias"
    (u/seed-rng! 11)
    (let [out (p/next-n (p/pwrand [1 2 3] [7 0 0] 20) 30)]
      (is (= 20 (count out)))
      (is (every? #{1} out))))
  (testing "pshuf yields permutation per repeat"
    (u/seed-rng! 3)
    (let [out (p/next-n (p/pshuf [1 2 3] 2) 10)]
      (is (= 6 (count out)))
      (is (= {1 2, 2 2, 3 2} (frequencies out)))
      (is (= (set [1 2 3]) (set (take 3 out))))
      (is (= (set [1 2 3]) (set (drop 3 out)))))))

(deftest seeded-goldens
  (testing "pwhite exact seeded sequence"
    (u/seed-rng! 42)
    (is (= [7 4 8 5 3 8 1 2 10 6 0 7 2 0 2 0]
           (p/next-n (p/pwhite 0 10 16) 16))))
  (testing "pexprand exact seeded sequence"
    (u/seed-rng! 42)
    (is (= [4.539996043049161 4.140113775349764 1.9002093420769222 1.7792083577973214]
           (p/next-n (p/pexprand 1.0 8.0 4) 4))))
  (testing "pwhite float range"
    (u/seed-rng! 1)
    (let [out (p/next-n (p/pwhite 0.0 1.0 100) 100)]
      (is (every? #(and (<= 0.0 %) (< % 1.0)) out))))
  (testing "pbrown stays clamped"
    (u/seed-rng! 5)
    (let [out (p/next-n (p/pbrown 0 10 1 200) 200)]
      (is (= 200 (count out)))
      (is (every? #(and (<= 0 %) (<= % 10)) out))))
  (testing "pgauss smoke"
    (u/seed-rng! 9)
    (let [out (p/next-n (p/pgauss 0.0 1.0 50) 50)]
      (is (= 50 (count out)))
      (is (every? number? out)))))

(deftest embed-repeat
  (testing "pn embeds full subpattern"
    (is (= [1 2 1 2 1 2] (p/next-n (p/pn (p/pseq [1 2] 1) 3) 10))))
  (testing "repeat-each repeats each value (upstream pr)"
    (is (= [0 0 1 1 2 2] (p/next-n (p/repeat-each (p/pseries 0 1 3) 2) 10)))
    (is (= [1] (p/next-n (p/repeat-each (p/pseq [1 2] 1) (p/pseq [1 0] 1)) 5)))
    (is (= [7 7 8 8] (p/next-n (p/repeat-each (p/pseq [7 8] 1) (fn [_] 2)) 6))
        "fn repeats called per value"))
  (testing "pfunc calls per output"
    (let [i (atom 0)]
      (is (= [1 2 3] (p/next-n (p/pfunc #(swap! i inc) 3) 5)))))
  (testing "plazy rebuilds per repeat"
    (is (= [1 2 1 2] (p/next-n (p/plazy #(p/pseq [1 2] 1) 2) 6)))))

(deftest reorder
  (testing "protate rotates finite pattern"
    (is (= [4 5 1 2 3] (p/next-n (p/protate (p/pseq [1 2 3 4 5] 1) 2) 10))))
  (testing "place indexes sublists per cycle"
    (is (= [1 2 3 1 2 4 1 2 5]
           (p/next-n (p/place [1 2 [3 4 5]] 3) 12)))))

(deftest termination
  (testing "finite lengths eop and stay eop"
    (doseq [pat [(p/pseq [1] 1) (p/pser [1 2] 1) (p/prand [1] 1)
                 (p/pxrand [1 2] 1) (p/pwhite 0 1 1) (p/pseries 0 1 1)
                 (p/pgeom 1 2 1) (p/pn (p/pseq [1] 1) 1)
                 (p/repeat-each (p/pseq [1] 1) 1) (p/pshuf [1] 1)
                 (p/protate (p/pseq [1] 1) 0) (p/place [1] 1)]]
      (let [s (p/as-pstream pat)]
        (is (not (p/eop? (p/pnext! s))))
        (is (p/eop? (p/pnext! s)))
        (is (p/eop? (p/pnext! s))))))
  (testing ":inf default never eops within bounded pulls"
    (is (= 100 (count (p/next-n (p/pseq [1 2]) 100))))
    (is (= 50 (count (p/next-n (p/pwhite 0 1) 50))))
    (is (= 30 (count (p/next-n (p/pseries 0 1) 30))))))

(deftest lazy-seqs-walk-without-realizing
  (testing "infinite seqs (Overtone repeatedly style) stream forever"
    (let [n (atom 0)
          s (p/as-pstream (repeatedly #(swap! n inc)))]
      (is (= [1 2 3 4 5] (vec (repeatedly 5 #(p/pnext! s)))))
      (is (= 5 @n))))
  (testing "finite lazy seqs walk once, then eop (vectors/lists still cycle)"
    (let [s (p/as-pstream (map inc [10 20]))]
      (is (= 11 (p/pnext! s)))
      (is (= 21 (p/pnext! s)))
      (is (p/eop? (p/pnext! s)))))
  (testing "quoted lists keep cycling"
    (is (= [1 2 1 2] (p/next-n '(1 2) 4)))))

(deftest prand-embeds-patterns
  (testing "picked patterns play through (SC list-pattern embedding)"
    (let [got (p/next-n (p/prand [(p/pwhite -1.0 -0.5 1) (p/pwhite 0.5 1.0 1)]) 6)]
      (is (= 6 (count got)))
      (is (every? number? got))
      (is (every? #(or (< % -0.4) (> % 0.4)) got)))))
