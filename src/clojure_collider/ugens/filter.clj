(ns clojure-collider.ugens.filter
  "Filter UGens needed by the play/defsynth tail. Mirrors the matching
   ugens/Filter.lisp defugens: hpz-1, changed, var-lag (via var-lag-new,
   which builds an EnvGen graph for non-:lin warp exactly like upstream)."
  (:require [clojure-collider.synth.ugen :as ugen]
            [clojure-collider.synth.operators :as op]
            [clojure-collider.ugens.env-gen :as e]
            [clojure-collider.ugens.osc :as osc]))

(defn hpz-1:ar
  ([in] (hpz-1:ar in 1.0 0.0))
  ([in mul] (hpz-1:ar in mul 0.0))
  ([in mul add]
   (op/madd (ugen/multinew (fn [cls i]
                             (ugen/ugen-new "HPZ1" :audio cls
                                            ugen/check-same-rate-as-first-input
                                            :bipolar i))
                           'pure-ugen in)
            mul add)))

(defn hpz-1:kr
  ([in] (hpz-1:kr in 1.0 0.0))
  ([in mul] (hpz-1:kr in mul 0.0))
  ([in mul add]
   (op/madd (ugen/multinew (fn [cls i]
                             (ugen/ugen-new "HPZ1" :control cls
                                            ugen/check-same-rate-as-first-input
                                            :bipolar i))
                           'pure-ugen in)
            mul add)))
(defn changed:ar [in & [threshold]]
  (op/> (op/abs (hpz-1:ar in)) (or threshold 0.0)))

(defn changed:kr [in & [threshold]]
  (op/> (op/abs (hpz-1:kr in)) (or threshold 0.0)))

(defn- var-lag-new [rate in time curvature warp start]
  (let [start (if (= :false start) in start)
        curve (when (keyword? warp) (get e/shape-table warp))
        curvature (if curve 0 curvature)
        curve (if curve curve warp)]
    (if (not= curve 1)
      (let [arrays (mapv #(update % :values assoc 6 curve 7 curvature)
                         (e/make-env-array-from-env (e/env [start in] [time] warp)))
            changed-fn (if (= rate :audio) changed:ar changed:kr)
            impulse-fn (if (= rate :audio) osc/impulse:ar osc/impulse:kr)
            trig (op/+ (changed-fn in) (impulse-fn 0))
            trig (if (= :scalar (ugen/rate time)) trig (op/+ trig (changed:kr time)))]
        (if (= rate :audio)
          (e/env-gen:ar arrays :gate trig)
          (e/env-gen:kr arrays :gate trig)))
      (ugen/ugen-new "VarLag" rate 'pure-ugen
                     ugen/check-same-rate-as-first-input :bipolar in time start))))

(defn var-lag:ar
  ([in] (var-lag:ar in 0.1 0 5 nil 1.0 0.0))
  ([in time] (var-lag:ar in time 0 5 nil 1.0 0.0))
  ([in time curvature warp start mul add]
   (op/madd
    (ugen/multinew (fn [_ i t c w s] (var-lag-new :audio i t c w s))
                   nil in time curvature warp (if start start :false))
    mul add)))

(defn var-lag:kr
  ([in] (var-lag:kr in 0.1 0 5 nil 1.0 0.0))
  ([in time] (var-lag:kr in time 0 5 nil 1.0 0.0))
  ([in time curvature warp start mul add]
   (op/madd
    (ugen/multinew (fn [_ i t c w s] (var-lag-new :control i t c w s))
                   nil in time curvature warp (if start start :false))
    mul add)))

(defn lag:ar
  ([in] (lag:ar in 0.1 1.0 0.0))
  ([in lag-time] (lag:ar in lag-time 1.0 0.0))
  ([in lag-time mul add]
   (op/madd
    (ugen/multinew (fn [cls i t]
                     (ugen/ugen-new "Lag" :audio cls
                                    ugen/check-same-rate-as-first-input
                                    :bipolar i t))
                   'pure-ugen in lag-time)
    mul add)))

(defn lag:kr
  ([in] (lag:kr in 0.1 1.0 0.0))
  ([in lag-time] (lag:kr in lag-time 1.0 0.0))
  ([in lag-time mul add]
   (op/madd
    (ugen/multinew (fn [cls i t]
                     (ugen/ugen-new "Lag" :control cls
                                    ugen/check-same-rate-as-first-input
                                    :bipolar i t))
                   'pure-ugen in lag-time)
    mul add)))


;; ---- generated mechanical forms (scripts/emit_ugens.clj) ----

(defn resonz:ar
  "Port of Filter.lisp (resonz \"Resonz\")."
  ([] (resonz:ar 0.0 440.0 1.0 1.0 0.0))
  ([in] (resonz:ar in 440.0 1.0 1.0 0.0))
  ([in freq] (resonz:ar in freq 1.0 1.0 0.0))
  ([in freq bwr] (resonz:ar in freq bwr 1.0 0.0))
  ([in freq bwr mul] (resonz:ar in freq bwr mul 0.0))
  ([in freq bwr mul add]
   (op/madd (ugen/multinew (fn [cls in freq bwr] (ugen/ugen-new "Resonz" :audio cls ugen/check-same-rate-as-first-input :bipolar in freq bwr))
                           'pure-ugen in freq bwr)
            mul add)))

(defn resonz:kr
  "Port of Filter.lisp (resonz \"Resonz\")."
  ([] (resonz:kr 0.0 440.0 1.0 1.0 0.0))
  ([in] (resonz:kr in 440.0 1.0 1.0 0.0))
  ([in freq] (resonz:kr in freq 1.0 1.0 0.0))
  ([in freq bwr] (resonz:kr in freq bwr 1.0 0.0))
  ([in freq bwr mul] (resonz:kr in freq bwr mul 0.0))
  ([in freq bwr mul add]
   (op/madd (ugen/multinew (fn [cls in freq bwr] (ugen/ugen-new "Resonz" :control cls ugen/check-same-rate-as-first-input :bipolar in freq bwr))
                           'pure-ugen in freq bwr)
            mul add)))

(defn one-pole:ar
  "Port of Filter.lisp (one-pole \"OnePole\")."
  ([] (one-pole:ar 0.0 0.5 1.0 0.0))
  ([in] (one-pole:ar in 0.5 1.0 0.0))
  ([in coef] (one-pole:ar in coef 1.0 0.0))
  ([in coef mul] (one-pole:ar in coef mul 0.0))
  ([in coef mul add]
   (op/madd (ugen/multinew (fn [cls in coef] (ugen/ugen-new "OnePole" :audio cls ugen/check-same-rate-as-first-input :bipolar in coef))
                           'pure-ugen in coef)
            mul add)))

(defn one-pole:kr
  "Port of Filter.lisp (one-pole \"OnePole\")."
  ([] (one-pole:kr 0.0 0.5 1.0 0.0))
  ([in] (one-pole:kr in 0.5 1.0 0.0))
  ([in coef] (one-pole:kr in coef 1.0 0.0))
  ([in coef mul] (one-pole:kr in coef mul 0.0))
  ([in coef mul add]
   (op/madd (ugen/multinew (fn [cls in coef] (ugen/ugen-new "OnePole" :control cls ugen/check-same-rate-as-first-input :bipolar in coef))
                           'pure-ugen in coef)
            mul add)))

(defn one-zero:ar
  "Port of Filter.lisp (one-zero \"OneZero\")."
  ([] (one-zero:ar 0.0 0.5 1.0 0.0))
  ([in] (one-zero:ar in 0.5 1.0 0.0))
  ([in coef] (one-zero:ar in coef 1.0 0.0))
  ([in coef mul] (one-zero:ar in coef mul 0.0))
  ([in coef mul add]
   (op/madd (ugen/multinew (fn [cls in coef] (ugen/ugen-new "OneZero" :audio cls ugen/check-same-rate-as-first-input :bipolar in coef))
                           'pure-ugen in coef)
            mul add)))

(defn one-zero:kr
  "Port of Filter.lisp (one-zero \"OneZero\")."
  ([] (one-zero:kr 0.0 0.5 1.0 0.0))
  ([in] (one-zero:kr in 0.5 1.0 0.0))
  ([in coef] (one-zero:kr in coef 1.0 0.0))
  ([in coef mul] (one-zero:kr in coef mul 0.0))
  ([in coef mul add]
   (op/madd (ugen/multinew (fn [cls in coef] (ugen/ugen-new "OneZero" :control cls ugen/check-same-rate-as-first-input :bipolar in coef))
                           'pure-ugen in coef)
            mul add)))

(defn two-pole:ar
  "Port of Filter.lisp (two-pole \"TwoPole\")."
  ([] (two-pole:ar 0.0 440.0 0.8 1.0 0.0))
  ([in] (two-pole:ar in 440.0 0.8 1.0 0.0))
  ([in freq] (two-pole:ar in freq 0.8 1.0 0.0))
  ([in freq radius] (two-pole:ar in freq radius 1.0 0.0))
  ([in freq radius mul] (two-pole:ar in freq radius mul 0.0))
  ([in freq radius mul add]
   (op/madd (ugen/multinew (fn [cls in freq radius] (ugen/ugen-new "TwoPole" :audio cls ugen/check-same-rate-as-first-input :bipolar in freq radius))
                           'pure-ugen in freq radius)
            mul add)))

(defn two-pole:kr
  "Port of Filter.lisp (two-pole \"TwoPole\")."
  ([] (two-pole:kr 0.0 440.0 0.8 1.0 0.0))
  ([in] (two-pole:kr in 440.0 0.8 1.0 0.0))
  ([in freq] (two-pole:kr in freq 0.8 1.0 0.0))
  ([in freq radius] (two-pole:kr in freq radius 1.0 0.0))
  ([in freq radius mul] (two-pole:kr in freq radius mul 0.0))
  ([in freq radius mul add]
   (op/madd (ugen/multinew (fn [cls in freq radius] (ugen/ugen-new "TwoPole" :control cls ugen/check-same-rate-as-first-input :bipolar in freq radius))
                           'pure-ugen in freq radius)
            mul add)))

(defn two-zero:ar
  "Port of Filter.lisp (two-zero \"TwoZero\")."
  ([] (two-zero:ar 0.0 440.0 0.8 1.0 0.0))
  ([in] (two-zero:ar in 440.0 0.8 1.0 0.0))
  ([in freq] (two-zero:ar in freq 0.8 1.0 0.0))
  ([in freq radius] (two-zero:ar in freq radius 1.0 0.0))
  ([in freq radius mul] (two-zero:ar in freq radius mul 0.0))
  ([in freq radius mul add]
   (op/madd (ugen/multinew (fn [cls in freq radius] (ugen/ugen-new "TwoZero" :audio cls ugen/check-same-rate-as-first-input :bipolar in freq radius))
                           'pure-ugen in freq radius)
            mul add)))

(defn two-zero:kr
  "Port of Filter.lisp (two-zero \"TwoZero\")."
  ([] (two-zero:kr 0.0 440.0 0.8 1.0 0.0))
  ([in] (two-zero:kr in 440.0 0.8 1.0 0.0))
  ([in freq] (two-zero:kr in freq 0.8 1.0 0.0))
  ([in freq radius] (two-zero:kr in freq radius 1.0 0.0))
  ([in freq radius mul] (two-zero:kr in freq radius mul 0.0))
  ([in freq radius mul add]
   (op/madd (ugen/multinew (fn [cls in freq radius] (ugen/ugen-new "TwoZero" :control cls ugen/check-same-rate-as-first-input :bipolar in freq radius))
                           'pure-ugen in freq radius)
            mul add)))

(defn apf:ar
  "Port of Filter.lisp (apf \"APF\")."
  ([] (apf:ar 0.0 440.0 0.8 1.0 0.0))
  ([in] (apf:ar in 440.0 0.8 1.0 0.0))
  ([in freq] (apf:ar in freq 0.8 1.0 0.0))
  ([in freq radius] (apf:ar in freq radius 1.0 0.0))
  ([in freq radius mul] (apf:ar in freq radius mul 0.0))
  ([in freq radius mul add]
   (op/madd (ugen/multinew (fn [cls in freq radius] (ugen/ugen-new "APF" :audio cls ugen/check-same-rate-as-first-input :bipolar in freq radius))
                           'pure-ugen in freq radius)
            mul add)))

(defn apf:kr
  "Port of Filter.lisp (apf \"APF\")."
  ([] (apf:kr 0.0 440.0 0.8 1.0 0.0))
  ([in] (apf:kr in 440.0 0.8 1.0 0.0))
  ([in freq] (apf:kr in freq 0.8 1.0 0.0))
  ([in freq radius] (apf:kr in freq radius 1.0 0.0))
  ([in freq radius mul] (apf:kr in freq radius mul 0.0))
  ([in freq radius mul add]
   (op/madd (ugen/multinew (fn [cls in freq radius] (ugen/ugen-new "APF" :control cls ugen/check-same-rate-as-first-input :bipolar in freq radius))
                           'pure-ugen in freq radius)
            mul add)))

(defn integrator:ar
  "Port of Filter.lisp (integrator \"Integrator\")."
  ([] (integrator:ar 0.0 1.0 1.0 0.0))
  ([in] (integrator:ar in 1.0 1.0 0.0))
  ([in coef] (integrator:ar in coef 1.0 0.0))
  ([in coef mul] (integrator:ar in coef mul 0.0))
  ([in coef mul add]
   (op/madd (ugen/multinew (fn [cls in coef] (ugen/ugen-new "Integrator" :audio cls ugen/check-same-rate-as-first-input :bipolar in coef))
                           'pure-ugen in coef)
            mul add)))

(defn integrator:kr
  "Port of Filter.lisp (integrator \"Integrator\")."
  ([] (integrator:kr 0.0 1.0 1.0 0.0))
  ([in] (integrator:kr in 1.0 1.0 0.0))
  ([in coef] (integrator:kr in coef 1.0 0.0))
  ([in coef mul] (integrator:kr in coef mul 0.0))
  ([in coef mul add]
   (op/madd (ugen/multinew (fn [cls in coef] (ugen/ugen-new "Integrator" :control cls ugen/check-same-rate-as-first-input :bipolar in coef))
                           'pure-ugen in coef)
            mul add)))

(defn decay:ar
  "Port of Filter.lisp (Decay \"Decay\")."
  ([] (decay:ar 0.0 1.0 1.0 0.0))
  ([in] (decay:ar in 1.0 1.0 0.0))
  ([in decay-time] (decay:ar in decay-time 1.0 0.0))
  ([in decay-time mul] (decay:ar in decay-time mul 0.0))
  ([in decay-time mul add]
   (op/madd (ugen/multinew (fn [cls in decay-time] (ugen/ugen-new "Decay" :audio cls ugen/check-same-rate-as-first-input :bipolar in decay-time))
                           'pure-ugen in decay-time)
            mul add)))

(defn decay:kr
  "Port of Filter.lisp (Decay \"Decay\")."
  ([] (decay:kr 0.0 1.0 1.0 0.0))
  ([in] (decay:kr in 1.0 1.0 0.0))
  ([in decay-time] (decay:kr in decay-time 1.0 0.0))
  ([in decay-time mul] (decay:kr in decay-time mul 0.0))
  ([in decay-time mul add]
   (op/madd (ugen/multinew (fn [cls in decay-time] (ugen/ugen-new "Decay" :control cls ugen/check-same-rate-as-first-input :bipolar in decay-time))
                           'pure-ugen in decay-time)
            mul add)))

(defn decay2:ar
  "Port of Filter.lisp (decay2 \"Decay2\")."
  ([] (decay2:ar 0.0 0.01 1.0 1.0 0.0))
  ([in] (decay2:ar in 0.01 1.0 1.0 0.0))
  ([in attack-time] (decay2:ar in attack-time 1.0 1.0 0.0))
  ([in attack-time decay-time] (decay2:ar in attack-time decay-time 1.0 0.0))
  ([in attack-time decay-time mul] (decay2:ar in attack-time decay-time mul 0.0))
  ([in attack-time decay-time mul add]
   (op/madd (ugen/multinew (fn [cls in attack-time decay-time] (ugen/ugen-new "Decay2" :audio cls ugen/check-same-rate-as-first-input :bipolar in attack-time decay-time))
                           'pure-ugen in attack-time decay-time)
            mul add)))

(defn decay2:kr
  "Port of Filter.lisp (decay2 \"Decay2\")."
  ([] (decay2:kr 0.0 0.01 1.0 1.0 0.0))
  ([in] (decay2:kr in 0.01 1.0 1.0 0.0))
  ([in attack-time] (decay2:kr in attack-time 1.0 1.0 0.0))
  ([in attack-time decay-time] (decay2:kr in attack-time decay-time 1.0 0.0))
  ([in attack-time decay-time mul] (decay2:kr in attack-time decay-time mul 0.0))
  ([in attack-time decay-time mul add]
   (op/madd (ugen/multinew (fn [cls in attack-time decay-time] (ugen/ugen-new "Decay2" :control cls ugen/check-same-rate-as-first-input :bipolar in attack-time decay-time))
                           'pure-ugen in attack-time decay-time)
            mul add)))

(defn lag2:ar
  "Port of Filter.lisp (lag2 \"Lag2\")."
  ([] (lag2:ar 0.0 0.1 1.0 0.0))
  ([in] (lag2:ar in 0.1 1.0 0.0))
  ([in lag-time] (lag2:ar in lag-time 1.0 0.0))
  ([in lag-time mul] (lag2:ar in lag-time mul 0.0))
  ([in lag-time mul add]
   (op/madd (ugen/multinew (fn [cls in lag-time] (ugen/ugen-new "Lag2" :audio cls ugen/check-same-rate-as-first-input :bipolar in lag-time))
                           'pure-ugen in lag-time)
            mul add)))

(defn lag2:kr
  "Port of Filter.lisp (lag2 \"Lag2\")."
  ([] (lag2:kr 0.0 0.1 1.0 0.0))
  ([in] (lag2:kr in 0.1 1.0 0.0))
  ([in lag-time] (lag2:kr in lag-time 1.0 0.0))
  ([in lag-time mul] (lag2:kr in lag-time mul 0.0))
  ([in lag-time mul add]
   (op/madd (ugen/multinew (fn [cls in lag-time] (ugen/ugen-new "Lag2" :control cls ugen/check-same-rate-as-first-input :bipolar in lag-time))
                           'pure-ugen in lag-time)
            mul add)))

(defn lag3:ar
  "Port of Filter.lisp (lag3 \"Lag3\")."
  ([] (lag3:ar 0.0 0.1 1.0 0.0))
  ([in] (lag3:ar in 0.1 1.0 0.0))
  ([in lag-time] (lag3:ar in lag-time 1.0 0.0))
  ([in lag-time mul] (lag3:ar in lag-time mul 0.0))
  ([in lag-time mul add]
   (op/madd (ugen/multinew (fn [cls in lag-time] (ugen/ugen-new "Lag3" :audio cls ugen/check-same-rate-as-first-input :bipolar in lag-time))
                           'pure-ugen in lag-time)
            mul add)))

(defn lag3:kr
  "Port of Filter.lisp (lag3 \"Lag3\")."
  ([] (lag3:kr 0.0 0.1 1.0 0.0))
  ([in] (lag3:kr in 0.1 1.0 0.0))
  ([in lag-time] (lag3:kr in lag-time 1.0 0.0))
  ([in lag-time mul] (lag3:kr in lag-time mul 0.0))
  ([in lag-time mul add]
   (op/madd (ugen/multinew (fn [cls in lag-time] (ugen/ugen-new "Lag3" :control cls ugen/check-same-rate-as-first-input :bipolar in lag-time))
                           'pure-ugen in lag-time)
            mul add)))

(defn ramp:ar
  "Port of Filter.lisp (ramp \"Ramp\")."
  ([] (ramp:ar 0.0 0.1 1.0 0.0))
  ([in] (ramp:ar in 0.1 1.0 0.0))
  ([in lag-time] (ramp:ar in lag-time 1.0 0.0))
  ([in lag-time mul] (ramp:ar in lag-time mul 0.0))
  ([in lag-time mul add]
   (op/madd (ugen/multinew (fn [cls in lag-time] (ugen/ugen-new "Ramp" :audio cls ugen/check-same-rate-as-first-input :bipolar in lag-time))
                           'pure-ugen in lag-time)
            mul add)))

(defn ramp:kr
  "Port of Filter.lisp (ramp \"Ramp\")."
  ([] (ramp:kr 0.0 0.1 1.0 0.0))
  ([in] (ramp:kr in 0.1 1.0 0.0))
  ([in lag-time] (ramp:kr in lag-time 1.0 0.0))
  ([in lag-time mul] (ramp:kr in lag-time mul 0.0))
  ([in lag-time mul add]
   (op/madd (ugen/multinew (fn [cls in lag-time] (ugen/ugen-new "Ramp" :control cls ugen/check-same-rate-as-first-input :bipolar in lag-time))
                           'pure-ugen in lag-time)
            mul add)))

(defn lag-ud:ar
  "Port of Filter.lisp (lag-ud \"LagUD\")."
  ([] (lag-ud:ar 0.0 0.1 0.1 1.0 0.0))
  ([in] (lag-ud:ar in 0.1 0.1 1.0 0.0))
  ([in lag-time-u] (lag-ud:ar in lag-time-u 0.1 1.0 0.0))
  ([in lag-time-u lag-time-d] (lag-ud:ar in lag-time-u lag-time-d 1.0 0.0))
  ([in lag-time-u lag-time-d mul] (lag-ud:ar in lag-time-u lag-time-d mul 0.0))
  ([in lag-time-u lag-time-d mul add]
   (op/madd (ugen/multinew (fn [cls in lag-time-u lag-time-d] (ugen/ugen-new "LagUD" :audio cls ugen/check-same-rate-as-first-input :bipolar in lag-time-u lag-time-d))
                           'pure-ugen in lag-time-u lag-time-d)
            mul add)))

(defn lag-ud:kr
  "Port of Filter.lisp (lag-ud \"LagUD\")."
  ([] (lag-ud:kr 0.0 0.1 0.1 1.0 0.0))
  ([in] (lag-ud:kr in 0.1 0.1 1.0 0.0))
  ([in lag-time-u] (lag-ud:kr in lag-time-u 0.1 1.0 0.0))
  ([in lag-time-u lag-time-d] (lag-ud:kr in lag-time-u lag-time-d 1.0 0.0))
  ([in lag-time-u lag-time-d mul] (lag-ud:kr in lag-time-u lag-time-d mul 0.0))
  ([in lag-time-u lag-time-d mul add]
   (op/madd (ugen/multinew (fn [cls in lag-time-u lag-time-d] (ugen/ugen-new "LagUD" :control cls ugen/check-same-rate-as-first-input :bipolar in lag-time-u lag-time-d))
                           'pure-ugen in lag-time-u lag-time-d)
            mul add)))

(defn lag-2ud:ar
  "Port of Filter.lisp (lag-2ud \"Lag2UD\")."
  ([] (lag-2ud:ar 0.0 0.1 0.1 1.0 0.0))
  ([in] (lag-2ud:ar in 0.1 0.1 1.0 0.0))
  ([in lag-time-u] (lag-2ud:ar in lag-time-u 0.1 1.0 0.0))
  ([in lag-time-u lag-time-d] (lag-2ud:ar in lag-time-u lag-time-d 1.0 0.0))
  ([in lag-time-u lag-time-d mul] (lag-2ud:ar in lag-time-u lag-time-d mul 0.0))
  ([in lag-time-u lag-time-d mul add]
   (op/madd (ugen/multinew (fn [cls in lag-time-u lag-time-d] (ugen/ugen-new "Lag2UD" :audio cls ugen/check-same-rate-as-first-input :bipolar in lag-time-u lag-time-d))
                           'pure-ugen in lag-time-u lag-time-d)
            mul add)))

(defn lag-2ud:kr
  "Port of Filter.lisp (lag-2ud \"Lag2UD\")."
  ([] (lag-2ud:kr 0.0 0.1 0.1 1.0 0.0))
  ([in] (lag-2ud:kr in 0.1 0.1 1.0 0.0))
  ([in lag-time-u] (lag-2ud:kr in lag-time-u 0.1 1.0 0.0))
  ([in lag-time-u lag-time-d] (lag-2ud:kr in lag-time-u lag-time-d 1.0 0.0))
  ([in lag-time-u lag-time-d mul] (lag-2ud:kr in lag-time-u lag-time-d mul 0.0))
  ([in lag-time-u lag-time-d mul add]
   (op/madd (ugen/multinew (fn [cls in lag-time-u lag-time-d] (ugen/ugen-new "Lag2UD" :control cls ugen/check-same-rate-as-first-input :bipolar in lag-time-u lag-time-d))
                           'pure-ugen in lag-time-u lag-time-d)
            mul add)))

(defn lag-3ud:ar
  "Port of Filter.lisp (lag-3ud \"Lag3UD\")."
  ([] (lag-3ud:ar 0.0 0.1 0.1 1.0 0.0))
  ([in] (lag-3ud:ar in 0.1 0.1 1.0 0.0))
  ([in lag-time-u] (lag-3ud:ar in lag-time-u 0.1 1.0 0.0))
  ([in lag-time-u lag-time-d] (lag-3ud:ar in lag-time-u lag-time-d 1.0 0.0))
  ([in lag-time-u lag-time-d mul] (lag-3ud:ar in lag-time-u lag-time-d mul 0.0))
  ([in lag-time-u lag-time-d mul add]
   (op/madd (ugen/multinew (fn [cls in lag-time-u lag-time-d] (ugen/ugen-new "Lag3UD" :audio cls ugen/check-same-rate-as-first-input :bipolar in lag-time-u lag-time-d))
                           'pure-ugen in lag-time-u lag-time-d)
            mul add)))

(defn lag-3ud:kr
  "Port of Filter.lisp (lag-3ud \"Lag3UD\")."
  ([] (lag-3ud:kr 0.0 0.1 0.1 1.0 0.0))
  ([in] (lag-3ud:kr in 0.1 0.1 1.0 0.0))
  ([in lag-time-u] (lag-3ud:kr in lag-time-u 0.1 1.0 0.0))
  ([in lag-time-u lag-time-d] (lag-3ud:kr in lag-time-u lag-time-d 1.0 0.0))
  ([in lag-time-u lag-time-d mul] (lag-3ud:kr in lag-time-u lag-time-d mul 0.0))
  ([in lag-time-u lag-time-d mul add]
   (op/madd (ugen/multinew (fn [cls in lag-time-u lag-time-d] (ugen/ugen-new "Lag3UD" :control cls ugen/check-same-rate-as-first-input :bipolar in lag-time-u lag-time-d))
                           'pure-ugen in lag-time-u lag-time-d)
            mul add)))

(defn leak-dc:ar
  "Port of Filter.lisp (leak-dc \"LeakDC\")."
  ([] (leak-dc:ar 0.0 0.995 1.0 0.0))
  ([in] (leak-dc:ar in 0.995 1.0 0.0))
  ([in coef] (leak-dc:ar in coef 1.0 0.0))
  ([in coef mul] (leak-dc:ar in coef mul 0.0))
  ([in coef mul add]
   (op/madd (ugen/multinew (fn [cls in coef] (ugen/ugen-new "LeakDC" :audio cls ugen/check-same-rate-as-first-input :bipolar in coef))
                           'pure-ugen in coef)
            mul add)))

(defn leak-dc:kr
  "Port of Filter.lisp (leak-dc \"LeakDC\")."
  ([] (leak-dc:kr 0.0 0.995 1.0 0.0))
  ([in] (leak-dc:kr in 0.995 1.0 0.0))
  ([in coef] (leak-dc:kr in coef 1.0 0.0))
  ([in coef mul] (leak-dc:kr in coef mul 0.0))
  ([in coef mul add]
   (op/madd (ugen/multinew (fn [cls in coef] (ugen/ugen-new "LeakDC" :control cls ugen/check-same-rate-as-first-input :bipolar in coef))
                           'pure-ugen in coef)
            mul add)))

(defn rlpf:ar
  "Port of Filter.lisp (rlpf \"RLPF\")."
  ([] (rlpf:ar 0.0 440.0 1.0 1.0 0.0))
  ([in] (rlpf:ar in 440.0 1.0 1.0 0.0))
  ([in freq] (rlpf:ar in freq 1.0 1.0 0.0))
  ([in freq rq] (rlpf:ar in freq rq 1.0 0.0))
  ([in freq rq mul] (rlpf:ar in freq rq mul 0.0))
  ([in freq rq mul add]
   (op/madd (ugen/multinew (fn [cls in freq rq] (ugen/ugen-new "RLPF" :audio cls ugen/check-same-rate-as-first-input :bipolar in freq rq))
                           'pure-ugen in freq rq)
            mul add)))

(defn rlpf:kr
  "Port of Filter.lisp (rlpf \"RLPF\")."
  ([] (rlpf:kr 0.0 440.0 1.0 1.0 0.0))
  ([in] (rlpf:kr in 440.0 1.0 1.0 0.0))
  ([in freq] (rlpf:kr in freq 1.0 1.0 0.0))
  ([in freq rq] (rlpf:kr in freq rq 1.0 0.0))
  ([in freq rq mul] (rlpf:kr in freq rq mul 0.0))
  ([in freq rq mul add]
   (op/madd (ugen/multinew (fn [cls in freq rq] (ugen/ugen-new "RLPF" :control cls ugen/check-same-rate-as-first-input :bipolar in freq rq))
                           'pure-ugen in freq rq)
            mul add)))

(defn rhpf:ar
  "Port of Filter.lisp (rhpf \"RHPF\")."
  ([] (rhpf:ar 0.0 440.0 1.0 1.0 0.0))
  ([in] (rhpf:ar in 440.0 1.0 1.0 0.0))
  ([in freq] (rhpf:ar in freq 1.0 1.0 0.0))
  ([in freq rq] (rhpf:ar in freq rq 1.0 0.0))
  ([in freq rq mul] (rhpf:ar in freq rq mul 0.0))
  ([in freq rq mul add]
   (op/madd (ugen/multinew (fn [cls in freq rq] (ugen/ugen-new "RHPF" :audio cls ugen/check-same-rate-as-first-input :bipolar in freq rq))
                           'pure-ugen in freq rq)
            mul add)))

(defn rhpf:kr
  "Port of Filter.lisp (rhpf \"RHPF\")."
  ([] (rhpf:kr 0.0 440.0 1.0 1.0 0.0))
  ([in] (rhpf:kr in 440.0 1.0 1.0 0.0))
  ([in freq] (rhpf:kr in freq 1.0 1.0 0.0))
  ([in freq rq] (rhpf:kr in freq rq 1.0 0.0))
  ([in freq rq mul] (rhpf:kr in freq rq mul 0.0))
  ([in freq rq mul add]
   (op/madd (ugen/multinew (fn [cls in freq rq] (ugen/ugen-new "RHPF" :control cls ugen/check-same-rate-as-first-input :bipolar in freq rq))
                           'pure-ugen in freq rq)
            mul add)))

(defn lpf:ar
  "Port of Filter.lisp (lpf \"LPF\")."
  ([] (lpf:ar 0.0 440.0 1.0 0.0))
  ([in] (lpf:ar in 440.0 1.0 0.0))
  ([in freq] (lpf:ar in freq 1.0 0.0))
  ([in freq mul] (lpf:ar in freq mul 0.0))
  ([in freq mul add]
   (op/madd (ugen/multinew (fn [cls in freq] (ugen/ugen-new "LPF" :audio cls ugen/check-same-rate-as-first-input :bipolar in freq))
                           'pure-ugen in freq)
            mul add)))

(defn lpf:kr
  "Port of Filter.lisp (lpf \"LPF\")."
  ([] (lpf:kr 0.0 440.0 1.0 0.0))
  ([in] (lpf:kr in 440.0 1.0 0.0))
  ([in freq] (lpf:kr in freq 1.0 0.0))
  ([in freq mul] (lpf:kr in freq mul 0.0))
  ([in freq mul add]
   (op/madd (ugen/multinew (fn [cls in freq] (ugen/ugen-new "LPF" :control cls ugen/check-same-rate-as-first-input :bipolar in freq))
                           'pure-ugen in freq)
            mul add)))

(defn hpf:ar
  "Port of Filter.lisp (hpf \"HPF\")."
  ([] (hpf:ar 0.0 440.0 1.0 0.0))
  ([in] (hpf:ar in 440.0 1.0 0.0))
  ([in freq] (hpf:ar in freq 1.0 0.0))
  ([in freq mul] (hpf:ar in freq mul 0.0))
  ([in freq mul add]
   (op/madd (ugen/multinew (fn [cls in freq] (ugen/ugen-new "HPF" :audio cls ugen/check-same-rate-as-first-input :bipolar in freq))
                           'pure-ugen in freq)
            mul add)))

(defn hpf:kr
  "Port of Filter.lisp (hpf \"HPF\")."
  ([] (hpf:kr 0.0 440.0 1.0 0.0))
  ([in] (hpf:kr in 440.0 1.0 0.0))
  ([in freq] (hpf:kr in freq 1.0 0.0))
  ([in freq mul] (hpf:kr in freq mul 0.0))
  ([in freq mul add]
   (op/madd (ugen/multinew (fn [cls in freq] (ugen/ugen-new "HPF" :control cls ugen/check-same-rate-as-first-input :bipolar in freq))
                           'pure-ugen in freq)
            mul add)))

(defn bpf:ar
  "Port of Filter.lisp (bpf \"BPF\")."
  ([] (bpf:ar 0.0 440.0 1.0 1.0 0.0))
  ([in] (bpf:ar in 440.0 1.0 1.0 0.0))
  ([in freq] (bpf:ar in freq 1.0 1.0 0.0))
  ([in freq rq] (bpf:ar in freq rq 1.0 0.0))
  ([in freq rq mul] (bpf:ar in freq rq mul 0.0))
  ([in freq rq mul add]
   (op/madd (ugen/multinew (fn [cls in freq rq] (ugen/ugen-new "BPF" :audio cls ugen/check-same-rate-as-first-input :bipolar in freq rq))
                           'pure-ugen in freq rq)
            mul add)))

(defn bpf:kr
  "Port of Filter.lisp (bpf \"BPF\")."
  ([] (bpf:kr 0.0 440.0 1.0 1.0 0.0))
  ([in] (bpf:kr in 440.0 1.0 1.0 0.0))
  ([in freq] (bpf:kr in freq 1.0 1.0 0.0))
  ([in freq rq] (bpf:kr in freq rq 1.0 0.0))
  ([in freq rq mul] (bpf:kr in freq rq mul 0.0))
  ([in freq rq mul add]
   (op/madd (ugen/multinew (fn [cls in freq rq] (ugen/ugen-new "BPF" :control cls ugen/check-same-rate-as-first-input :bipolar in freq rq))
                           'pure-ugen in freq rq)
            mul add)))

(defn brf:ar
  "Port of Filter.lisp (brf \"BRF\")."
  ([] (brf:ar 0.0 440.0 1.0 1.0 0.0))
  ([in] (brf:ar in 440.0 1.0 1.0 0.0))
  ([in freq] (brf:ar in freq 1.0 1.0 0.0))
  ([in freq rq] (brf:ar in freq rq 1.0 0.0))
  ([in freq rq mul] (brf:ar in freq rq mul 0.0))
  ([in freq rq mul add]
   (op/madd (ugen/multinew (fn [cls in freq rq] (ugen/ugen-new "BRF" :audio cls ugen/check-same-rate-as-first-input :bipolar in freq rq))
                           'pure-ugen in freq rq)
            mul add)))

(defn brf:kr
  "Port of Filter.lisp (brf \"BRF\")."
  ([] (brf:kr 0.0 440.0 1.0 1.0 0.0))
  ([in] (brf:kr in 440.0 1.0 1.0 0.0))
  ([in freq] (brf:kr in freq 1.0 1.0 0.0))
  ([in freq rq] (brf:kr in freq rq 1.0 0.0))
  ([in freq rq mul] (brf:kr in freq rq mul 0.0))
  ([in freq rq mul add]
   (op/madd (ugen/multinew (fn [cls in freq rq] (ugen/ugen-new "BRF" :control cls ugen/check-same-rate-as-first-input :bipolar in freq rq))
                           'pure-ugen in freq rq)
            mul add)))

(defn mid-eq:ar
  "Port of Filter.lisp (mid-eq \"MidEQ\")."
  ([] (mid-eq:ar 0.0 440.0 1.0 0.0 1.0 0.0))
  ([in] (mid-eq:ar in 440.0 1.0 0.0 1.0 0.0))
  ([in freq] (mid-eq:ar in freq 1.0 0.0 1.0 0.0))
  ([in freq rq] (mid-eq:ar in freq rq 0.0 1.0 0.0))
  ([in freq rq db] (mid-eq:ar in freq rq db 1.0 0.0))
  ([in freq rq db mul] (mid-eq:ar in freq rq db mul 0.0))
  ([in freq rq db mul add]
   (op/madd (ugen/multinew (fn [cls in freq rq db] (ugen/ugen-new "MidEQ" :audio cls ugen/check-same-rate-as-first-input :bipolar in freq rq db))
                           'pure-ugen in freq rq db)
            mul add)))

(defn mid-eq:kr
  "Port of Filter.lisp (mid-eq \"MidEQ\")."
  ([] (mid-eq:kr 0.0 440.0 1.0 0.0 1.0 0.0))
  ([in] (mid-eq:kr in 440.0 1.0 0.0 1.0 0.0))
  ([in freq] (mid-eq:kr in freq 1.0 0.0 1.0 0.0))
  ([in freq rq] (mid-eq:kr in freq rq 0.0 1.0 0.0))
  ([in freq rq db] (mid-eq:kr in freq rq db 1.0 0.0))
  ([in freq rq db mul] (mid-eq:kr in freq rq db mul 0.0))
  ([in freq rq db mul add]
   (op/madd (ugen/multinew (fn [cls in freq rq db] (ugen/ugen-new "MidEQ" :control cls ugen/check-same-rate-as-first-input :bipolar in freq rq db))
                           'pure-ugen in freq rq db)
            mul add)))

(defn lpz-1:ar
  "Port of Filter.lisp (lpz-1 \"LPZ1\")."
  ([] (lpz-1:ar 0.0 1.0 0.0))
  ([in] (lpz-1:ar in 1.0 0.0))
  ([in mul] (lpz-1:ar in mul 0.0))
  ([in mul add]
   (op/madd (ugen/multinew (fn [cls in] (ugen/ugen-new "LPZ1" :audio cls ugen/check-same-rate-as-first-input :bipolar in))
                           'pure-ugen in)
            mul add)))

(defn lpz-1:kr
  "Port of Filter.lisp (lpz-1 \"LPZ1\")."
  ([] (lpz-1:kr 0.0 1.0 0.0))
  ([in] (lpz-1:kr in 1.0 0.0))
  ([in mul] (lpz-1:kr in mul 0.0))
  ([in mul add]
   (op/madd (ugen/multinew (fn [cls in] (ugen/ugen-new "LPZ1" :control cls ugen/check-same-rate-as-first-input :bipolar in))
                           'pure-ugen in)
            mul add)))

(defn slope:ar
  "Port of Filter.lisp (slope \"Slope\")."
  ([] (slope:ar 0.0 1.0 0.0))
  ([in] (slope:ar in 1.0 0.0))
  ([in mul] (slope:ar in mul 0.0))
  ([in mul add]
   (op/madd (ugen/multinew (fn [cls in] (ugen/ugen-new "Slope" :audio cls ugen/check-same-rate-as-first-input :bipolar in))
                           'pure-ugen in)
            mul add)))

(defn slope:kr
  "Port of Filter.lisp (slope \"Slope\")."
  ([] (slope:kr 0.0 1.0 0.0))
  ([in] (slope:kr in 1.0 0.0))
  ([in mul] (slope:kr in mul 0.0))
  ([in mul add]
   (op/madd (ugen/multinew (fn [cls in] (ugen/ugen-new "Slope" :control cls ugen/check-same-rate-as-first-input :bipolar in))
                           'pure-ugen in)
            mul add)))

(defn lpz-2:ar
  "Port of Filter.lisp (lpz-2 \"LPZ2\")."
  ([] (lpz-2:ar 0.0 1.0 0.0))
  ([in] (lpz-2:ar in 1.0 0.0))
  ([in mul] (lpz-2:ar in mul 0.0))
  ([in mul add]
   (op/madd (ugen/multinew (fn [cls in] (ugen/ugen-new "LPZ2" :audio cls ugen/check-same-rate-as-first-input :bipolar in))
                           'pure-ugen in)
            mul add)))

(defn lpz-2:kr
  "Port of Filter.lisp (lpz-2 \"LPZ2\")."
  ([] (lpz-2:kr 0.0 1.0 0.0))
  ([in] (lpz-2:kr in 1.0 0.0))
  ([in mul] (lpz-2:kr in mul 0.0))
  ([in mul add]
   (op/madd (ugen/multinew (fn [cls in] (ugen/ugen-new "LPZ2" :control cls ugen/check-same-rate-as-first-input :bipolar in))
                           'pure-ugen in)
            mul add)))

(defn hpz-2:ar
  "Port of Filter.lisp (hpz-2 \"HPZ2\")."
  ([] (hpz-2:ar 0.0 1.0 0.0))
  ([in] (hpz-2:ar in 1.0 0.0))
  ([in mul] (hpz-2:ar in mul 0.0))
  ([in mul add]
   (op/madd (ugen/multinew (fn [cls in] (ugen/ugen-new "HPZ2" :audio cls ugen/check-same-rate-as-first-input :bipolar in))
                           'pure-ugen in)
            mul add)))

(defn hpz-2:kr
  "Port of Filter.lisp (hpz-2 \"HPZ2\")."
  ([] (hpz-2:kr 0.0 1.0 0.0))
  ([in] (hpz-2:kr in 1.0 0.0))
  ([in mul] (hpz-2:kr in mul 0.0))
  ([in mul add]
   (op/madd (ugen/multinew (fn [cls in] (ugen/ugen-new "HPZ2" :control cls ugen/check-same-rate-as-first-input :bipolar in))
                           'pure-ugen in)
            mul add)))

(defn bpz-2:ar
  "Port of Filter.lisp (bpz-2 \"BPZ2\")."
  ([] (bpz-2:ar 0.0 1.0 0.0))
  ([in] (bpz-2:ar in 1.0 0.0))
  ([in mul] (bpz-2:ar in mul 0.0))
  ([in mul add]
   (op/madd (ugen/multinew (fn [cls in] (ugen/ugen-new "BPZ2" :audio cls ugen/check-same-rate-as-first-input :bipolar in))
                           'pure-ugen in)
            mul add)))

(defn bpz-2:kr
  "Port of Filter.lisp (bpz-2 \"BPZ2\")."
  ([] (bpz-2:kr 0.0 1.0 0.0))
  ([in] (bpz-2:kr in 1.0 0.0))
  ([in mul] (bpz-2:kr in mul 0.0))
  ([in mul add]
   (op/madd (ugen/multinew (fn [cls in] (ugen/ugen-new "BPZ2" :control cls ugen/check-same-rate-as-first-input :bipolar in))
                           'pure-ugen in)
            mul add)))

(defn brz-2:ar
  "Port of Filter.lisp (brz-2 \"BRZ2\")."
  ([] (brz-2:ar 0.0 1.0 0.0))
  ([in] (brz-2:ar in 1.0 0.0))
  ([in mul] (brz-2:ar in mul 0.0))
  ([in mul add]
   (op/madd (ugen/multinew (fn [cls in] (ugen/ugen-new "BRZ2" :audio cls ugen/check-same-rate-as-first-input :bipolar in))
                           'pure-ugen in)
            mul add)))

(defn brz-2:kr
  "Port of Filter.lisp (brz-2 \"BRZ2\")."
  ([] (brz-2:kr 0.0 1.0 0.0))
  ([in] (brz-2:kr in 1.0 0.0))
  ([in mul] (brz-2:kr in mul 0.0))
  ([in mul add]
   (op/madd (ugen/multinew (fn [cls in] (ugen/ugen-new "BRZ2" :control cls ugen/check-same-rate-as-first-input :bipolar in))
                           'pure-ugen in)
            mul add)))

(defn median:ar
  "Port of Filter.lisp (median \"Median\")."
  ([] (median:ar 3 0.0 1.0 0.0))
  ([length] (median:ar length 0.0 1.0 0.0))
  ([length in] (median:ar length in 1.0 0.0))
  ([length in mul] (median:ar length in mul 0.0))
  ([length in mul add]
   (op/madd (ugen/multinew (fn [cls length in] (ugen/ugen-new "Median" :audio cls ugen/check-when-audio :bipolar length in))
                           'pure-ugen length in)
            mul add)))

(defn median:kr
  "Port of Filter.lisp (median \"Median\")."
  ([] (median:kr 3 0.0 1.0 0.0))
  ([length] (median:kr length 0.0 1.0 0.0))
  ([length in] (median:kr length in 1.0 0.0))
  ([length in mul] (median:kr length in mul 0.0))
  ([length in mul add]
   (op/madd (ugen/multinew (fn [cls length in] (ugen/ugen-new "Median" :control cls ugen/check-when-audio :bipolar length in))
                           'pure-ugen length in)
            mul add)))

(defn slew:ar
  "Port of Filter.lisp (slew \"Slew\")."
  ([] (slew:ar 0.0 1.0 1.0 1.0 0.0))
  ([in] (slew:ar in 1.0 1.0 1.0 0.0))
  ([in up] (slew:ar in up 1.0 1.0 0.0))
  ([in up dn] (slew:ar in up dn 1.0 0.0))
  ([in up dn mul] (slew:ar in up dn mul 0.0))
  ([in up dn mul add]
   (op/madd (ugen/multinew (fn [cls in up dn] (ugen/ugen-new "Slew" :audio cls ugen/check-same-rate-as-first-input :bipolar in up dn))
                           'pure-ugen in up dn)
            mul add)))

(defn slew:kr
  "Port of Filter.lisp (slew \"Slew\")."
  ([] (slew:kr 0.0 1.0 1.0 1.0 0.0))
  ([in] (slew:kr in 1.0 1.0 1.0 0.0))
  ([in up] (slew:kr in up 1.0 1.0 0.0))
  ([in up dn] (slew:kr in up dn 1.0 0.0))
  ([in up dn mul] (slew:kr in up dn mul 0.0))
  ([in up dn mul add]
   (op/madd (ugen/multinew (fn [cls in up dn] (ugen/ugen-new "Slew" :control cls ugen/check-same-rate-as-first-input :bipolar in up dn))
                           'pure-ugen in up dn)
            mul add)))

(defn fos:ar
  "Port of Filter.lisp (fos \"FOS\")."
  ([] (fos:ar 0.0 0.0 0.0 0.0 1.0 0.0))
  ([in] (fos:ar in 0.0 0.0 0.0 1.0 0.0))
  ([in a0] (fos:ar in a0 0.0 0.0 1.0 0.0))
  ([in a0 a1] (fos:ar in a0 a1 0.0 1.0 0.0))
  ([in a0 a1 b1] (fos:ar in a0 a1 b1 1.0 0.0))
  ([in a0 a1 b1 mul] (fos:ar in a0 a1 b1 mul 0.0))
  ([in a0 a1 b1 mul add]
   (op/madd (ugen/multinew (fn [cls in a0 a1 b1] (ugen/ugen-new "FOS" :audio cls ugen/check-same-rate-as-first-input :bipolar in a0 a1 b1))
                           'pure-ugen in a0 a1 b1)
            mul add)))

(defn fos:kr
  "Port of Filter.lisp (fos \"FOS\")."
  ([] (fos:kr 0.0 0.0 0.0 0.0 1.0 0.0))
  ([in] (fos:kr in 0.0 0.0 0.0 1.0 0.0))
  ([in a0] (fos:kr in a0 0.0 0.0 1.0 0.0))
  ([in a0 a1] (fos:kr in a0 a1 0.0 1.0 0.0))
  ([in a0 a1 b1] (fos:kr in a0 a1 b1 1.0 0.0))
  ([in a0 a1 b1 mul] (fos:kr in a0 a1 b1 mul 0.0))
  ([in a0 a1 b1 mul add]
   (op/madd (ugen/multinew (fn [cls in a0 a1 b1] (ugen/ugen-new "FOS" :control cls ugen/check-same-rate-as-first-input :bipolar in a0 a1 b1))
                           'pure-ugen in a0 a1 b1)
            mul add)))

(defn sos:ar
  "Port of Filter.lisp (sos \"SOS\")."
  ([] (sos:ar 0.0 0.0 0.0 0.0 0.0 0.0 1.0 0.0))
  ([in] (sos:ar in 0.0 0.0 0.0 0.0 0.0 1.0 0.0))
  ([in a0] (sos:ar in a0 0.0 0.0 0.0 0.0 1.0 0.0))
  ([in a0 a1] (sos:ar in a0 a1 0.0 0.0 0.0 1.0 0.0))
  ([in a0 a1 a2] (sos:ar in a0 a1 a2 0.0 0.0 1.0 0.0))
  ([in a0 a1 a2 b1] (sos:ar in a0 a1 a2 b1 0.0 1.0 0.0))
  ([in a0 a1 a2 b1 b2] (sos:ar in a0 a1 a2 b1 b2 1.0 0.0))
  ([in a0 a1 a2 b1 b2 mul] (sos:ar in a0 a1 a2 b1 b2 mul 0.0))
  ([in a0 a1 a2 b1 b2 mul add]
   (op/madd (ugen/multinew (fn [cls in a0 a1 a2 b1 b2] (ugen/ugen-new "SOS" :audio cls ugen/check-same-rate-as-first-input :bipolar in a0 a1 a2 b1 b2))
                           'pure-ugen in a0 a1 a2 b1 b2)
            mul add)))

(defn sos:kr
  "Port of Filter.lisp (sos \"SOS\")."
  ([] (sos:kr 0.0 0.0 0.0 0.0 0.0 0.0 1.0 0.0))
  ([in] (sos:kr in 0.0 0.0 0.0 0.0 0.0 1.0 0.0))
  ([in a0] (sos:kr in a0 0.0 0.0 0.0 0.0 1.0 0.0))
  ([in a0 a1] (sos:kr in a0 a1 0.0 0.0 0.0 1.0 0.0))
  ([in a0 a1 a2] (sos:kr in a0 a1 a2 0.0 0.0 1.0 0.0))
  ([in a0 a1 a2 b1] (sos:kr in a0 a1 a2 b1 0.0 1.0 0.0))
  ([in a0 a1 a2 b1 b2] (sos:kr in a0 a1 a2 b1 b2 1.0 0.0))
  ([in a0 a1 a2 b1 b2 mul] (sos:kr in a0 a1 a2 b1 b2 mul 0.0))
  ([in a0 a1 a2 b1 b2 mul add]
   (op/madd (ugen/multinew (fn [cls in a0 a1 a2 b1 b2] (ugen/ugen-new "SOS" :control cls ugen/check-same-rate-as-first-input :bipolar in a0 a1 a2 b1 b2))
                           'pure-ugen in a0 a1 a2 b1 b2)
            mul add)))

(defn ringz:ar
  "Port of Filter.lisp (ringz \"Ringz\")."
  ([] (ringz:ar 0.0 440.0 1.0 1.0 0.0))
  ([in] (ringz:ar in 440.0 1.0 1.0 0.0))
  ([in freq] (ringz:ar in freq 1.0 1.0 0.0))
  ([in freq decay-time] (ringz:ar in freq decay-time 1.0 0.0))
  ([in freq decay-time mul] (ringz:ar in freq decay-time mul 0.0))
  ([in freq decay-time mul add]
   (op/madd (ugen/multinew (fn [cls in freq decay-time] (ugen/ugen-new "Ringz" :audio cls ugen/check-same-rate-as-first-input :bipolar in freq decay-time))
                           'pure-ugen in freq decay-time)
            mul add)))

(defn ringz:kr
  "Port of Filter.lisp (ringz \"Ringz\")."
  ([] (ringz:kr 0.0 440.0 1.0 1.0 0.0))
  ([in] (ringz:kr in 440.0 1.0 1.0 0.0))
  ([in freq] (ringz:kr in freq 1.0 1.0 0.0))
  ([in freq decay-time] (ringz:kr in freq decay-time 1.0 0.0))
  ([in freq decay-time mul] (ringz:kr in freq decay-time mul 0.0))
  ([in freq decay-time mul add]
   (op/madd (ugen/multinew (fn [cls in freq decay-time] (ugen/ugen-new "Ringz" :control cls ugen/check-same-rate-as-first-input :bipolar in freq decay-time))
                           'pure-ugen in freq decay-time)
            mul add)))

(defn formlet:ar
  "Port of Filter.lisp (formlet \"Formlet\")."
  ([] (formlet:ar 0.0 440.0 1.0 1.0 1.0 0.0))
  ([in] (formlet:ar in 440.0 1.0 1.0 1.0 0.0))
  ([in freq] (formlet:ar in freq 1.0 1.0 1.0 0.0))
  ([in freq attack] (formlet:ar in freq attack 1.0 1.0 0.0))
  ([in freq attack decay] (formlet:ar in freq attack decay 1.0 0.0))
  ([in freq attack decay mul] (formlet:ar in freq attack decay mul 0.0))
  ([in freq attack decay mul add]
   (op/madd (ugen/multinew (fn [cls in freq attack decay] (ugen/ugen-new "Formlet" :audio cls ugen/check-same-rate-as-first-input :bipolar in freq attack decay))
                           'pure-ugen in freq attack decay)
            mul add)))

(defn formlet:kr
  "Port of Filter.lisp (formlet \"Formlet\")."
  ([] (formlet:kr 0.0 440.0 1.0 1.0 1.0 0.0))
  ([in] (formlet:kr in 440.0 1.0 1.0 1.0 0.0))
  ([in freq] (formlet:kr in freq 1.0 1.0 1.0 0.0))
  ([in freq attack] (formlet:kr in freq attack 1.0 1.0 0.0))
  ([in freq attack decay] (formlet:kr in freq attack decay 1.0 0.0))
  ([in freq attack decay mul] (formlet:kr in freq attack decay mul 0.0))
  ([in freq attack decay mul add]
   (op/madd (ugen/multinew (fn [cls in freq attack decay] (ugen/ugen-new "Formlet" :control cls ugen/check-same-rate-as-first-input :bipolar in freq attack decay))
                           'pure-ugen in freq attack decay)
            mul add)))

(defn detect-silence:ar
  "Port of Filter.lisp (detect-silence \"DetectSilence\")."
  ([] (detect-silence:ar 0.0 1.0E-4 :time 0.1 :act :no-action))
  ([in] (detect-silence:ar in 1.0E-4 :time 0.1 :act :no-action))
  ([in amp & {:keys [time act] :or {time 0.1 act :no-action}}]
   (ugen/multinew (fn [cls in amp time act] (ugen/ugen-new "DetectSilence" :audio cls ugen/check-same-rate-as-first-input :bipolar in amp time (ugen/act act)))
                           'ugen in amp time act)))

(defn detect-silence:kr
  "Port of Filter.lisp (detect-silence \"DetectSilence\")."
  ([] (detect-silence:kr 0.0 1.0E-4 :time 0.1 :act :no-action))
  ([in] (detect-silence:kr in 1.0E-4 :time 0.1 :act :no-action))
  ([in amp & {:keys [time act] :or {time 0.1 act :no-action}}]
   (ugen/multinew (fn [cls in amp time act] (ugen/ugen-new "DetectSilence" :control cls ugen/check-same-rate-as-first-input :bipolar in amp time (ugen/act act)))
                           'ugen in amp time act)))

(defn svf:ar
  "State Variable Filter (not in upstream Filter.lisp; SC semantics:
   SVF.ar(in, cutoff, res, lowpass, bandpass, highpass, mul, add))."
  ([] (svf:ar 0.0 1000.0 0.7 0.0 0.0 0.0 1.0 0.0))
  ([in] (svf:ar in 1000.0 0.7 0.0 0.0 0.0 1.0 0.0))
  ([in cutoff] (svf:ar in cutoff 0.7 0.0 0.0 0.0 1.0 0.0))
  ([in cutoff res] (svf:ar in cutoff res 0.0 0.0 0.0 1.0 0.0))
  ([in cutoff res lowpass] (svf:ar in cutoff res lowpass 0.0 0.0 1.0 0.0))
  ([in cutoff res lowpass bandpass] (svf:ar in cutoff res lowpass bandpass 0.0 1.0 0.0))
  ([in cutoff res lowpass bandpass highpass] (svf:ar in cutoff res lowpass bandpass highpass 1.0 0.0))
  ([in cutoff res lowpass bandpass highpass mul] (svf:ar in cutoff res lowpass bandpass highpass mul 0.0))
  ([in cutoff res lowpass bandpass highpass mul add]
   (op/madd (ugen/multinew (fn [cls in cutoff res lowpass bandpass highpass] (ugen/ugen-new "SVF" :audio cls ugen/check-same-rate-as-first-input :bipolar in cutoff res lowpass bandpass highpass))
                           'pure-ugen in cutoff res lowpass bandpass highpass)
            mul add)))

(defn svf:kr
  "State Variable Filter (not in upstream Filter.lisp; SC semantics:
   SVF.kr(in, cutoff, res, lowpass, bandpass, highpass, mul, add))."
  ([] (svf:kr 0.0 1000.0 0.7 0.0 0.0 0.0 1.0 0.0))
  ([in] (svf:kr in 1000.0 0.7 0.0 0.0 0.0 1.0 0.0))
  ([in cutoff] (svf:kr in cutoff 0.7 0.0 0.0 0.0 1.0 0.0))
  ([in cutoff res] (svf:kr in cutoff res 0.0 0.0 0.0 1.0 0.0))
  ([in cutoff res lowpass] (svf:kr in cutoff res lowpass 0.0 0.0 1.0 0.0))
  ([in cutoff res lowpass bandpass] (svf:kr in cutoff res lowpass bandpass 0.0 1.0 0.0))
  ([in cutoff res lowpass bandpass highpass] (svf:kr in cutoff res lowpass bandpass highpass 1.0 0.0))
  ([in cutoff res lowpass bandpass highpass mul] (svf:kr in cutoff res lowpass bandpass highpass mul 0.0))
  ([in cutoff res lowpass bandpass highpass mul add]
   (op/madd (ugen/multinew (fn [cls in cutoff res lowpass bandpass highpass] (ugen/ugen-new "SVF" :control cls ugen/check-same-rate-as-first-input :bipolar in cutoff res lowpass bandpass highpass))
                           'pure-ugen in cutoff res lowpass bandpass highpass)
            mul add)))
