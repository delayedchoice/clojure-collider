(ns clojure-collider.synth.synthdef-test
  "Topo sort + v2 encoding parity (synthdef.lisp topo-logical-sort,
   to-byte-array-synthdef-2, ugen.lisp write-def-ugen-version2)."
  (:require [clojure.test :refer [deftest is testing]]
            [clojure-collider.synth.ugen :as ugen]
            [clojure-collider.synth.synthdef :as synthdef]
            [clojure-collider.synth.control :as ctl :refer [with-controls]]
            [clojure-collider.synth.operators :as ops]
            [clojure-collider.osc.encode :as enc]
            [clojure-collider.ugens.env-gen :as e]
            [clojure-collider.ugens.filter :as flt]
            [clojure-collider.ugens.osc :as osc]
            [clojure-collider.ugens.in-out :as io])
  (:import [java.util Arrays]
           [java.nio.file Files Paths]))

(defn- build [name f]
  ;; *synthdef* evaluates to the atom; @ derefs it to the map.
  (binding [ugen/*synthdef* (synthdef/make-synthdef name)]
    (f)
    (let [a ugen/*synthdef*]
      (synthdef/build-synthdef a)
      @a)))
(deftest sum-fusion-keeps-consumers
  (testing "4-voice sum fuses twice (Sum3 then Sum4); the outer consumer
   must follow to the replacement. Identity-based rewrite missed the
   second hop (mapv copies invalidate identities) and scsynth rejected
   the def ('buffer coloring error'). Refs now match by :synth-index."
    (let [s (build "fuse4"
                   (fn []
                     (let [sig (apply ops/+
                                      (map (fn [m] (flt/lpf:ar (osc/saw:ar (ops/midicps m)) 800))
                                           [39 37 44 51]))]
                       (io/out:ar 0 (ops/* 0.1 sig)))))
          kids (:children s)
          idxs (set (map :synth-index kids))
          sum (first (filter #(= "Sum4" (:name %)) kids))
          mults (filter #(and (= "BinaryOpUGen" (:name %))
                              (= 2 (:special-index %))) kids)]
        (is (some? sum) "Sum4 formed")
        (is (= 1 (count mults)))
        ;; post-build records are copies; compare by stable :synth-index
        (is (some #(= (:synth-index sum) (:synth-index %))
                  (:inputs (first mults)))
            "consumer follows fusion to Sum4")
        (is (every? #(contains? idxs (:synth-index %))
                    (filter ugen/ugen-like? (mapcat :inputs kids)))
            "no dangling inputs"))))

(deftest topo-schedule-order
  (testing "CL stack discipline jumps newly-ready ahead: [A B C(dep A) D(dep B)]
   schedules as [A C B D]. Plain-ugen tails (C D) survive DCE, so the demo
   holds post-optimize, like upstream."
    (let [s (build "reorder"
                   (fn []
                     (let [a (osc/sin-osc:ar 440 0 1 0)
                           b (osc/sin-osc:ar 441 0 1 0)
                           _c (osc/saw:ar a 1 0)]
                       (osc/saw:ar b 1 0))))]
      (is (= ["SinOsc" "Saw" "SinOsc" "Saw"] (mapv :name (:children s))))
      (is (= [[0 0]] (ugen/input-spec (second (:children s)) s)))
      (is (= [[2 0]] (ugen/input-spec (nth (:children s) 3) s))))))

(deftest topo-producers-first
  (testing "diamond: every ugen input resolves to an earlier ugen.
   saw:ar folds mul/add through madd, so each saw site is Saw+BinaryOp/MulAdd."
    (let [s (build "diamond"
                   (fn []
                     (let [a (osc/sin-osc:ar 440 0 1 0)
                           b (osc/saw:ar 100 a 0)
                           c (osc/saw:ar 200 a 0)]
                       (osc/saw:ar 300 b c))))]
      (is (= ["SinOsc" "Saw" "BinaryOpUGen" "Saw" "BinaryOpUGen" "Saw" "MulAdd"]
             (mapv :name (:children s))))
      (doseq [[i u] (map-indexed vector (:children s))]
        (doseq [[ref-idx _] (filter (fn [[r _]] (>= r 0)) (ugen/input-spec u s))]
          (is (< ref-idx i) (str "ugen " i " references later ugen " ref-idx)))))))

(deftest out-encodes-zero-outputs
  (testing "ugen-new honors NoOutputUGen class (ugen.lisp nooutput-ugen)"
    (let [s (build "sine"
                   (fn []
                     (let [car (osc/sin-osc:ar 440 0 0.2 0)]
                       (io/out:ar 0 car))))]
      ;; record type by simple name: the FQ JVM name has underscores
      ;; (clojure_collider...), so don't spell it as a class literal.
      ;; Children are [SinOsc BinaryOpUGen Out]: Out is third now.
      (is (= "NoOutputUGen" (.getSimpleName (type (nth (:children s) 2)))))
      (is (= 0 (ugen/num-outputs (nth (:children s) 2))))
      ;; Out inputs: const bus 0.0 (idx 1), carrier BinaryOp (idx 1)
      (is (= [[-1 1] [1 0]] (ugen/input-spec (nth (:children s) 2) s))))))

(defn- cl-bytes [fixture-name built]
  (let [got (synthdef/encode-synthdef (atom built))
        want (Files/readAllBytes (Paths/get (str "test/resources/golden/" fixture-name)
                                            (into-array String [])))]
    (is (Arrays/equals ^bytes want ^bytes got)
        (str fixture-name " differs from CL output"))
    got))

(deftest golden-sine-bytes
  (testing "cl-sine.scsyndef: byte-identical to SBCL cl-collider output"
    (let [s (build "sine"
                   (fn []
                     (let [car (osc/sin-osc:ar 440 0 0.2 0)]
                       (io/out:ar 0 car))))
          got (cl-bytes "cl-sine.scsyndef" s)]
      ;; structural header audit (to-byte-array-synthdef-2 field order)
      (is (= [0x53 0x43 0x67 0x66] (mapv #(bit-and (aget got %) 255) (range 4))))
      (is (= 2 (enc/decode-int32 got 4)))
      (is (= "sine" (String. got 11 4 "UTF-8"))))))

(deftest golden-reorder-bytes
  (testing "cl-reorder.scsyndef: schedule order + DCE match CL"
    (let [s (build "reorder"
                   (fn []
                     (let [a (osc/sin-osc:ar 440 0 1 0)
                           b (osc/sin-osc:ar 441 0 1 0)]
                       (osc/saw:ar a 1 0)
                       (osc/saw:ar b 1 0))))]
      (cl-bytes "cl-reorder.scsyndef" s))))

(deftest golden-diamond-bytes
  (testing "cl-diamond.scsyndef: MulAdd tails + nesting match CL"
    (let [s (build "diamond"
                   (fn []
                     (let [a (osc/sin-osc:ar 440 0 1 0)]
                       (osc/saw:ar 300 (osc/saw:ar 100 a 0) (osc/saw:ar 200 a 0)))))]
      (cl-bytes "cl-diamond.scsyndef" s))))

(deftest golden-dsine-bytes
  (testing "cl-dsine.scsyndef: defsynth control + Out path match CL"
    (let [s (build "dsine"
                   (fn []
                     (with-controls [freq 440]
                       (io/out:ar 0 (osc/sin-osc:ar freq 0 1 0.2)))))]
      (cl-bytes "cl-dsine.scsyndef" s))))

(deftest golden-varlag-bytes
  (testing "cl-varlag.scsyndef: VarLag EnvGen-graph + rate checks match CL"
    (let [s (build "varlag"
                   (fn []
                     (io/out:ar
                      0 (flt/var-lag:ar (osc/sin-osc:ar 440 0 1 0) 0.1))))]
      (cl-bytes "cl-varlag.scsyndef" s))))

(deftest golden-playtail-bytes
  (testing "cl-playtail.scsyndef: play fade tail (EnvGen gate, Sum3 trig,
   lagged gain outlets) matches CL"
    (let [s (build "playtail"
                   (fn []
                     (let [result (osc/sin-osc:ar 440 0 1 0.2)
                           [dt buses gate gain lag]
                           (ctl/make-control
                            ugen/*synthdef*
                            [{:sym 'fade :name "fade" :value (float 0.02)
                              :rate nil :lag nil}
                             {:sym 'out-bus :name "out-bus" :value (float 0)
                              :rate nil :lag nil}
                             {:sym 'gate :name "gate" :value 1.0
                              :rate nil :lag nil}
                             {:sym 'gain :name "gain" :value (float 0.5)
                              :rate nil :lag nil}
                             {:sym 'lag :name "lag" :value (float 1.0)
                              :rate nil :lag nil}])
                           start-val (ops/<= dt 0)
                           env (e/env-gen:kr
                                (e/env [start-val 1 0] [1 1] :lin 1)
                                :gate gate :time-scale dt :act :free)
                           result2 (ops/* env result)]
                       (io/out:ar buses
                                  (ops/* result2 (flt/var-lag:kr gain lag))))))]
      (cl-bytes "cl-playtail.scsyndef" s))))
