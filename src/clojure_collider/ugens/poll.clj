(ns clojure-collider.ugens.poll
  "Port of cl-collider ugens/Poll.lisp (custom poll with trigger coercion).
   Number triggers coerce via osc/impulse:kr; the label encodes as char
   codes with its length (upstream poll-ugen new1). The unit is built for
   its side effect; the fns return `in`, like upstream."
  (:require [clojure-collider.synth.ugen :as ugen]
            [clojure-collider.ugens.osc :as osc]))

(defn- coerce-trig
  "Upstream etypecase: numbers become kr impulses, ugens pass through."
  [trig]
  (let [trs (if (sequential? trig) (vec trig) [trig])]
    (ugen/unbubble (mapv #(if (number? %) (osc/impulse:kr %) %) trs))))

(defn- poll-with
  [rate trig in label trigid]
  (let [tr (coerce-trig trig)]
    (ugen/multinew
     (fn [cls tr inn lab tid]
       (let [s (if (nil? lab) (str "UGen(" inn ")") (str lab))
             codes (mapv int s)]
         (apply ugen/ugen-new "Poll" rate cls
                ugen/check-same-rate-as-first-input :bipolar
                tr inn tid (count codes) codes)))
     'poll-ugen tr in label trigid)
    in))

(defn poll:ar
  "Port of Poll.lisp (poll \"Poll\")."
  ([] (poll:ar 0 0.0 nil -1))
  ([trig] (poll:ar trig 0.0 nil -1))
  ([trig in] (poll:ar trig in nil -1))
  ([trig in label] (poll:ar trig in label -1))
  ([trig in label trigid]
   (poll-with :audio trig in label trigid)))

(defn poll:kr
  "Port of Poll.lisp (poll \"Poll\")."
  ([] (poll:kr 0 0.0 nil -1))
  ([trig] (poll:kr trig 0.0 nil -1))
  ([trig in] (poll:kr trig in nil -1))
  ([trig in label] (poll:kr trig in label -1))
  ([trig in label trigid]
   (poll-with :control trig in label trigid)))
