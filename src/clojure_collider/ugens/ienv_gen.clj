(ns clojure-collider.ugens.ienv-gen
  "Port of cl-collider ugens/IEnvGen.lisp (custom defugen + helper)."
  (:require [clojure-collider.synth.ugen :as ugen]
            [clojure-collider.synth.operators :as op]
            [clojure-collider.ugens.env-gen :as envgen]))

(defn- nth-wrap [i coll] (nth coll (mod i (count coll))))

(defn as-array-for-interpolation
  "Port of IEnvGen.lisp as-array-for-interpolation. Converts an Env record
   into an opaque single-channel array (one EnvArray value, broadcast by
   multinew) suitable for i-env-gen."
  [env]
  (let [levels (:levels env)
        times (:times env)
        curve-number (:curve-number env)
        curve-value (:curve-value env)
        size (count times)
        flat (vec (concat [0 (nth levels 0) size (reduce clojure.core/+ times)]
                          (mapcat (fn [i] [(nth times i)
                                           (nth-wrap i curve-number)
                                           (nth-wrap i curve-value)
                                           (nth levels (inc i))])
                                  (range size))))]
    (envgen/->EnvArray flat)))

(defn i-env-gen:ar
  "Port of IEnvGen.lisp (i-env-gen \"IEnvGen\")."
  ([envelope index] (i-env-gen:ar envelope index 1.0 0.0))
  ([envelope index mul] (i-env-gen:ar envelope index mul 0.0))
  ([envelope index mul add]
   (op/madd (ugen/unbubble
             (ugen/multinew
              ;; splice BEFORE add: records are values, so the graph must store
              ;; the processed copy (same as env-gen*).
              (fn [_ idx arr]
                (ugen/add-to-synth
                 (envgen/process-env
                  (ugen/construct "IEnvGen" :audio 'ugen nil :bipolar 0 [idx arr]))))
              nil index (as-array-for-interpolation envelope)))
            mul add)))

(defn i-env-gen:kr
  "Port of IEnvGen.lisp (i-env-gen \"IEnvGen\")."
  ([envelope index] (i-env-gen:kr envelope index 1.0 0.0))
  ([envelope index mul] (i-env-gen:kr envelope index mul 0.0))
  ([envelope index mul add]
   (op/madd (ugen/unbubble
             (ugen/multinew
              ;; splice BEFORE add: records are values, so the graph must store
              ;; the processed copy (same as env-gen*).
              (fn [_ idx arr]
                (ugen/add-to-synth
                 (envgen/process-env
                  (ugen/construct "IEnvGen" :control 'ugen nil :bipolar 0 [idx arr]))))
              nil index (as-array-for-interpolation envelope)))
            mul add)))
