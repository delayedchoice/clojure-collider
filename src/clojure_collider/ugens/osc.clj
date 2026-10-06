(ns clojure-collider.ugens.osc
  "Oscillator UGens. Mirrors ugens/Osc.lisp defugen shape: the unit takes
   only true unit inputs (SinOsc: freq phase) and mul/add fold through madd
   (so defaults vanish to the bare unit, like upstream)."
  (:require [clojure-collider.synth.ugen :as ugen]
            [clojure-collider.synth.operators :as op]
            [clojure-collider.ugens.pan :as pan]
            [clojure-collider.ugens.noise :as noise]))

(def ^:private no-check (fn [_] nil))

(defn sin-osc:ar
  ([freq] (sin-osc:ar freq 0 1 0))
  ([freq phase] (sin-osc:ar freq phase 1 0))
  ([freq phase mul] (sin-osc:ar freq phase mul 0))
  ([freq phase mul add]
   (op/madd (ugen/multinew (fn [cls & args]
                             (apply ugen/ugen-new "SinOsc" :audio cls no-check :bipolar args))
                           'pure-ugen freq phase)
            mul add)))
(defn sin-osc:kr
  ([freq] (sin-osc:kr freq 0 1 0))
  ([freq phase] (sin-osc:kr freq phase 1 0))
  ([freq phase mul] (sin-osc:kr freq phase mul 0))
  ([freq phase mul add]
   (op/madd (ugen/multinew (fn [cls & args]
                             (apply ugen/ugen-new "SinOsc" :control cls no-check :bipolar args))
                           'pure-ugen freq phase)
            mul add)))

(defn saw:ar
  ([freq] (saw:ar freq 1.0 0.0))
  ([freq mul] (saw:ar freq mul 0.0))
  ([freq mul add]
   (op/madd (ugen/multinew (fn [cls & args]
                             (apply ugen/ugen-new "Saw" :audio cls no-check :bipolar args))
                           'ugen freq)
            mul add)))

(defn impulse:ar
  ([freq] (impulse:ar freq 0.0 1.0 0.0))
  ([freq phase] (impulse:ar freq phase 1.0 0.0))
  ([freq phase mul] (impulse:ar freq phase mul 0.0))
  ([freq phase mul add]
   (op/madd (ugen/multinew (fn [cls & args]
                             (apply ugen/ugen-new "Impulse" :audio cls no-check :unipolar args))
                           'pure-ugen freq phase)
            mul add)))

(defn impulse:kr
  ([freq] (impulse:kr freq 0.0 1.0 0.0))
  ([freq phase] (impulse:kr freq phase 1.0 0.0))
  ([freq phase mul] (impulse:kr freq phase mul 0.0))
  ([freq phase mul add]
   (op/madd (ugen/multinew (fn [cls & args]
                             (apply ugen/ugen-new "Impulse" :control cls no-check :unipolar args))
                           'pure-ugen freq phase)
            mul add)))


;; ---- generated mechanical forms (scripts/emit_ugens.clj) ----

(defn osc:ar
  "Port of Osc.lisp (osc \"Osc\")."
  ([bufnum] (osc:ar bufnum 440.0 0.0 1.0 0.0))
  ([bufnum freq] (osc:ar bufnum freq 0.0 1.0 0.0))
  ([bufnum freq phase] (osc:ar bufnum freq phase 1.0 0.0))
  ([bufnum freq phase mul] (osc:ar bufnum freq phase mul 0.0))
  ([bufnum freq phase mul add]
   (op/madd (ugen/multinew (fn [cls bufnum freq phase] (ugen/ugen-new "Osc" :audio cls no-check :bipolar bufnum freq phase))
                           'pure-ugen bufnum freq phase)
            mul add)))

(defn osc:kr
  "Port of Osc.lisp (osc \"Osc\")."
  ([bufnum] (osc:kr bufnum 440.0 0.0 1.0 0.0))
  ([bufnum freq] (osc:kr bufnum freq 0.0 1.0 0.0))
  ([bufnum freq phase] (osc:kr bufnum freq phase 1.0 0.0))
  ([bufnum freq phase mul] (osc:kr bufnum freq phase mul 0.0))
  ([bufnum freq phase mul add]
   (op/madd (ugen/multinew (fn [cls bufnum freq phase] (ugen/ugen-new "Osc" :control cls no-check :bipolar bufnum freq phase))
                           'pure-ugen bufnum freq phase)
            mul add)))

(defn sin-osc-fb:ar
  "Port of Osc.lisp (sin-osc-fb \"SinOscFB\")."
  ([] (sin-osc-fb:ar 440.0 0.0 1.0 0.0))
  ([freq] (sin-osc-fb:ar freq 0.0 1.0 0.0))
  ([freq feedback] (sin-osc-fb:ar freq feedback 1.0 0.0))
  ([freq feedback mul] (sin-osc-fb:ar freq feedback mul 0.0))
  ([freq feedback mul add]
   (op/madd (ugen/multinew (fn [cls freq feedback] (ugen/ugen-new "SinOscFB" :audio cls no-check :bipolar freq feedback))
                           'pure-ugen freq feedback)
            mul add)))

(defn sin-osc-fb:kr
  "Port of Osc.lisp (sin-osc-fb \"SinOscFB\")."
  ([] (sin-osc-fb:kr 440.0 0.0 1.0 0.0))
  ([freq] (sin-osc-fb:kr freq 0.0 1.0 0.0))
  ([freq feedback] (sin-osc-fb:kr freq feedback 1.0 0.0))
  ([freq feedback mul] (sin-osc-fb:kr freq feedback mul 0.0))
  ([freq feedback mul add]
   (op/madd (ugen/multinew (fn [cls freq feedback] (ugen/ugen-new "SinOscFB" :control cls no-check :bipolar freq feedback))
                           'pure-ugen freq feedback)
            mul add)))

(defn osc-n:ar
  "Port of Osc.lisp (osc-n \"OscN\")."
  ([bufnum] (osc-n:ar bufnum 440.0 0.0 1.0 0.0))
  ([bufnum freq] (osc-n:ar bufnum freq 0.0 1.0 0.0))
  ([bufnum freq phase] (osc-n:ar bufnum freq phase 1.0 0.0))
  ([bufnum freq phase mul] (osc-n:ar bufnum freq phase mul 0.0))
  ([bufnum freq phase mul add]
   (op/madd (ugen/multinew (fn [cls bufnum freq phase] (ugen/ugen-new "OscN" :audio cls no-check :bipolar bufnum freq phase))
                           'pure-ugen bufnum freq phase)
            mul add)))

(defn osc-n:kr
  "Port of Osc.lisp (osc-n \"OscN\")."
  ([bufnum] (osc-n:kr bufnum 440.0 0.0 1.0 0.0))
  ([bufnum freq] (osc-n:kr bufnum freq 0.0 1.0 0.0))
  ([bufnum freq phase] (osc-n:kr bufnum freq phase 1.0 0.0))
  ([bufnum freq phase mul] (osc-n:kr bufnum freq phase mul 0.0))
  ([bufnum freq phase mul add]
   (op/madd (ugen/multinew (fn [cls bufnum freq phase] (ugen/ugen-new "OscN" :control cls no-check :bipolar bufnum freq phase))
                           'pure-ugen bufnum freq phase)
            mul add)))

(defn v-osc:ar
  "Port of Osc.lisp (v-osc \"VOsc\")."
  ([bufpos] (v-osc:ar bufpos 440.0 0.0 1.0 0.0))
  ([bufpos freq] (v-osc:ar bufpos freq 0.0 1.0 0.0))
  ([bufpos freq phase] (v-osc:ar bufpos freq phase 1.0 0.0))
  ([bufpos freq phase mul] (v-osc:ar bufpos freq phase mul 0.0))
  ([bufpos freq phase mul add]
   (op/madd (ugen/multinew (fn [cls bufpos freq phase] (ugen/ugen-new "VOsc" :audio cls no-check :bipolar bufpos freq phase))
                           'pure-ugen bufpos freq phase)
            mul add)))

(defn v-osc:kr
  "Port of Osc.lisp (v-osc \"VOsc\")."
  ([bufpos] (v-osc:kr bufpos 440.0 0.0 1.0 0.0))
  ([bufpos freq] (v-osc:kr bufpos freq 0.0 1.0 0.0))
  ([bufpos freq phase] (v-osc:kr bufpos freq phase 1.0 0.0))
  ([bufpos freq phase mul] (v-osc:kr bufpos freq phase mul 0.0))
  ([bufpos freq phase mul add]
   (op/madd (ugen/multinew (fn [cls bufpos freq phase] (ugen/ugen-new "VOsc" :control cls no-check :bipolar bufpos freq phase))
                           'pure-ugen bufpos freq phase)
            mul add)))

(defn c-osc:ar
  "Port of Osc.lisp (c-osc \"COsc\")."
  ([bufnum] (c-osc:ar bufnum 440.0 0.5 1.0 0.0))
  ([bufnum freq] (c-osc:ar bufnum freq 0.5 1.0 0.0))
  ([bufnum freq beats] (c-osc:ar bufnum freq beats 1.0 0.0))
  ([bufnum freq beats mul] (c-osc:ar bufnum freq beats mul 0.0))
  ([bufnum freq beats mul add]
   (op/madd (ugen/multinew (fn [cls bufnum freq beats] (ugen/ugen-new "COsc" :audio cls no-check :bipolar bufnum freq beats))
                           'pure-ugen bufnum freq beats)
            mul add)))

(defn c-osc:kr
  "Port of Osc.lisp (c-osc \"COsc\")."
  ([bufnum] (c-osc:kr bufnum 440.0 0.5 1.0 0.0))
  ([bufnum freq] (c-osc:kr bufnum freq 0.5 1.0 0.0))
  ([bufnum freq beats] (c-osc:kr bufnum freq beats 1.0 0.0))
  ([bufnum freq beats mul] (c-osc:kr bufnum freq beats mul 0.0))
  ([bufnum freq beats mul add]
   (op/madd (ugen/multinew (fn [cls bufnum freq beats] (ugen/ugen-new "COsc" :control cls no-check :bipolar bufnum freq beats))
                           'pure-ugen bufnum freq beats)
            mul add)))

(defn formant:ar
  "Port of Osc.lisp (formant \"Formant\")."
  ([] (formant:ar 440.0 1760.0 880.0 1.0 0.0))
  ([fund-freq] (formant:ar fund-freq 1760.0 880.0 1.0 0.0))
  ([fund-freq form-freq] (formant:ar fund-freq form-freq 880.0 1.0 0.0))
  ([fund-freq form-freq bw-freq] (formant:ar fund-freq form-freq bw-freq 1.0 0.0))
  ([fund-freq form-freq bw-freq mul] (formant:ar fund-freq form-freq bw-freq mul 0.0))
  ([fund-freq form-freq bw-freq mul add]
   (op/madd (ugen/multinew (fn [cls fund-freq form-freq bw-freq] (ugen/ugen-new "Formant" :audio cls no-check :bipolar fund-freq form-freq bw-freq))
                           'pure-ugen fund-freq form-freq bw-freq)
            mul add)))

(defn lf-saw:ar
  "Port of Osc.lisp (lf-saw \"LFSaw\")."
  ([] (lf-saw:ar 440.0 0.0 1.0 0.0))
  ([freq] (lf-saw:ar freq 0.0 1.0 0.0))
  ([freq iphase] (lf-saw:ar freq iphase 1.0 0.0))
  ([freq iphase mul] (lf-saw:ar freq iphase mul 0.0))
  ([freq iphase mul add]
   (op/madd (ugen/multinew (fn [cls freq iphase] (ugen/ugen-new "LFSaw" :audio cls no-check :bipolar freq iphase))
                           'pure-ugen freq iphase)
            mul add)))

(defn lf-saw:kr
  "Port of Osc.lisp (lf-saw \"LFSaw\")."
  ([] (lf-saw:kr 440.0 0.0 1.0 0.0))
  ([freq] (lf-saw:kr freq 0.0 1.0 0.0))
  ([freq iphase] (lf-saw:kr freq iphase 1.0 0.0))
  ([freq iphase mul] (lf-saw:kr freq iphase mul 0.0))
  ([freq iphase mul add]
   (op/madd (ugen/multinew (fn [cls freq iphase] (ugen/ugen-new "LFSaw" :control cls no-check :bipolar freq iphase))
                           'pure-ugen freq iphase)
            mul add)))

(defn lf-par:ar
  "Port of Osc.lisp (lf-par \"LFPar\")."
  ([] (lf-par:ar 440.0 0.0 1.0 0.0))
  ([freq] (lf-par:ar freq 0.0 1.0 0.0))
  ([freq iphase] (lf-par:ar freq iphase 1.0 0.0))
  ([freq iphase mul] (lf-par:ar freq iphase mul 0.0))
  ([freq iphase mul add]
   (op/madd (ugen/multinew (fn [cls freq iphase] (ugen/ugen-new "LFPar" :audio cls no-check :bipolar freq iphase))
                           'pure-ugen freq iphase)
            mul add)))

(defn lf-par:kr
  "Port of Osc.lisp (lf-par \"LFPar\")."
  ([] (lf-par:kr 440.0 0.0 1.0 0.0))
  ([freq] (lf-par:kr freq 0.0 1.0 0.0))
  ([freq iphase] (lf-par:kr freq iphase 1.0 0.0))
  ([freq iphase mul] (lf-par:kr freq iphase mul 0.0))
  ([freq iphase mul add]
   (op/madd (ugen/multinew (fn [cls freq iphase] (ugen/ugen-new "LFPar" :control cls no-check :bipolar freq iphase))
                           'pure-ugen freq iphase)
            mul add)))

(defn lf-cub:ar
  "Port of Osc.lisp (lf-cub \"LFCub\")."
  ([] (lf-cub:ar 440.0 0.0 1.0 0.0))
  ([freq] (lf-cub:ar freq 0.0 1.0 0.0))
  ([freq iphase] (lf-cub:ar freq iphase 1.0 0.0))
  ([freq iphase mul] (lf-cub:ar freq iphase mul 0.0))
  ([freq iphase mul add]
   (op/madd (ugen/multinew (fn [cls freq iphase] (ugen/ugen-new "LFCub" :audio cls no-check :bipolar freq iphase))
                           'pure-ugen freq iphase)
            mul add)))

(defn lf-cub:kr
  "Port of Osc.lisp (lf-cub \"LFCub\")."
  ([] (lf-cub:kr 440.0 0.0 1.0 0.0))
  ([freq] (lf-cub:kr freq 0.0 1.0 0.0))
  ([freq iphase] (lf-cub:kr freq iphase 1.0 0.0))
  ([freq iphase mul] (lf-cub:kr freq iphase mul 0.0))
  ([freq iphase mul add]
   (op/madd (ugen/multinew (fn [cls freq iphase] (ugen/ugen-new "LFCub" :control cls no-check :bipolar freq iphase))
                           'pure-ugen freq iphase)
            mul add)))

(defn lf-tri:ar
  "Port of Osc.lisp (lf-tri \"LFTri\")."
  ([] (lf-tri:ar 440.0 0.0 1.0 0.0))
  ([freq] (lf-tri:ar freq 0.0 1.0 0.0))
  ([freq iphase] (lf-tri:ar freq iphase 1.0 0.0))
  ([freq iphase mul] (lf-tri:ar freq iphase mul 0.0))
  ([freq iphase mul add]
   (op/madd (ugen/multinew (fn [cls freq iphase] (ugen/ugen-new "LFTri" :audio cls no-check :bipolar freq iphase))
                           'pure-ugen freq iphase)
            mul add)))

(defn lf-tri:kr
  "Port of Osc.lisp (lf-tri \"LFTri\")."
  ([] (lf-tri:kr 440.0 0.0 1.0 0.0))
  ([freq] (lf-tri:kr freq 0.0 1.0 0.0))
  ([freq iphase] (lf-tri:kr freq iphase 1.0 0.0))
  ([freq iphase mul] (lf-tri:kr freq iphase mul 0.0))
  ([freq iphase mul add]
   (op/madd (ugen/multinew (fn [cls freq iphase] (ugen/ugen-new "LFTri" :control cls no-check :bipolar freq iphase))
                           'pure-ugen freq iphase)
            mul add)))

(defn lf-gauss:ar
  "Port of Osc.lisp (lf-gauss \"LFGauss\")."
  ([] (lf-gauss:ar 1 0.1 :iphase 0.0 :loop 1 :action 0))
  ([duration] (lf-gauss:ar duration 0.1 :iphase 0.0 :loop 1 :action 0))
  ([duration width & {:keys [iphase loop action] :or {iphase 0.0 loop 1 action 0}}]
   (ugen/multinew (fn [cls duration width iphase loop action] (ugen/ugen-new "LFGauss" :audio cls no-check :bipolar duration width iphase loop action))
                           'lf-gauss duration width iphase loop action)))

(defn lf-gauss:kr
  "Port of Osc.lisp (lf-gauss \"LFGauss\")."
  ([] (lf-gauss:kr 1 0.1 :iphase 0.0 :loop 1 :action 0))
  ([duration] (lf-gauss:kr duration 0.1 :iphase 0.0 :loop 1 :action 0))
  ([duration width & {:keys [iphase loop action] :or {iphase 0.0 loop 1 action 0}}]
   (ugen/multinew (fn [cls duration width iphase loop action] (ugen/ugen-new "LFGauss" :control cls no-check :bipolar duration width iphase loop action))
                           'lf-gauss duration width iphase loop action)))

(defn lf-pulse:ar
  "Port of Osc.lisp (lf-pulse \"LFPulse\")."
  ([] (lf-pulse:ar 440.0 0.0 0.5 1.0 0.0))
  ([freq] (lf-pulse:ar freq 0.0 0.5 1.0 0.0))
  ([freq iphase] (lf-pulse:ar freq iphase 0.5 1.0 0.0))
  ([freq iphase width] (lf-pulse:ar freq iphase width 1.0 0.0))
  ([freq iphase width mul] (lf-pulse:ar freq iphase width mul 0.0))
  ([freq iphase width mul add]
   (op/madd (ugen/multinew (fn [cls freq iphase width] (ugen/ugen-new "LFPulse" :audio cls no-check :unipolar freq iphase width))
                           'pure-ugen freq iphase width)
            mul add)))

(defn lf-pulse:kr
  "Port of Osc.lisp (lf-pulse \"LFPulse\")."
  ([] (lf-pulse:kr 440.0 0.0 0.5 1.0 0.0))
  ([freq] (lf-pulse:kr freq 0.0 0.5 1.0 0.0))
  ([freq iphase] (lf-pulse:kr freq iphase 0.5 1.0 0.0))
  ([freq iphase width] (lf-pulse:kr freq iphase width 1.0 0.0))
  ([freq iphase width mul] (lf-pulse:kr freq iphase width mul 0.0))
  ([freq iphase width mul add]
   (op/madd (ugen/multinew (fn [cls freq iphase width] (ugen/ugen-new "LFPulse" :control cls no-check :unipolar freq iphase width))
                           'pure-ugen freq iphase width)
            mul add)))

(defn var-saw:ar
  "Port of Osc.lisp (var-saw \"VarSaw\")."
  ([] (var-saw:ar 440.0 0.0 0.5 1.0 0.0))
  ([freq] (var-saw:ar freq 0.0 0.5 1.0 0.0))
  ([freq iphase] (var-saw:ar freq iphase 0.5 1.0 0.0))
  ([freq iphase width] (var-saw:ar freq iphase width 1.0 0.0))
  ([freq iphase width mul] (var-saw:ar freq iphase width mul 0.0))
  ([freq iphase width mul add]
   (op/madd (ugen/multinew (fn [cls freq iphase width] (ugen/ugen-new "VarSaw" :audio cls no-check :bipolar freq iphase width))
                           'pure-ugen freq iphase width)
            mul add)))

(defn var-saw:kr
  "Port of Osc.lisp (var-saw \"VarSaw\")."
  ([] (var-saw:kr 440.0 0.0 0.5 1.0 0.0))
  ([freq] (var-saw:kr freq 0.0 0.5 1.0 0.0))
  ([freq iphase] (var-saw:kr freq iphase 0.5 1.0 0.0))
  ([freq iphase width] (var-saw:kr freq iphase width 1.0 0.0))
  ([freq iphase width mul] (var-saw:kr freq iphase width mul 0.0))
  ([freq iphase width mul add]
   (op/madd (ugen/multinew (fn [cls freq iphase width] (ugen/ugen-new "VarSaw" :control cls no-check :bipolar freq iphase width))
                           'pure-ugen freq iphase width)
            mul add)))

(defn sync-saw:ar
  "Port of Osc.lisp (sync-saw \"SyncSaw\")."
  ([] (sync-saw:ar 440.0 440.0 1.0 0.0))
  ([sync-freq] (sync-saw:ar sync-freq 440.0 1.0 0.0))
  ([sync-freq saw-freq] (sync-saw:ar sync-freq saw-freq 1.0 0.0))
  ([sync-freq saw-freq mul] (sync-saw:ar sync-freq saw-freq mul 0.0))
  ([sync-freq saw-freq mul add]
   (op/madd (ugen/multinew (fn [cls sync-freq saw-freq] (ugen/ugen-new "SyncSaw" :audio cls no-check :bipolar sync-freq saw-freq))
                           'pure-ugen sync-freq saw-freq)
            mul add)))

(defn sync-saw:kr
  "Port of Osc.lisp (sync-saw \"SyncSaw\")."
  ([] (sync-saw:kr 440.0 440.0 1.0 0.0))
  ([sync-freq] (sync-saw:kr sync-freq 440.0 1.0 0.0))
  ([sync-freq saw-freq] (sync-saw:kr sync-freq saw-freq 1.0 0.0))
  ([sync-freq saw-freq mul] (sync-saw:kr sync-freq saw-freq mul 0.0))
  ([sync-freq saw-freq mul add]
   (op/madd (ugen/multinew (fn [cls sync-freq saw-freq] (ugen/ugen-new "SyncSaw" :control cls no-check :bipolar sync-freq saw-freq))
                           'pure-ugen sync-freq saw-freq)
            mul add)))

(defn index:ar
  "Port of Osc.lisp (index \"Index\")."
  ([bufnum] (index:ar bufnum 0.0 1.0 0.0))
  ([bufnum in] (index:ar bufnum in 1.0 0.0))
  ([bufnum in mul] (index:ar bufnum in mul 0.0))
  ([bufnum in mul add]
   (op/madd (ugen/multinew (fn [cls bufnum in] (ugen/ugen-new "Index" :audio cls no-check :bipolar bufnum in))
                           'pure-ugen bufnum in)
            mul add)))

(defn index:kr
  "Port of Osc.lisp (index \"Index\")."
  ([bufnum] (index:kr bufnum 0.0 1.0 0.0))
  ([bufnum in] (index:kr bufnum in 1.0 0.0))
  ([bufnum in mul] (index:kr bufnum in mul 0.0))
  ([bufnum in mul add]
   (op/madd (ugen/multinew (fn [cls bufnum in] (ugen/ugen-new "Index" :control cls no-check :bipolar bufnum in))
                           'pure-ugen bufnum in)
            mul add)))

(defn wrap-index:ar
  "Port of Osc.lisp (wrap-index \"WrapIndex\")."
  ([bufnum] (wrap-index:ar bufnum 0.0 1.0 0.0))
  ([bufnum in] (wrap-index:ar bufnum in 1.0 0.0))
  ([bufnum in mul] (wrap-index:ar bufnum in mul 0.0))
  ([bufnum in mul add]
   (op/madd (ugen/multinew (fn [cls bufnum in] (ugen/ugen-new "WrapIndex" :audio cls no-check :bipolar bufnum in))
                           'pure-ugen bufnum in)
            mul add)))

(defn wrap-index:kr
  "Port of Osc.lisp (wrap-index \"WrapIndex\")."
  ([bufnum] (wrap-index:kr bufnum 0.0 1.0 0.0))
  ([bufnum in] (wrap-index:kr bufnum in 1.0 0.0))
  ([bufnum in mul] (wrap-index:kr bufnum in mul 0.0))
  ([bufnum in mul add]
   (op/madd (ugen/multinew (fn [cls bufnum in] (ugen/ugen-new "WrapIndex" :control cls no-check :bipolar bufnum in))
                           'pure-ugen bufnum in)
            mul add)))

(defn index-in-between:ar
  "Port of Osc.lisp (index-in-between \"IndexInBetween\")."
  ([bufnum] (index-in-between:ar bufnum 0.0 1.0 0.0))
  ([bufnum in] (index-in-between:ar bufnum in 1.0 0.0))
  ([bufnum in mul] (index-in-between:ar bufnum in mul 0.0))
  ([bufnum in mul add]
   (op/madd (ugen/multinew (fn [cls bufnum in] (ugen/ugen-new "IndexInBetween" :audio cls no-check :bipolar bufnum in))
                           'pure-ugen bufnum in)
            mul add)))

(defn index-in-between:kr
  "Port of Osc.lisp (index-in-between \"IndexInBetween\")."
  ([bufnum] (index-in-between:kr bufnum 0.0 1.0 0.0))
  ([bufnum in] (index-in-between:kr bufnum in 1.0 0.0))
  ([bufnum in mul] (index-in-between:kr bufnum in mul 0.0))
  ([bufnum in mul add]
   (op/madd (ugen/multinew (fn [cls bufnum in] (ugen/ugen-new "IndexInBetween" :control cls no-check :bipolar bufnum in))
                           'pure-ugen bufnum in)
            mul add)))

(defn detect-index:ar
  "Port of Osc.lisp (detect-index \"DetectIndex\")."
  ([bufnum] (detect-index:ar bufnum 0.0 1.0 0.0))
  ([bufnum in] (detect-index:ar bufnum in 1.0 0.0))
  ([bufnum in mul] (detect-index:ar bufnum in mul 0.0))
  ([bufnum in mul add]
   (op/madd (ugen/multinew (fn [cls bufnum in] (ugen/ugen-new "DetectIndex" :audio cls no-check :bipolar bufnum in))
                           'pure-ugen bufnum in)
            mul add)))

(defn detect-index:kr
  "Port of Osc.lisp (detect-index \"DetectIndex\")."
  ([bufnum] (detect-index:kr bufnum 0.0 1.0 0.0))
  ([bufnum in] (detect-index:kr bufnum in 1.0 0.0))
  ([bufnum in mul] (detect-index:kr bufnum in mul 0.0))
  ([bufnum in mul add]
   (op/madd (ugen/multinew (fn [cls bufnum in] (ugen/ugen-new "DetectIndex" :control cls no-check :bipolar bufnum in))
                           'pure-ugen bufnum in)
            mul add)))

(defn shaper:ar
  "Port of Osc.lisp (shaper \"Shaper\")."
  ([bufnum] (shaper:ar bufnum 0.0 1.0 0.0))
  ([bufnum in] (shaper:ar bufnum in 1.0 0.0))
  ([bufnum in mul] (shaper:ar bufnum in mul 0.0))
  ([bufnum in mul add]
   (op/madd (ugen/multinew (fn [cls bufnum in] (ugen/ugen-new "Shaper" :audio cls no-check :bipolar bufnum in))
                           'pure-ugen bufnum in)
            mul add)))

(defn shaper:kr
  "Port of Osc.lisp (shaper \"Shaper\")."
  ([bufnum] (shaper:kr bufnum 0.0 1.0 0.0))
  ([bufnum in] (shaper:kr bufnum in 1.0 0.0))
  ([bufnum in mul] (shaper:kr bufnum in mul 0.0))
  ([bufnum in mul add]
   (op/madd (ugen/multinew (fn [cls bufnum in] (ugen/ugen-new "Shaper" :control cls no-check :bipolar bufnum in))
                           'pure-ugen bufnum in)
            mul add)))

(defn index-l:ar
  "Port of Osc.lisp (index-l \"IndexL\")."
  ([bufnum] (index-l:ar bufnum 0.0 1.0 0.0))
  ([bufnum in] (index-l:ar bufnum in 1.0 0.0))
  ([bufnum in mul] (index-l:ar bufnum in mul 0.0))
  ([bufnum in mul add]
   (op/madd (ugen/multinew (fn [cls bufnum in] (ugen/ugen-new "IndexL" :audio cls no-check :bipolar bufnum in))
                           'pure-ugen bufnum in)
            mul add)))

(defn index-l:kr
  "Port of Osc.lisp (index-l \"IndexL\")."
  ([bufnum] (index-l:kr bufnum 0.0 1.0 0.0))
  ([bufnum in] (index-l:kr bufnum in 1.0 0.0))
  ([bufnum in mul] (index-l:kr bufnum in mul 0.0))
  ([bufnum in mul add]
   (op/madd (ugen/multinew (fn [cls bufnum in] (ugen/ugen-new "IndexL" :control cls no-check :bipolar bufnum in))
                           'pure-ugen bufnum in)
            mul add)))

(defn degree-to-key:ar
  "Port of Osc.lisp (degree-to-key \"DegreeToKey\")."
  ([bufnum] (degree-to-key:ar bufnum 0.0 12.0 1.0 0.0))
  ([bufnum in] (degree-to-key:ar bufnum in 12.0 1.0 0.0))
  ([bufnum in octave] (degree-to-key:ar bufnum in octave 1.0 0.0))
  ([bufnum in octave mul] (degree-to-key:ar bufnum in octave mul 0.0))
  ([bufnum in octave mul add]
   (op/madd (ugen/multinew (fn [cls bufnum in octave] (ugen/ugen-new "DegreeToKey" :audio cls no-check :bipolar bufnum in octave))
                           'pure-ugen bufnum in octave)
            mul add)))

(defn degree-to-key:kr
  "Port of Osc.lisp (degree-to-key \"DegreeToKey\")."
  ([bufnum] (degree-to-key:kr bufnum 0.0 12.0 1.0 0.0))
  ([bufnum in] (degree-to-key:kr bufnum in 12.0 1.0 0.0))
  ([bufnum in octave] (degree-to-key:kr bufnum in octave 1.0 0.0))
  ([bufnum in octave mul] (degree-to-key:kr bufnum in octave mul 0.0))
  ([bufnum in octave mul add]
   (op/madd (ugen/multinew (fn [cls bufnum in octave] (ugen/ugen-new "DegreeToKey" :control cls no-check :bipolar bufnum in octave))
                           'pure-ugen bufnum in octave)
            mul add)))

(defn vibrato:ar
  "Port of Osc.lisp (vibrato \"Vibrato\")."
  ([] (vibrato:ar 440.0 6 0.02 0.0 0.0 0.04 0.1 0.0))
  ([freq] (vibrato:ar freq 6 0.02 0.0 0.0 0.04 0.1 0.0))
  ([freq frate] (vibrato:ar freq frate 0.02 0.0 0.0 0.04 0.1 0.0))
  ([freq frate depth] (vibrato:ar freq frate depth 0.0 0.0 0.04 0.1 0.0))
  ([freq frate depth delay] (vibrato:ar freq frate depth delay 0.0 0.04 0.1 0.0))
  ([freq frate depth delay onset] (vibrato:ar freq frate depth delay onset 0.04 0.1 0.0))
  ([freq frate depth delay onset rate-variation] (vibrato:ar freq frate depth delay onset rate-variation 0.1 0.0))
  ([freq frate depth delay onset rate-variation depth-variation] (vibrato:ar freq frate depth delay onset rate-variation depth-variation 0.0))
  ([freq frate depth delay onset rate-variation depth-variation iphase]
   (ugen/multinew (fn [cls freq frate depth delay onset rate-variation depth-variation iphase] (ugen/ugen-new "Vibrato" :audio cls no-check :bipolar freq frate depth delay onset rate-variation depth-variation iphase))
                           'pure-ugen freq frate depth delay onset rate-variation depth-variation iphase)))

(defn vibrato:kr
  "Port of Osc.lisp (vibrato \"Vibrato\")."
  ([] (vibrato:kr 440.0 6 0.02 0.0 0.0 0.04 0.1 0.0))
  ([freq] (vibrato:kr freq 6 0.02 0.0 0.0 0.04 0.1 0.0))
  ([freq frate] (vibrato:kr freq frate 0.02 0.0 0.0 0.04 0.1 0.0))
  ([freq frate depth] (vibrato:kr freq frate depth 0.0 0.0 0.04 0.1 0.0))
  ([freq frate depth delay] (vibrato:kr freq frate depth delay 0.0 0.04 0.1 0.0))
  ([freq frate depth delay onset] (vibrato:kr freq frate depth delay onset 0.04 0.1 0.0))
  ([freq frate depth delay onset rate-variation] (vibrato:kr freq frate depth delay onset rate-variation 0.1 0.0))
  ([freq frate depth delay onset rate-variation depth-variation] (vibrato:kr freq frate depth delay onset rate-variation depth-variation 0.0))
  ([freq frate depth delay onset rate-variation depth-variation iphase]
   (ugen/multinew (fn [cls freq frate depth delay onset rate-variation depth-variation iphase] (ugen/ugen-new "Vibrato" :control cls no-check :bipolar freq frate depth delay onset rate-variation depth-variation iphase))
                           'pure-ugen freq frate depth delay onset rate-variation depth-variation iphase)))

;; ---- hand customs: select / crossfade-select / triggered-choose / pm-osc ----

(defn select:ar
  "Port of Osc.lisp (select \"Select\")."
  [which array]
  (let [w (cond (true? which) 1 (nil? which) 0 :else which)]
    (ugen/multinew-list (fn [cls & args] (apply ugen/ugen-new "Select" :audio cls no-check :bipolar args))
                        'pure-ugen (into [w] array))))

(defn select:kr
  "Port of Osc.lisp (select \"Select\")."
  [which array]
  (ugen/multinew-list (fn [cls & args] (apply ugen/ugen-new "Select" :control cls no-check :bipolar args))
                      'pure-ugen (into [which] array)))

(defn select-x:ar
  "Port of Osc.lisp (select-x \"SelectX\")."
  ([which array] (select-x:ar which array 1.0))
  ([which array _wrap]
   (pan/x-fade2:ar (select:ar (op/round which 2) array)
                   (select:ar (op/+ (op/trunc which 2) 1) array)
                   (op/fold2 (op/- (op/* which 2) 1) 1))))

(defn select-x:kr
  "Port of Osc.lisp (select-x \"SelectX\")."
  ([which array] (select-x:kr which array 1.0))
  ([which array _wrap]
   (pan/x-fade2:kr (select:kr (op/round which 2) array)
                   (select:kr (op/+ (op/trunc which 2) 1) array)
                   (op/fold2 (op/- (op/* which 2) 1) 1))))

(defn lin-select-x:ar
  "Port of Osc.lisp (lin-select-x \"LinSelectX\"). LinXFade2 is inlined
  (pan.clj ports only XFade2); mirrors Pan.lisp level-1.0 elision."
  ([which array] (lin-select-x:ar which array 1.0))
  ([which array _wrap]
   (ugen/multinew (fn [cls in-a in-b pan] (ugen/ugen-new "LinXFade2" :audio cls no-check :bipolar in-a in-b pan))
                  'ugen
                  (select:ar (op/round which 2) array)
                  (select:ar (op/+ (op/trunc which 2) 1) array)
                  (op/fold2 (op/- (op/* which 2) 1) 1))))

(defn lin-select-x:kr
  "Port of Osc.lisp (lin-select-x \"LinSelectX\"). LinXFade2 is inlined
  (pan.clj ports only XFade2); mirrors Pan.lisp level-1.0 elision."
  ([which array] (lin-select-x:kr which array 1.0))
  ([which array _wrap]
   (ugen/multinew (fn [cls in-a in-b pan] (ugen/ugen-new "LinXFade2" :control cls no-check :bipolar in-a in-b pan))
                  'ugen
                  (select:kr (op/round which 2) array)
                  (select:kr (op/+ (op/trunc which 2) 1) array)
                  (op/fold2 (op/- (op/* which 2) 1) 1))))

(defn select-x-focus:ar
  "Port of Osc.lisp (select-x-focus \"SelectXFocus\")."
  ([which array] (select-x-focus:ar which array 1.0))
  ([which array focus]
   (op/mix (mapv (fn [[i input]]
                   (op/* (op/max (op/- 1 (op/* (op/abs (op/- which i)) focus)) 0)
                         input))
                 (map-indexed vector array)))))

(defn select-x-focus:kr
  "Port of Osc.lisp (select-x-focus \"SelectXFocus\")."
  ([which array] (select-x-focus:kr which array 1.0))
  ([which array focus]
   (op/mix (mapv (fn [[i input]]
                   (op/* (op/max (op/- 1 (op/* (op/abs (op/- which i)) focus)) 0)
                         input))
                 (map-indexed vector array)))))

(defn t-choose:ar
  "Port of Osc.lisp (t-choose \"TChoose\")."
  [trig array]
  (select:ar (noise/ti-rand:ar 0 (dec (count array)) trig) array))

(defn t-choose:kr
  "Port of Osc.lisp (t-choose \"TChoose\")."
  [trig array]
  (select:kr (noise/ti-rand:kr 0 (dec (count array)) trig) array))

(defn tw-choose:ar
  "Port of Osc.lisp (tw-choose \"TWChoose\")."
  ([trig array weights] (tw-choose:ar trig array weights 0))
  ([trig array weights normalize]
   (select:ar (noise/tw-index:ar trig weights normalize) array)))

(defn tw-choose:kr
  "Port of Osc.lisp (tw-choose \"TWChoose\")."
  ([trig array weights] (tw-choose:kr trig array weights 0))
  ([trig array weights normalize]
   (select:kr (noise/tw-index:kr trig weights normalize) array)))

(defn pm-osc:ar
  "Phase-modulation composition from Osc.lisp (pm-osc.ar)."
  ([car-freq mod-freq] (pm-osc:ar car-freq mod-freq 0.0 0.0 1.0 0.0))
  ([car-freq mod-freq pm-index] (pm-osc:ar car-freq mod-freq pm-index 0.0 1.0 0.0))
  ([car-freq mod-freq pm-index mod-phase] (pm-osc:ar car-freq mod-freq pm-index mod-phase 1.0 0.0))
  ([car-freq mod-freq pm-index mod-phase mul] (pm-osc:ar car-freq mod-freq pm-index mod-phase mul 0.0))
  ([car-freq mod-freq pm-index mod-phase mul add]
   (sin-osc:ar car-freq (sin-osc:ar mod-freq mod-phase pm-index 0.0) mul add)))

(defn pm-osc:kr
  "Phase-modulation composition from Osc.lisp (pm-osc.kr)."
  ([car-freq mod-freq] (pm-osc:kr car-freq mod-freq 0.0 0.0 1.0 0.0))
  ([car-freq mod-freq pm-index] (pm-osc:kr car-freq mod-freq pm-index 0.0 1.0 0.0))
  ([car-freq mod-freq pm-index mod-phase] (pm-osc:kr car-freq mod-freq pm-index mod-phase 1.0 0.0))
  ([car-freq mod-freq pm-index mod-phase mul] (pm-osc:kr car-freq mod-freq pm-index mod-phase mul 0.0))
  ([car-freq mod-freq pm-index mod-phase mul add]
   (sin-osc:kr car-freq (sin-osc:kr mod-freq mod-phase pm-index 0.0) mul add)))
