(ns clojure-collider.ugens-test
  "Phase 6: one build-smoke per ugen file (guards wiring/check-fn/arity
   breaks), CL-parity goldens for select/demand customs, and the klang
   structural pin (our Klang is multi-out; upstream emits 1 — DECISIONS)."
  (:require [clojure.test :refer [deftest is testing]]
            [clojure-collider.synth.ugen :as ugen]
            [clojure-collider.synth.synthdef :as synthdef]
            [clojure-collider.ugens :as u])
  (:import [java.util Arrays]
           [java.nio.file Files Paths]))

(defn- build-names [thunk]
  (let [sd (synthdef/make-synthdef "t")]
    (binding [ugen/*synthdef* sd]
      (thunk)
      (synthdef/build-synthdef sd)
      (mapv :name (:children @sd)))))

(defn- want [n]
  (Files/readAllBytes (Paths/get (str "test/resources/golden/cl-" n ".scsyndef")
                                 (into-array String []))))

(defn- golden [n thunk]
  (let [sd (synthdef/make-synthdef n)]
    (binding [ugen/*synthdef* sd]
      (thunk)
      (synthdef/build-synthdef sd)
      (is (Arrays/equals ^bytes (want n) ^bytes (synthdef/encode-synthdef sd))
          (str "cl-" n ".scsyndef differs from CL output")))))

(deftest select-golden
  (golden "select"
          #(u/out:ar 0 (u/select:ar (u/sin-osc:kr 1)
                                    [(u/sin-osc:ar 440 0 1 0)
                                     (u/sin-osc:ar 550 0 1 0)]))))

(deftest demand-golden
  (golden "demand" #(u/out:ar 0 (u/duty:ar 1.0 :reset 0.0 :level 1.0))))

(deftest klang-shape
  (testing "Klang voices one channel per partial (SC semantics); upstream
            CL inherits 1 output (see DECISIONS 2026-10-06)"
    (let [sd (synthdef/make-synthdef "klang")]
      (binding [ugen/*synthdef* sd]
        (u/out:ar 0 (u/klang:ar [[800 1071] [1.0 1.0] [0.0 0.0]]))
        (synthdef/build-synthdef sd)
        (is (= ["Klang" "Out"] (mapv :name (:children @sd))))
        (let [k (first (:children @sd))]
          (is (= 8 (count (:inputs k))))
          (is (= 2 (:channels k))))))))

(defn- car [] (u/sin-osc:ar 440 0 1 0))

(deftest file-smokes
  (testing "one representative graph per ugen file builds its unit"
    (doseq [[label thunk unit]
            [[:beq #(u/out:ar 0 (u/b-lowpass:ar (car))) "BLowPass"]
             [:buf-io #(u/out:ar 0 (u/buf-rd:ar 1)) "BufRd"]
             [:chaos #(u/out:ar 0 (u/quad-n:ar 440)) "QuadN"]
             [:compander #(u/out:ar 0 (u/amplitude:ar (car))) "Amplitude"]
             [:delays #(u/out:ar 0 (u/delay-n:ar (car) 0.2 0.2)) "DelayN"]
             [:demand #(u/out:ar 0 (u/duty:ar 1.0)) "Duty"]
             [:disk-io #(u/out:ar 0 (u/disk-in:ar 1 0)) "DiskIn"]
             [:env-gen #(u/out:kr 0 (u/linen:kr 1 0.01 1.0 1.0)) "Linen"]
             [:fft #(u/out:kr 0 (u/fft:kr 0 (car))) "FFT"]
             [:filter #(u/out:ar 0 (u/resonz:ar (car))) "Resonz"]
             [:free-verb #(u/out:ar 0 (u/freeverb:ar (car))) "FreeVerb"]
             [:fsin-osc #(u/out:ar 0 (u/blip:ar 440)) "Blip"]
             [:g-verb #(u/out:ar 0 (u/g-verb:ar (car))) "GVerb"]
             [:gendyn #(u/out:ar 0 (u/gendy1:ar)) "Gendy1"]
             [:grain #(u/out:ar 0 (u/grain-sin:ar 2 (car) 0.1 0 440)) "GrainSin"]
             [:hilbert #(u/out:ar 0 (u/hilbert:ar 0 (car))) "Hilbert"]
             [:ienv-gen #(u/out:ar 0 (u/i-env-gen:ar (u/env [0 1 0] [1 1]) 0)) "IEnvGen"]
             [:info #(u/out:ar 0 (u/sin-osc:ar (u/sample-rate:ir) 0 1 0)) "SampleRate"]
             [:mac #(u/out:kr 0 (u/mouse-x:kr)) "MouseX"]
             [:machine #(u/out:kr 0 (u/loudness:kr 0)) "Loudness"]
             [:moog #(u/out:ar 0 (u/moog-ff:ar (car))) "MoogFF"]
             [:noise #(u/out:ar 0 (u/white-noise:ar)) "WhiteNoise"]
             [:osc #(u/out:ar 0 (u/vibrato:ar)) "Vibrato"]
             [:pan #(u/out:ar 0 (u/pan2:ar (car) 0 1.0)) "Pan2"]
             [:part-conv #(u/out:ar 0 (u/part-conv:ar (car) 1024 0)) "PartConv"]
             [:physical #(u/out:ar 0 (u/spring:ar (car))) "Spring"]
             [:pitch-shift #(u/out:ar 0 (u/pitch-shift:ar (car))) "PitchShift"]
             [:pluck #(u/out:ar 0 (u/pluck:ar (car))) "Pluck"]
             [:poll #(u/out:ar 0 (u/poll:ar (u/impulse:ar 1) (car) "reply" 0)) "Poll"]
             [:psin-grain #(u/out:ar 0 (u/p-sin-grain:ar 440)) "PSinGrain"]
             [:sound-in #(u/out:ar 0 (u/sound-in:ar 0)) "In"]
             [:splay #(u/out:ar 0 (u/splay:ar [(car)])) "Pan2"]
             [:test #(u/out:ar 0 (u/sanitize:ar (car))) "Sanitize"]
             [:trig #(u/out:ar 0 (u/stepper:ar (u/impulse:ar 1) 0 0 7 1)) "Stepper"]
             [:line #(u/out:ar 0 (u/dc:ar 0.5)) "DC"]
             [:in-out #(u/out:ar 0 (car)) "Out"]]]
      (testing (str label)
        (is (some #{unit} (build-names thunk)) (str label " missing " unit))))))

(deftest extras-smokes
  (testing "one representative graph per SC3plugins/Extensions/quarks file"
    (doseq [[label thunk unit]
            [[:bhob #(u/out:ar 0 (u/double-nested-allpass-c:ar (car))) "DoubleNestedAllpassC"]
             [:deind #(u/out:ar 0 (u/greyhole:ar (car))) "GreyholeRaw"]
             [:distortion #(u/out:ar 0 (u/decimator:ar (car))) "Decimator"]
             [:josh #(u/out:ar 0 (u/bin-data:ar 0 0)) "BinData"]
             [:ladspa #(u/out:ar 0 (u/ladspa:ar 1 0)) "LADSPA"]
             [:mcld #(u/out:ar 0 (u/array-max:ar [(car)])) "ArrayMax"]
             [:mda #(u/out:ar 0 (u/mda-piano:ar)) "MdaPiano"]
             [:nh #(u/out:ar 0 (u/nhhall:ar [(car) (car)])) "NHHall"]
             [:pitch #(u/out:kr 0 (u/tartini:kr (car))) "Tartini"]
             [:sl #(u/out:ar 0 (u/brusselator:ar)) "Brusselator"]
             [:tj #(u/out:ar 0 (u/dfm-1:ar (car))) "DFM1"]
             [:deind #(u/out:ar 0 (u/greyhole:ar (car))) "GreyholeRaw"]
             [:f0 #(u/out:ar 0 (u/atari2600:ar)) "Atari2600"]
             [:i-buf-wr #(u/out:ar 0 (u/ibuf-wr:ar [(car)] 0 (car))) "IBufWr"]
             [:mi #(u/out:ar 0 (u/mi-braids:ar)) "MiBraids"]
             [:ported #(u/out:ar 0 (u/analog-degrade:ar (car))) "AnalogDegrade"]
             [:safety #(u/out:ar 0 (u/safety-limiter:ar (car))) "SafetyLimiter"]
             [:red-impulse #(u/out:ar 0 (u/red-impulse:ar (u/impulse:ar 1))) "Phasor"]
             [:wave-folding #(u/out:ar 0 (u/smooth-clip-s:ar (car))) "Select"]]]
      (testing (str label)
        (is (some #{unit} (build-names thunk)) (str label " missing " unit))))))

(deftest dyn-registries-empty
  (testing "dyn-gen/faust module registries start empty (server-delivery
            helpers deferred; building an unregistered module asserts)"
    (is (= [] (u/dyn-gen-all)))
    (is (= [] (u/faust-all)))
    (is (thrown? AssertionError (u/dyn-gen:ar 1 "nope")))
    (is (thrown? AssertionError (u/faust:ar 1 "nope")))))

(deftest pokey-square-arities
  (testing "all 14 cascade arities route to the 13-param full arity (kondo
            caught 9/10/11-arg calls passing 14 fillers)"
    (doseq [n (range 14)]
      (is (some? (apply u/pokey-square:ar (repeat n 0))) (str "arity " n)))))

(deftest clip-fold-wrap-dispatch
  (testing "Trig.lisp defmethods: scalar folds to numbers, else unit at combined rate"
    (is (== 440 (u/clip 440 20 20000)))
    (is (== 20 (u/clip 5 20 20000)))
    (is (== 20000 (u/clip 30000 20 20000)))
    (is (== 0.5 (u/fold 1.5 0 1)))
    (is (== 0.5 (u/wrap 1.5 0 1)))
    (let [units (fn [thunk]
                  (let [sd (synthdef/make-synthdef "t")]
                    (binding [ugen/*synthdef* sd]
                      (thunk)
                      (synthdef/build-synthdef sd)
                      (:children @sd))))
          rate-of (fn [us nm]
                    (:rate (some #(when (= nm (:name %)) %) us)))]
      (is (= :audio (rate-of (units #(u/clip (car) 0 1)) "Clip")))
      (is (= :control (rate-of (units #(u/clip (u/sin-osc:kr 440 0 1 0) 0 1)) "Clip")))
      (is (= :audio (rate-of (units #(u/fold (car) 0 1)) "Fold")))
      (is (= :control (rate-of (units #(u/wrap (u/sin-osc:kr 440 0 1 0) 0 1)) "Wrap"))))))
