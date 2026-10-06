(ns clojure-collider.ugens.in-out
  "In/Out UGens. Mirrors ugens/InOut.lisp: In (multiout proxies), Out/XOut
   (abstract-out, channels spliced into ONE unit via apply-multinew,
   audio-rate input checks, zero->silence on :ar, scalar 0 return)."
  (:require [clojure-collider.synth.ugen :as ugen]
            [clojure-collider.ugens.line :as line]))

(def ^:private no-out-cls 'abstract-out)

(defn- abstract-out-check
  "abstract-out-check: when the unit is audio rate, inputs from FIXED-ARGS
   on must be audio rate."
  [fixed-args]
  (fn [u]
    (when (= :audio (:rate u))
      (doseq [[i inp] (map-indexed vector (:inputs u))]
        (when (and (>= i fixed-args) (not= :audio (ugen/rate inp)))
          (throw (ex-info (str "Input at index " i " is not audio rate.")
                          {:ugen (:name u) :index i})))))))

(defn- replace-zeroes-with-silence
  "Numeric 0 channels become Silent outputs (InOut.lisp helper, :ar only)."
  [channels]
  (let [ch (vec (if (sequential? channels) channels [channels]))
        zeros (filter #(and (number? %) (zero? %)) ch)]
    (if (empty? zeros)
      ch
      (let [s (line/silent:ar (count zeros))
            silent-ch (if (sequential? s) (vec s) [s])]
        (loop [items ch i 0 pos 0 out []]
          (if (>= i (count items))
            out
            (let [item (nth items i)]
              (cond (and (number? item) (zero? item))
                    (recur items (inc i) (inc pos) (conj out (nth silent-ch pos)))
                    (sequential? item)
                    (recur items (inc i) pos
                           (conj out (replace-zeroes-with-silence item)))
                    :else (recur items (inc i) pos (conj out item))))))))))

(defn in:ar
  ([bus] (in:ar bus 1))
  ([bus chanls]
   (ugen/unbubble
    (ugen/multinew (fn [_cls ch b] (ugen/multiout-new "In" :audio :bipolar ch b))
                   'multiout-ugen chanls bus))))

(defn in:kr
  ([bus] (in:kr bus 1))
  ([bus chanls]
   (ugen/unbubble
    (ugen/multinew (fn [_cls ch b] (ugen/multiout-new "In" :control :bipolar ch b))
                   'multiout-ugen chanls bus))))

(defn- ensure-channels [sig]
  (vec (if (sequential? sig) sig [sig])))

(defn out:ar [bus sig]
  ;; upstream (apply multinew ... (cons bus channels)): channels splice
  ;; into ONE unit; expansion only pairs a sequential bus across them.
  (ugen/unbubble
   (ugen/multinew-list (fn [cls & args]
                         (apply ugen/ugen-new "Out" :audio cls
                                (abstract-out-check 1) :bipolar args))
                       no-out-cls (into [bus] (ensure-channels sig))))
  0)

(defn out:kr [bus sig]
  (ugen/unbubble
   (ugen/multinew-list (fn [cls & args]
                         (apply ugen/ugen-new "Out" :control cls
                                (abstract-out-check 1) :bipolar args))
                       no-out-cls (into [bus] (ensure-channels sig))))
  0)

(defn x-out:ar [bus xfade sig]
  (ugen/unbubble
   (ugen/multinew-list
    (fn [cls & args]
      (apply ugen/ugen-new "XOut" :audio cls
             (abstract-out-check 2) :bipolar args))
    no-out-cls
    (into [bus xfade] (replace-zeroes-with-silence (ensure-channels sig)))))
  0)

(defn x-out:kr [bus xfade sig]
  (ugen/unbubble
   (ugen/multinew-list (fn [cls & args]
                         (apply ugen/ugen-new "XOut" :control cls
                                (abstract-out-check 2) :bipolar args))
                       no-out-cls (into [bus xfade] (ensure-channels sig))))
  0)


;; ---- generated mechanical forms (scripts/emit_ugens.clj) ----

(defn local-in:ar
  "Port of InOut.lisp (local-in \"LocalIn\")."
  ([] (local-in:ar 1 0.0))
  ([chanls] (local-in:ar chanls 0.0))
  ([chanls default]
   (ugen/multinew (fn [_cls chanls default] (ugen/multiout-new "LocalIn" :audio :bipolar chanls default))
                           'multiout-ugen chanls default)))

(defn local-in:kr
  "Port of InOut.lisp (local-in \"LocalIn\")."
  ([] (local-in:kr 1 0.0))
  ([chanls] (local-in:kr chanls 0.0))
  ([chanls default]
   (ugen/multinew (fn [_cls chanls default] (ugen/multiout-new "LocalIn" :control :bipolar chanls default))
                           'multiout-ugen chanls default)))

(defn lag-in:kr
  "Port of InOut.lisp (lag-in \"LagIn\")."
  ([] (lag-in:kr 0 1 0.1))
  ([bus] (lag-in:kr bus 1 0.1))
  ([bus chanls] (lag-in:kr bus chanls 0.1))
  ([bus chanls lag]
   (ugen/multinew (fn [_cls chanls bus lag] (ugen/multiout-new "LagIn" :control :bipolar chanls bus lag))
                           'multiout-ugen chanls bus lag)))

(defn in-feedback:ar
  "Port of InOut.lisp (in-feedback \"InFeedback\")."
  ([] (in-feedback:ar 0 1))
  ([bus] (in-feedback:ar bus 1))
  ([bus chanls]
   (ugen/multinew (fn [_cls chanls bus] (ugen/multiout-new "InFeedback" :audio :bipolar chanls bus))
                           'multiout-ugen chanls bus)))

(defn in-trig:kr
  "Port of InOut.lisp (in-trig \"InTrig\")."
  ([] (in-trig:kr 0 1))
  ([bus] (in-trig:kr bus 1))
  ([bus chanls]
   (ugen/multinew (fn [_cls chanls bus] (ugen/multiout-new "InTrig" :control :bipolar chanls bus))
                           'multiout-ugen chanls bus)))

(defn replace-out:ar [bus sig]
  (ugen/unbubble
   (ugen/multinew-list (fn [cls & args]
                         (apply ugen/ugen-new "ReplaceOut" :audio cls
                                (abstract-out-check 1) :bipolar args))
                       no-out-cls (into [bus] (replace-zeroes-with-silence (ensure-channels sig)))))
  0)

(defn replace-out:kr [bus sig]
  (ugen/unbubble
   (ugen/multinew-list (fn [cls & args]
                         (apply ugen/ugen-new "ReplaceOut" :control cls
                                (abstract-out-check 1) :bipolar args))
                       no-out-cls (into [bus] (ensure-channels sig))))
  0)

(defn offset-out:ar [bus sig]
  (ugen/unbubble
   (ugen/multinew-list (fn [cls & args]
                         (apply ugen/ugen-new "OffsetOut" :audio cls
                                (abstract-out-check 1) :bipolar args))
                       no-out-cls (into [bus] (replace-zeroes-with-silence (ensure-channels sig)))))
  0)

(defn offset-out:kr [bus sig]
  (throw (ex-info (str "Control rate OffsetOut is not implemented (bus: " bus " channels-array: " sig ").")
                  {:bus bus :sig sig})))

(defn local-out:ar [sig]
  (ugen/unbubble
   (ugen/multinew-list (fn [cls & args]
                         (apply ugen/ugen-new "LocalOut" :audio cls
                                (abstract-out-check 0) :bipolar args))
                       no-out-cls (vec (replace-zeroes-with-silence (ensure-channels sig)))))
  0)

(defn local-out:kr [sig]
  (ugen/unbubble
   (ugen/multinew-list (fn [cls & args]
                         (apply ugen/ugen-new "LocalOut" :control cls
                                (abstract-out-check 0) :bipolar args))
                       no-out-cls (vec (ensure-channels sig))))
  0)
