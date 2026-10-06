(ns clojure-collider.ugens.sound-in
  "Port of cl-collider ugens/SoundIn.lisp (custom defuns)."
  (:require [clojure-collider.synth.operators :as op]
            [clojure-collider.ugens.in-out :as inout]
            [clojure-collider.ugens.info-ugens :as info]))

(def ^:private num-output-buses:ir info/num-output-buses:ir)

(defn- sound-in-prim
  "Port of SoundIn.lisp sound-in-prim."
  [chan-offset bus mul add]
  (if (not (sequential? bus))
    (op/madd (inout/in:ar (op/+ chan-offset bus) 1) mul add)
    (if (= (vec bus) (vec (range (first bus) (+ (first bus) (count bus)))))
      (op/madd (inout/in:ar (op/+ chan-offset (first bus)) (count bus)) mul add)
      (op/madd (inout/in:ar (op/+ chan-offset bus)) mul add))))

(defn sound-in:ar
  "Port of SoundIn.lisp sound-in.ar."
  ([] (sound-in:ar 0))
  ([bus] (sound-in:ar bus 1.0))
  ([bus mul] (sound-in:ar bus mul 0.0))
  ([bus mul add]
   (sound-in-prim (num-output-buses:ir) bus mul add)))

(defn audio-in:ar
  "Port of SoundIn.lisp audio-in.ar."
  ([] (audio-in:ar 0))
  ([channel] (audio-in:ar channel 1.0))
  ([channel mul] (audio-in:ar channel mul 0.0))
  ([channel mul add]
   (sound-in-prim (op/- (num-output-buses:ir) 1) channel mul add)))
