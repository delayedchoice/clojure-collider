(ns clojure-collider.ugens.splay
  "Port of cl-collider ugens/Splay.lisp (custom defugen + helpers)."
  (:require [clojure-collider.synth.ugen :as ugen]
            [clojure-collider.synth.operators :as op]
            [clojure-collider.ugens.pan :as pan]))

(defn- splay-new
  "Port of Splay.lisp splay-new (pan2 composition)."
  [rate spread level center level-comp & in-array]
  (let [n (max 2 (count in-array))
        n1 (dec n)
        idx (mapv #(* % (/ 2.0 n1)) (range n))
        position (op/+ (op/* (op/- idx 1) spread) center)
        level (if level-comp
                (if (= rate :audio)
                  (op/* level (op/sqrt (op/reciprocal n)))
                  (op// level n))
                level)
        chans (vec in-array)
        panned (if (= rate :audio)
                 (pan/pan2:ar chans position)
                 (pan/pan2:kr chans position))]
    (op/* (op/sum panned) level)))

(defn splay:ar
  "Port of Splay.lisp (splay \"Splay\")."
  ([in-array] (splay:ar in-array 1 1 0.0 true))
  ([in-array spread] (splay:ar in-array spread 1 0.0 true))
  ([in-array spread level] (splay:ar in-array spread level 0.0 true))
  ([in-array spread level center] (splay:ar in-array spread level center true))
  ([in-array spread level center level-comp]
   (let [chs (if (sequential? in-array) (vec in-array) [in-array])]
     (apply ugen/multinew
            (fn [_cls sp lv ct lc & ins] (apply splay-new :audio sp lv ct lc ins))
            'pure-ugen spread level center level-comp chs))))

(defn splay:kr
  "Port of Splay.lisp (splay \"Splay\")."
  ([in-array] (splay:kr in-array 1 1 0.0 true))
  ([in-array spread] (splay:kr in-array spread 1 0.0 true))
  ([in-array spread level] (splay:kr in-array spread level 0.0 true))
  ([in-array spread level center] (splay:kr in-array spread level center true))
  ([in-array spread level center level-comp]
   (let [chs (if (sequential? in-array) (vec in-array) [in-array])]
     (apply ugen/multinew
            (fn [_cls sp lv ct lc & ins] (apply splay-new :control sp lv ct lc ins))
            'pure-ugen spread level center level-comp chs))))

(defn splay-fill
  "Port of Splay.lisp splay-fill."
  ([n function] (splay-fill n function 1 1 0.0 true))
  ([n function spread] (splay-fill n function spread 1 0.0 true))
  ([n function spread level] (splay-fill n function spread level 0.0 true))
  ([n function spread level center] (splay-fill n function spread level center true))
  ([n function spread level center level-comp]
   (splay:ar (op/dup function n) spread level center level-comp)))
