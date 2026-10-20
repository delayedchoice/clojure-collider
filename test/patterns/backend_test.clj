(ns patterns.backend-test
  "Tests for patterns.backend. Debug backend + reserved-key stripping only;
   no running scsynth required."
  (:require [clojure.test :refer [deftest is testing]]
            [patterns.backend :as b]
            [patterns.event :as ev]
            [clojure-collider.synth.sdef :as sdef]
            [clojure-collider.ugens.osc :as osc]
            [clojure-collider.ugens.in-out :as io]))

(deftest debug-backend-captures-beats-and-events
  (let [db (b/make-debug-backend)
        e1 (ev/event :note 60 :dur 1)
        e2 (ev/event :note 62 :dur 0.5)]
    (b/play-event db e1 0.0)
    (b/play-event db e2 1.0)
    (is (= [{:beat 0.0 :event e1}
            {:beat 1.0 :event e2}]
           (b/captured-events db)))))

(deftest debug-backend-clear
  (let [db (b/make-debug-backend)]
    (b/play-event db (ev/event :note 60) 0.0)
    (is (= 1 (count (b/captured-events db))))
    (b/clear-captured! db)
    (is (= [] (b/captured-events db)))))

(deftest rest-is-silence
  (testing "cl-collider backend performs no synth for :rest (nil server ok)"
    (is (nil? (b/play-event (b/->ClColliderBackend nil)
                            (ev/event :type :rest)
                            0.0))))
  (testing "debug backend still records rests"
    (let [db (b/make-debug-backend)
          e (ev/event :type :rest)]
      (b/play-event db e 3.0)
      (is (= [{:beat 3.0 :event e}] (b/captured-events db))))))

(deftest reserved-keys-stripped-from-controls
  (let [args (apply hash-map
                    (b/control-args (ev/event :instrument :default :note 60
                                              :freq 440 :amp 0.5 :dur 2
                                              :delta 2 :type :note :legato 0.8
                                              :beat 4 :tempo 1)))]
    (testing "sounding params survive"
      (is (= 0.5 (:amp args)))
      (is (= 440 (:freq args))))
    (doseq [k [:type :dur :delta :legato :quant :play-quant :beat :tempo
               :timing-offset :group :out :backend :instrument :duration
               :sustain]]
      (testing (str k " stripped")
        (is (not (contains? args k)))))))

(deftest freq-resolved-when-absent
   (let [args (apply hash-map (b/control-args (ev/event :midinote 60)))]
     (is (< (Math/abs (- 261.63 (:freq args))) 0.01))))

(deftest gated-synth-predicate
  (testing "gate-0 release only for synths exposing gate (no /n_set on dead nodes)"
    (sdef/defsynth :gate-probe-gated [gate 1 freq 440]
      (io/out:ar 0 (osc/sin-osc:ar freq 0 0.1)))
    (sdef/defsynth :gate-probe-free [freq 440]
      (io/out:ar 0 (osc/sin-osc:ar freq 0 0.1)))
    (is (true? (b/gated-synth? :gate-probe-gated)))
    (is (false? (b/gated-synth? :gate-probe-free)))
    (is (false? (b/gated-synth? :no-such-synth-anywhere)))))
