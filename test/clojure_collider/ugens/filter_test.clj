(ns clojure-collider.ugens.filter-test
  "VarLag fidelity: non-:lin warp builds the upstream EnvGen graph;
   :lin warp emits the bare VarLag unit. Scalar filter inputs throw at
   build, like upstream (HPZ1 rate check)."
  (:require [clojure.test :refer [deftest is testing]]
            [clojure-collider.ugens.filter :as flt]
            [clojure-collider.ugens.osc :as osc]
            [clojure-collider.ugens.in-out :as io]
            [clojure-collider.synth.ugen :as ugen]
            [clojure-collider.synth.synthdef :as synthdef]))
(defn- graph-names [out-fn thunk]
  (let [sd (synthdef/make-synthdef "t")]
    (binding [ugen/*synthdef* sd]
      ;; consume the result through Out so pure units survive DCE
      (out-fn 0 (thunk))
      (synthdef/build-synthdef sd)
      (mapv :name (:children @sd)))))

(deftest var-lag-default-builds-env-graph
  ;; Filter.lisp var-lag-new: warp 5 is not :lin, so no VarLag unit appears;
  ;; the lag runs on changed (HPZ1 + abs + >) + Impulse + EnvGen.
  (let [names (graph-names io/out:ar
                           #(flt/var-lag:ar (osc/sin-osc:ar 440 0 1 0) 0.1))]
    (is (some #{"EnvGen"} names))
    (is (some #{"HPZ1"} names))
    (is (some #{"Impulse"} names))
    (is (not (some #{"VarLag"} names)))))

(deftest var-lag-lin-emits-unit
  (let [names (graph-names io/out:ar
                           #(flt/var-lag:ar (osc/sin-osc:ar 440 0 1 0)
                                            0.1 0 :lin nil 1.0 0.0))]
    (is (some #{"VarLag"} names))
    (is (not (some #{"EnvGen"} names)))))

(deftest scalar-filter-input-throws
  ;; upstream check-same-rate-as-first-input runs at build; scalar `in`
  ;; to a filter is illegal in both.
  (is (thrown? clojure.lang.ExceptionInfo
               (graph-names io/out:ar #(flt/var-lag:kr 1.0 0.1))))
  (is (thrown? clojure.lang.ExceptionInfo
               (graph-names io/out:kr #(flt/changed:kr 1.0)))))

(deftest changed-shape
  (testing "changed is a composite (> (abs (hpz-1 in)) threshold)"
    (let [names (graph-names io/out:kr #(flt/changed:kr (osc/sin-osc:kr 440)))]
      (is (some #{"HPZ1"} names))
      (is (some #{"BinaryOpUGen"} names)))))

(deftest svf-shape
  (testing "SVF.ar(in, cutoff, res, lp, bp, hp) emits one SVF unit"
    (let [names (graph-names io/out:ar
                             #(flt/svf:ar (osc/saw:ar 110) 800 0.9 0 1 0))]
      (is (some #{"SVF"} names))
      (is (some #{"Saw"} names)))))
