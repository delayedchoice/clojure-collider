(ns clojure-collider.ugens.filter-test
  "VarLag fidelity: non-:lin warp builds the upstream EnvGen graph;
   :lin warp emits the bare VarLag unit. Scalar filter inputs throw at
   build, like upstream (HPZ1 rate check)."
  (:require [clojure.test :refer [deftest is testing]]
            [clojure-collider.synth.sdef :as sdef]
            [clojure-collider.ugens.filter :as flt]
            [clojure-collider.ugens.osc :as osc]
            [clojure-collider.ugens.in-out :as io]))

(defn- graph-names
  "Child UGen names of a built synthdef atom. Graphs are built with the
   explicit defsynth form (nil server: build only, nothing sent)."
  [sd]
  (mapv :name (:children @sd)))

(deftest var-lag-default-builds-env-graph
  ;; Filter.lisp var-lag-new: warp 5 is not :lin, so no VarLag unit appears;
  ;; the lag runs on changed (HPZ1 + abs + >) + Impulse + EnvGen.
  (let [sd (sdef/defsynth nil var-lag-env []
             ;; consume the result through Out so pure units survive DCE
             (io/out:ar 0 (flt/var-lag:ar (osc/sin-osc:ar 440 0 1 0) 0.1)))
        names (graph-names sd)]
    (is (some #{"EnvGen"} names))
    (is (some #{"HPZ1"} names))
    (is (some #{"Impulse"} names))
    (is (not (some #{"VarLag"} names)))))

(deftest var-lag-lin-emits-unit
  (let [sd (sdef/defsynth nil var-lag-lin []
             (io/out:ar 0 (flt/var-lag:ar (osc/sin-osc:ar 440 0 1 0)
                                          0.1 0 :lin nil 1.0 0.0)))
        names (graph-names sd)]
    (is (some #{"VarLag"} names))
    (is (not (some #{"EnvGen"} names)))))

(deftest scalar-filter-input-throws
  ;; upstream check-same-rate-as-first-input runs at build; scalar `in`
  ;; to a filter is illegal in both.
  (is (thrown? clojure.lang.ExceptionInfo
               (sdef/defsynth nil var-lag-scalar []
                 (io/out:ar 0 (flt/var-lag:kr 1.0 0.1)))))
  (is (thrown? clojure.lang.ExceptionInfo
               (sdef/defsynth nil changed-scalar []
                 (io/out:kr 0 (flt/changed:kr 1.0))))))

(deftest changed-shape
  (testing "changed is a composite (> (abs (hpz-1 in)) threshold)"
    (let [sd (sdef/defsynth nil changed-graph []
               (io/out:kr 0 (flt/changed:kr (osc/sin-osc:kr 440))))
          names (graph-names sd)]
      (is (some #{"HPZ1"} names))
      (is (some #{"BinaryOpUGen"} names)))))

(deftest svf-shape
  (testing "SVF.ar(in, cutoff, res, lp, bp, hp) emits one SVF unit"
    (let [sd (sdef/defsynth nil svf-graph []
               (io/out:ar 0 (flt/svf:ar (osc/saw:ar 110) 800 0.9 0 1 0)))
          names (graph-names sd)]
      (is (some #{"SVF"} names))
      (is (some #{"Saw"} names)))))
