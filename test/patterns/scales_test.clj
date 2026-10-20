(ns patterns.scales-test
  (:require [clojure.test :refer [deftest is testing]]
            [patterns.scales :as sc]))

(deftest registry-counts
  (testing "table counts match upstream scales.lisp (35 tunings, 108 scales, 19 chords)"
    (is (= 35 (count (sc/all-tunings))))
    (is (= 108 (count (sc/all-scales))))
    (is (= 19 (count (sc/all-chords))))
    (testing "user definitions register and resolve"
      (sc/define-scale :test-scale-x [0 2 5])
      (is (= [0 2 5] (sc/scale-notes :test-scale-x)))
      (is (= 109 (count (sc/all-scales)))))))

(deftest major-scale-notes
  (is (= [0 2 4 5 7 9 11] (sc/scale-notes :major)))
  (is (= [0 2 4 5 7 9 11] (sc/scale-notes :ionian)))
  (is (= [0 2 3 5 7 8 10] (sc/scale-notes :aeolian))))

(deftest tuning-basics
  (testing "et12 tuning shape"
    (is (= "Equal Temperament 12" (sc/tuning-name :et12)))
    (is (= (vec (range 12)) (sc/tuning-pitches :et12)))
    (is (= 12 (sc/tuning-steps-per-octave :et12)))
    (is (= 2 (sc/tuning-octave-ratio :et12))))
  (testing "non-12 tunings report their own step counts"
    (is (= 43 (sc/tuning-steps-per-octave :partch)))
    (is (= 24 (sc/tuning-steps-per-octave :et24)))
    (is (= 12 (sc/tuning-steps-per-octave :bohlen-pierce)))
    (is (= 3 (sc/tuning-octave-ratio :bohlen-pierce))))
  (testing "scalar ratios were midi-converted at load"
    (let [p (sc/tuning-pitches :pythagorean)]
      (is (= 12 (count p)))
      (is (< (Math/abs (double (first p))) 1e-9)))))

(deftest alias-resolution
  (testing "explicit aliases"
    (is (= "Equal Temperament 12" (sc/tuning-name (sc/tuning :et12))))
    (is (= "5-Limit Just Intonation" (sc/tuning-name (sc/tuning :just))))
    (is (= [0 2 3 5 7 8 10] (sc/scale-notes :minor)))
    (is (= "Natural Minor" (sc/scale-name :minor)))
    (is (= "Major 7th" (sc/chord-name :maj7)))
    (is (= "Diminished 7th" (sc/chord-name :dim7)))
    (is (= "Minor Triad" (sc/chord-name :mintri))))
  (testing "generated abbreviation aliases"
    (is (= "Major" (sc/scale-name :maj)))
    (is (= "Minor Pentatonic" (sc/scale-name :pentatonic))))
  (testing "string lookups normalize like keywords"
    (is (= (sc/scale :major) (sc/scale "Major")))
    (is (= (sc/tuning :et12) (sc/tuning "Equal Temperament 12"))))
  (testing "unknown keys return nil"
    (is (nil? (sc/scale :no-such-scale)))
    (is (nil? (sc/tuning :no-such-tuning)))
    (is (nil? (sc/chord :no-such-chord)))))

(deftest scale-midinotes-test
  (is (= [60 62 64 65 67 69 71] (sc/scale-midinotes :major :c 5)))
  (is (= [48 50 52 53 55 57 59] (sc/scale-midinotes :major)))
  (is (= [60 62 64 65 67 69 71] (sc/scale-midinotes "Major" "C" 5))))

(deftest degree-midinote-test
  (testing "positional [degree scale root octave]"
    (is (= 72 (long (sc/degree-midinote 0 :major 0 5))))
    (is (= 60 (long (sc/degree-midinote 0 :major 0 4))))
    (is (= 84 (long (sc/degree-midinote 7 :major 0 5))))
    (is (= 76 (long (sc/degree-midinote 2 :major 0 5)))))
  (testing "keyword args"
    (is (= 72 (long (sc/degree-midinote 0 :scale :major :root 0 :octave 5)))))
  (testing "matches the upstream formula note + root + (octave - 4) * 12 + 60"
    (doseq [deg (range 7) oct [4 5]]
      (is (= (+ (nth [0 2 4 5 7 9 11] deg) (* (- oct 4) 12) 60)
             (long (sc/degree-midinote deg :major 0 oct)))))))

(deftest chord-test
  (is (= [0 4 7] (sc/chord-notes :major-triad)))
  (is (= [0 3 7] (sc/chord-notes :minor-triad)))
  (is (= [0 3 6 9] (sc/chord-notes :diminished-7th)))
  (is (= [72 76 79] (mapv long (sc/chord-midinotes :major-triad))))
  (is (= :major (sc/chord-scale :major-triad)))
  (is (= [0 2 4] (sc/chord-indexes :major-triad))))

(deftest scale-descriptor-test
  (is (= [0 2 4 5 7 9 11] (:notes (sc/scale-descriptor :major))))
  (is (= 12 (:steps-per-octave (sc/scale-descriptor :major))))
  (is (= 24 (:steps-per-octave (sc/scale-descriptor :rast))))
  (is (= 43 (:steps-per-octave (sc/scale-descriptor :partch-otonality-1))))
  (is (nil? (sc/scale-descriptor :no-such-scale))))
