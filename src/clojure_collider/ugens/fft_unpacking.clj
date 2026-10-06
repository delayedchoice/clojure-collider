(ns clojure-collider.ugens.fft-unpacking
  "Port of cl-collider ugens/FFTUnpacking.lisp (custom FFT chain helpers).
  Upstream builds these on the pv-chain-ugen (width-first) class; they are
  constructed with plain 'ugen flavor here until width-first antecedent
  tracking lands (see pack-fft note). No mul/add wrappers upstream."
  (:require [clojure-collider.synth.ugen :as ugen]
            [clojure-collider.synth.operators :as op]))

(def ^:private no-check (fn [_] nil))

(defn unpack-1-fft
  "Port of FFTUnpacking.lisp (unpack-1-fft \"Unpack1FFT\"). Demand-rate;
   binindex may be a single bin or a sequential collection (multinew expands)."
  ([chain bufsize binindex] (unpack-1-fft chain bufsize binindex 0))
  ([chain bufsize binindex whichmeasure]
   (ugen/multinew (fn [cls chain bufsize binindex whichmeasure]
                    (ugen/ugen-new "Unpack1FFT" :demand cls no-check :bipolar
                                   chain bufsize binindex whichmeasure))
                  'ugen chain bufsize binindex whichmeasure)))

(defn unpack-fft
  "Port of FFTUnpacking.lisp unpack-fft: flat [mag phase ...] pairs over
   bins frombin..tobin (inclusive, clamped to bufsize/2)."
  ([chain bufsize] (unpack-fft chain bufsize 0 nil))
  ([chain bufsize frombin] (unpack-fft chain bufsize frombin nil))
  ([chain bufsize frombin tobin]
   (let [upper (/ bufsize 2.0)
         tobin (if (nil? tobin) upper (min tobin upper))
         bins (vec (range frombin (inc (long tobin))))
         mags (unpack-1-fft chain bufsize bins 0)
         phases (unpack-1-fft chain bufsize bins 1)]
     (vec (flatten (op/flop [mags phases]))))))

(defn pack-fft
  "Port of FFTUnpacking.lisp (pack-fft \"PackFFT\"). Control-rate.
   NOTE: upstream class is pv-chain-ugen (width-first); constructed with
   plain 'ugen flavor for now (construct maps both to the same record)."
  ([chain bufsize magsphases]
   (pack-fft chain bufsize magsphases :frombin 0 :tobin nil :zeroothers 0))
  ([chain bufsize magsphases & {:keys [frombin tobin zeroothers]
                                :or {frombin 0 tobin nil zeroothers 0}}]
   (let [tobin (if (nil? tobin) (/ bufsize 2.0) tobin)
         mags (if (sequential? magsphases) (vec magsphases) [magsphases])
         n (count mags)]
     (ugen/multinew-list
      (fn [cls chain bufsize frombin tobin zeroothers n & mps]
        (apply ugen/ugen-new "PackFFT" :control cls no-check :bipolar
               chain bufsize frombin tobin zeroothers n mps))
      'ugen
      (into [chain bufsize frombin tobin zeroothers n] mags)))))

(defn- normalize-mags
  "Mirror the CL case on (length magsphases): 1 result keeps the original
   phases, 2 results pass through, anything else is treated as mags with
   the original phases."
  [res orig-phases]
  (let [m (if (sequential? res) (vec res) [res])
        orig (if (sequential? orig-phases) (vec orig-phases) [orig-phases])]
    (case (count m)
      1 (vec (concat m orig))
      2 m
      [res orig-phases])))

(defn pv-calc
  "Port of FFTUnpacking.lisp pv-calc: unpack, apply func to [mags phases],
   repack. func takes (mags phases) and returns mags, [mags phases], or a
   mags collection paired with the original phases."
  ([pv-ugen frames func]
   (pv-calc pv-ugen frames func :frombin 0 :tobin nil :zeroothers 0))
  ([pv-ugen frames func & {:keys [frombin tobin zeroothers]
                           :or {frombin 0 tobin nil zeroothers 0}}]
   (let [pairs (op/flop (op/clump (unpack-fft pv-ugen frames frombin tobin) 2))
         orig-mags (first pairs)
         orig-phases (second pairs)
         norm (normalize-mags (func orig-mags orig-phases) orig-phases)
         flat (vec (flatten (op/flop norm)))]
     (pack-fft pv-ugen frames flat :frombin frombin :tobin tobin :zeroothers zeroothers))))

(defn pv-calc2
  "Port of FFTUnpacking.lisp pv-calc2: like pv-calc over two chains; func
   takes (mags1 phases1 mags2 phases2)."
  ([pv-ugen chain2 frames func]
   (pv-calc2 pv-ugen chain2 frames func :frombin 0 :tobin nil :zeroothers 0))
  ([pv-ugen chain2 frames func & {:keys [frombin tobin zeroothers]
                                  :or {frombin 0 tobin nil zeroothers 0}}]
   (let [pairs1 (op/flop (op/clump (unpack-fft pv-ugen frames frombin tobin) 2))
         pairs2 (op/flop (op/clump (unpack-fft chain2 frames frombin tobin) 2))
         norm (normalize-mags (func (first pairs1) (second pairs1)
                                    (first pairs2) (second pairs2))
                              (second pairs1))
         flat (vec (flatten (op/flop norm)))]
     (pack-fft pv-ugen frames flat :frombin frombin :tobin tobin :zeroothers zeroothers))))

(defn pv-collect
  "Port of FFTUnpacking.lisp pv-collect: apply func per bin to
   (mag phase bin-index), collect single returns with the original phase."
  ([pv-ugen frames func]
   (pv-collect pv-ugen frames func :frombin 0 :tobin nil :zeroothers 0))
  ([pv-ugen frames func & {:keys [frombin tobin zeroothers]
                           :or {frombin 0 tobin nil zeroothers 0}}]
   (let [clumps (op/clump (unpack-fft pv-ugen frames frombin tobin) 2)
         flat (vec (mapcat (fn [mp idx]
                             (let [[m p] mp
                                   ret (func m p (+ frombin idx))
                                   r (if (sequential? ret) (vec ret) [ret])]
                               (if (= 1 (count r)) (conj r p) r)))
                           clumps (range)))]
     (pack-fft pv-ugen frames flat :frombin frombin :tobin tobin :zeroothers zeroothers))))
