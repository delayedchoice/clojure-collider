(ns patterns.event-test
  "Events: defaults, pitch/amp/dur chains, combine, multichannel split."
  (:require [clojure.test :refer [deftest is testing]]
            [patterns.util :as u]
            [patterns.conversions :as conv]
            [patterns.scales :as scales]
            [patterns.event :as ev :refer [*event* e]]))

(defn- approx?
  [a b eps]
  (< (abs (double (- a b))) eps))

(deftest empty-defaults
  (let [m (ev/event)]
    (testing "identity keys"
      (is (= :note (ev/event-value m :type)))
      (is (= :default (ev/event-value m :instrument)))
      (is (= 1 (ev/event-value m :group)))
      (is (= 0 (ev/event-value m :out)))
      (is (= 0 (ev/event-value m :pan)))
      (is (= 1 (ev/event-value m :tempo)))
      (is (nil? (ev/event-value m :beat))))
    (testing "amp with db cross-default"
      (is (= 0.5 (ev/event-value m :amp)))
      (is (approx? (ev/event-value m :db) -6.020599913279624 1e-9)))
    (testing "dur chain"
      (is (= 1 (ev/event-value m :dur)))
      (is (= 1 (ev/event-value m :delta)))
      (is (= 1 (ev/event-value m :duration)))
      (is (= 0.8 (ev/event-value m :legato)))
      (is (approx? (ev/event-value m :sustain) 0.8 1e-12)))
    (testing "pitch chain"
      (is (= 440.0 (double (ev/event-value m :freq))))
      (is (= 69 (ev/event-value m :midinote)))
      (is (= 0 (ev/event-value m :note)))
      (is (= 5 (ev/event-value m :degree)))
      (is (= 0 (ev/event-value m :root)))
      (is (= 5 (ev/event-value m :octave)))
      (is (= :major (ev/event-value m :scale)))
      (is (= 1 (ev/event-value m :rate))))
    (testing "unknown keys read raw"
      (is (nil? (ev/event-value m :nope))))))

(deftest pitch-precedence
  (testing "explicit freq wins over everything"
    (is (= 880 (ev/event-value (ev/event :freq 880 :midinote 60 :note 0) :freq))))
  (testing "midinote beats note/degree"
    (let [f (ev/event-value (ev/event :midinote 60 :degree 0) :freq)]
      (is (approx? f 261.6255653005986 1e-9))))
  (testing "note resolves through conversions (shares numbering with degree)"
    (let [f (ev/event-value (ev/event :note 0) :freq)]
      (is (approx? f (conv/midinote-freq 72) 1e-9)))
    (let [f (ev/event-value (ev/event :note 0 :octave 4) :freq)]
      (is (approx? f (conv/midinote-freq 60) 1e-9))))
  (testing "degree agrees with scales + note 0 in major"
    (let [m (ev/event :degree 0)
          expected (conv/midinote-freq
                    (scales/degree-midinote 0 :root 0 :octave 5 :scale :major))]
      (is (approx? (ev/event-value m :freq) expected 1e-9))
      (is (approx? (ev/event-value m :freq)
                   (ev/event-value (ev/event :note 0) :freq) 1e-9))))
  (testing "rate honors base-freq"
    (is (= 2 (ev/event-value (ev/event :freq 880) :rate)))
    (is (= 1 (ev/event-value (ev/event :freq 220 :base-freq 220) :rate)))))

(deftest amp-db-conversion
  (testing "db derives amp"
    (is (approx? (ev/event-value (ev/event :db -6) :amp) 0.5011872336272722 1e-9)))
  (testing "amp derives db"
    (is (approx? (ev/event-value (ev/event :amp 1.0) :db) 0.0 1e-9)))
  (testing "explicit amp wins over db"
    (is (= 0.25 (ev/event-value (ev/event :amp 0.25 :db -6) :amp)))))

(deftest legato-sustain
  (testing "sustain follows legato * dur"
    (is (approx? (ev/event-value (ev/event :dur 2) :sustain) 1.6 1e-12))
    (is (= 1.0 (ev/event-value (ev/event :dur 2 :legato 0.5) :sustain))))
  (testing "legato derives from explicit sustain"
    (is (= 0.5 (ev/event-value (ev/event :dur 2 :sustain 1.0) :legato))))
  (testing "explicit sustain wins"
    (is (= 3.0 (ev/event-value (ev/event :dur 2 :legato 0.5 :sustain 3.0) :sustain))))
  (testing "delta tracks dur, duration divides by tempo"
    (is (= 2 (ev/event-value (ev/event :dur 2) :delta)))
    (is (= 1 (ev/event-value (ev/event :dur 2 :tempo 2) :duration)))))

(deftest combine-semantics
  (testing "later events win, keys merge"
    (let [m (ev/combine-events (ev/event :amp 0.1 :pan -1) (ev/event :amp 0.9))]
      (is (= 0.9 (:amp m)))
      (is (= -1 (:pan m)))))
  (testing "eop poisons"
    (is (= u/eop (ev/combine-events (ev/event :a 1) u/eop)))
    (is (= u/eop (ev/combine-events u/eop))))
  (testing "nil-valued key aborts to nil"
    (is (nil? (ev/combine-events (ev/event :a 1) (ev/event :a nil)))))
  (testing "empty combine is an empty event"
    (is (= {} (ev/combine-events))))
  (testing "copy is equal but independent"
    (let [a (ev/event :x 1 :y [1 2])
          c (ev/copy-event a)]
      (is (ev/event-equal a c))
      (is (not (identical? a c))))))

(deftest multichannel-split
  (testing "max length wins, scalars repeat, short lists wrap"
    (let [parts (ev/split-event-by-lists (ev/event :a 1 :b [10 20 30]))]
      (is (= 3 (count parts)))
      (is (= [{:a 1 :b 10} {:a 1 :b 20} {:a 1 :b 30}] parts))))
  (testing "uneven lists wrap the short one"
    (let [parts (ev/split-event-by-lists (ev/event :b [1 2] :c [7 8 9]))]
      (is (= [1 2 1] (mapv :b parts)))
      (is (= [7 8 9] (mapv :c parts)))))
  (testing "empty event splits to one empty event"
    (is (= [{}] (ev/split-event-by-lists (ev/event)))))
  (testing "vector key resolves element-wise"
    (is (= [440.0 880.0]
           (mapv double (ev/event-value (ev/event :freq [440 880]) :freq))))))

(deftest equality-helpers
  (testing "event-equal ignores derivation, compares resolution"
    (is (ev/event-equal (ev/event :db -6) (ev/event :db -6)))
    (is (not (ev/event-equal (ev/event :a 1) (ev/event :a 2))))
    (is (not (ev/event-equal (ev/event :a 1) (ev/event :a 1 :b 2)))))
  (testing "every-event-equal needs equal length"
    (is (ev/every-event-equal [(ev/event :a 1)] [(ev/event :a 1)]))
    (is (not (ev/every-event-equal [(ev/event :a 1)]
                                   [(ev/event :a 1) (ev/event :a 1)]))))
  (testing "differing keys"
    (is (= [:a] (ev/events-differing-keys (ev/event :a 1 :b 2)
                                          (ev/event :a 9 :b 2))))))

(deftest e-sugar
  (testing "e reads the bound event"
    (binding [*event* (ev/event :amp 0.9 :midinote 60)]
      (is (= 0.9 (e :amp)))
      (is (approx? (e :freq) 261.6255653005986 1e-9))))
  (testing "unbound event yields nil"
    (binding [*event* nil]
      (is (nil? (e :amp))))))
