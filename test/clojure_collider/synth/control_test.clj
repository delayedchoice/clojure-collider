(ns clojure-collider.synth.control-test
  "synthdef.lisp control parity: with-controls shapes, groups,
   named-control registry, graph wiring."
  (:require [clojure.test :refer [deftest is testing]]
            [clojure-collider.synth.ugen :as ugen]
            [clojure-collider.synth.synthdef :as synthdef]
            [clojure-collider.synth.control :as ctl]
            [clojure-collider.ugens.osc :as osc]
            [clojure-collider.ugens.in-out :as io]))

(defn- build [name f]
  (binding [ugen/*synthdef* (synthdef/make-synthdef name)]
    (let [a ugen/*synthdef*]
      (f)
      (synthdef/build-synthdef a)
      @a)))

(deftest with-controls-vector
  (let [s (build "ctl"
                 #(ctl/with-controls [freq 440 amp 0.5]
                    (osc/saw:ar freq amp 0)))]
    ;; the (mul Saw amp) tail is unconsumed -> DCE'd, like upstream
    (is (= ["Control" "Saw"] (mapv :name (:children s))))
    (is (= 2 (:channels (first (:children s)))))
    (is (= [440.0 0.5] (:controls s)))
    (is (= [["freq" 0] ["amp" 1]] (:control-names s)))
    (is (= [] (:constants s)))
    (is (= [[0 0]] (ugen/input-spec (second (:children s)) s)))))

(deftest with-controls-cl-groups
  (let [s (build "groups"
                 #(ctl/with-controls ((gate 0 :tr) (freq 440 :ar) (amp 0.5))
                    (osc/saw:ar freq amp gate)))]
    ;; Saw depends only on AudioControl, so it schedules ahead of Control
    (is (= ["TrigControl" "AudioControl" "Saw" "Control" "MulAdd"] (mapv :name (:children s))))
    (is (= [["gate" 0] ["freq" 1] ["amp" 2]] (:control-names s)))))

(deftest with-controls-multi
  (let [s (build "multictl"
                 #(ctl/with-controls [freqs [440 441]]
                    (io/out:ar 0 (osc/sin-osc:ar freqs 0 1 0))))]
    (is (= 2 (:channels (first (:children s)))))
    ;; expansion over 2 freq proxies, sunk into ONE spliced Out (upstream
    ;; apply-multinew: (out.ar 0 (channels...)) is a single unit)
    (is (= ["Control" "SinOsc" "SinOsc" "Out"] (mapv :name (:children s))))))

(deftest duplicate-control-name
  ;; intentional duplicate binding: the test IS the collision
  #_:clj-kondo/ignore
  (is (thrown? clojure.lang.ExceptionInfo
               (build "dup" #(ctl/with-controls [freq 440 freq 441] freq)))))
(deftest named-control-registry
  (binding [ugen/*synthdef* (synthdef/make-synthdef "named")]
    (let [a ugen/*synthdef*
          u1 (ctl/named-control "freq" :kr 440)
          u2 (ctl/named-control "freq" :kr 440)]
      (is (= u1 u2))
      (is (thrown? clojure.lang.ExceptionInfo
                   (ctl/named-control "freq" :kr 441)))
      (is (thrown? clojure.lang.ExceptionInfo
                   (ctl/named-control "freq" :ar 440)))
      (is (thrown? clojure.lang.ExceptionInfo
                   (ctl/named-control "nope" :xx 1)))
      ;; fixed-lag control is a LagControl (lags as inputs, no wrapping).
      (is (= "LagControl"
             (:name (last (:children
                           (do (ctl/named-control "laggy" :kr 440 0.1)
                               @a))))))
      ;; audio+lag always wraps in lag.ar -> needs Phase-6 Lag.
      (is (thrown? clojure.lang.ExceptionInfo
                   (ctl/named-control "awrap" :ar 440 0.1)))
      (synthdef/build-synthdef a)
      (is (= ["Control" "LagControl"] (mapv :name (:children @a)))))))

(deftest kr-shortcut
  (binding [ugen/*synthdef* (synthdef/make-synthdef "kr")]
    (let [a ugen/*synthdef*
          k (ctl/kr "amp" 0.5)]
      (is (ugen/proxy-output? k))
      (is (= 0 (:output-index k)))
      (synthdef/build-synthdef a)
      (is (= [["amp" 0]] (:control-names @a))))))

(deftest control-graph-encodes
  (let [s (build "enc"
                 #(ctl/with-controls [freq 440]
                    (let [car (osc/sin-osc:ar freq 0 0.2 0)]
                      (io/out:ar 0 car))))]
    (is (= ["Control" "SinOsc" "BinaryOpUGen" "Out"] (mapv :name (:children s))))
    (let [bytes (synthdef/encode-synthdef (atom s))]
      (is (pos? (alength bytes)))
      (is (= 1 (count (:control-names s)))))))

(deftest golden-control-bytes
  (testing "cl-ctl/groups.scsyndef: controls + DCE match CL"
    (let [s (build "ctl"
                   #(ctl/with-controls [freq 440 amp 0.5]
                      (osc/saw:ar freq amp 0)))
          got (synthdef/encode-synthdef (atom s))
          want (java.nio.file.Files/readAllBytes
                (java.nio.file.Paths/get "test/resources/golden/cl-ctl.scsyndef"
                                         (into-array String [])))]
      (is (java.util.Arrays/equals ^bytes want ^bytes got)))
    (let [s (build "groups"
                   #(ctl/with-controls ((gate 0 :tr) (freq 440 :ar) (amp 0.5))
                      (osc/saw:ar freq amp gate)))
          got (synthdef/encode-synthdef (atom s))
          want (java.nio.file.Files/readAllBytes
                (java.nio.file.Paths/get "test/resources/golden/cl-groups.scsyndef"
                                         (into-array String [])))]
      (is (java.util.Arrays/equals ^bytes want ^bytes got)))))
