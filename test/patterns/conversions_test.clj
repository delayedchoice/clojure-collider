(ns patterns.conversions-test
  "Round-trips, clamp behavior, and degree/scale delegation for conversions."
  (:require [clojure.test :refer [deftest is testing]]
            [patterns.conversions :as c]))

(defn- approx= [a b eps]
  (< (Math/abs (- (double a) (double b))) eps))

(deftest polarity
  (testing "bipolar/unipolar round-trip"
    (is (approx= 0.75 (c/bipolar-unipolar 0.5) 1e-12))
    (is (approx= 0.5 (c/unipolar-bipolar 0.75) 1e-12))))

(deftest volume-round-trip
  (testing "amp 0.5 <-> db"
    (let [db (c/amp-db 0.5)]
      (is (approx= -6.020599913279624 db 1e-9))
      (is (approx= 0.5 (c/db-amp db) 1e-12)))))

(deftest pitch-round-trips
  (testing "midinote 69 <-> 440.0"
    (is (approx= 440.0 (c/midinote-freq 69) 1e-9))
    (is (approx= 69.0 (c/freq-midinote 440.0) 1e-9)))
  (testing "freq round-trips through midinote"
    (is (approx= 261.6255653005986 (c/midinote-freq 60) 1e-9))
    (is (approx= 60.0 (c/freq-midinote (c/midinote-freq 60)) 1e-9)))
  (testing "rate round-trips"
    (is (approx= 2.0 (c/freq-rate 880.0 440.0) 1e-12))
    (is (approx= 880.0 (c/rate-freq 2.0 440.0) 1e-12))
    (is (approx= 69.0 (c/rate-midinote 1.0) 1e-9))
    (is (approx= 1.0 (c/midinote-rate 69) 1e-12)))
  (testing "ratio/midi round-trip"
    (is (approx= 12.0 (c/ratio-midi 2.0) 1e-12))
    (is (approx= 2.0 (c/midi-ratio 12) 1e-12))))

(deftest octave-conversions
  (testing "octave <-> midinote <-> freq"
    (is (= 60 (c/octave-midinote 4)))
    (is (= 4 (c/midinote-octave 60)))
    (is (= 5 (c/midinote-octave 72)))
    (is (approx= (c/midinote-freq 60) (c/octave-freq 4) 1e-12))
    (is (= 4 (c/freq-octave 440.0)))))

(deftest note-conversions
  (testing "numeric and named notes"
    (is (= 60.0 (c/note-midinote 0)))
    (is (= 60.0 (c/note-midinote :c4)))
    (is (= :c4 (c/midinote-note 60)))
    (is (= :a4 (c/midinote-note 69)))
    (is (= 4 (c/note-octave :c4)))
    (is (= :c5 (c/octave-note 5)))
    (is (= :c4 (c/freq-note 261.6255653005986)))
    (is (approx= 440.0 (c/note-freq :a4) 1e-9)))
  (testing "chromatic index round-trip"
    (is (= 0 (c/note-chromatic-index :c)))
    (is (= :c (c/chromatic-index-note 0)))
    (is (= :c# (c/chromatic-index-note 1)))
    (is (= 1 (c/note-chromatic-index :db)))
    (is (approx= 440.0 (c/chromatic-index-freq 9 4) 1e-9))
    (is (= 9 (c/freq-chromatic-index 440.0)))))

(deftest dur-conversions
  (testing "dur/duration/freq at explicit tempo"
    (is (= 2.0 (c/dur-duration 4 2)))
    (is (= 4.0 (c/duration-dur 2 2)))
    (is (= 2.0 (c/dur-time 4 2)))
    (is (= 4.0 (c/time-dur 2 2)))
    (is (= 0.5 (c/dur-freq 4 2)))
    (is (= 4.0 (c/freq-dur 0.5 2)))))

(deftest degree-built-in-major
  (testing "degrees resolve against built-in :major"
    (is (= 60.0 (c/degree-midinote 0)))
    (is (= 62.0 (c/degree-midinote 1)))
    (is (= 72.0 (c/degree-midinote 7)))
    (is (= 0 (c/degree-note 0 :scale :major)))
    (is (= 0 (c/note-degree :c :scale :major)))
    (is (= 0 (c/midinote-degree 60 :scale :major)))
    (is (= 4 (c/degree-octave 3)))
    (is (= 0 (c/octave-degree 5)))))

(deftest degree-injected-lookup
  (testing "custom scale via per-call :lookup"
    (let [minor {:notes [0 2 3 5 7 8 10] :steps-per-octave 12 :octave-ratio 2.0}
          lookup (fn [k] (when (= k :minor) minor))]
      (is (= 63.0 (c/degree-midinote 2 :scale :minor :lookup lookup)))
      (is (= 3 (c/degree-note 2 :scale :minor :lookup lookup)))
      (is (= 2 (c/note-degree 3 :scale :minor :lookup lookup)))))
  (testing "unknown scale without lookup throws (isolated from global wiring)"
    (binding [c/*scale-lookup* nil]
      (is (thrown? clojure.lang.ExceptionInfo
                   (c/degree-midinote 0 :scale :dorian))))))

(deftest midi-clamp
  (testing "truncate and clamp to 0..127"
    (is (= 60 (c/midi-truncate-clamp 60.9)))
    (is (= 127 (c/midi-truncate-clamp 200)))
    (is (= 0 (c/midi-truncate-clamp -3)))
    (is (= 100 (c/midi-truncate-clamp 100.7 100)))))
