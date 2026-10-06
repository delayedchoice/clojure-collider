(ns clojure-collider.core-test
  (:require [clojure.test :refer [deftest is testing]]
            [clojure-collider.core :as core]
            [clojure-collider.ugens.osc :as uosc]
            [clojure-collider.server.server :as server]
            [clojure-collider.synth.operators :as ops]))

(deftest core-loads
  (is (= "0.1.0-SNAPSHOT" core/version)))

(deftest colon-ugen-names-work
  (testing "colon rate suffix is valid defn target per DECISIONS.md"
    (is (= 440 ((fn sin-osc:ar [freq] freq) 440)))
    (is (= 440 ((fn sin-osc:kr [freq] freq) 440)))))

(deftest overtone-vector-params-shape
  (testing "vector param shape accepted (overtone style)"
    (let [params '[freq 440 amp 0.5 gate [0 :tr]]]
      (is (vector? params))
      (is (even? (count params))))))

(deftest facade-points-at-impl
  (testing "re-exports resolve to the home vars (fns, ugens, macros)"
    ;; import-vars interns copies, so compare roots, not identities
    (is (= @#'uosc/sin-osc:ar @#'core/sin-osc:ar))
    (is (= @#'server/make-external-server @#'core/make-external-server))
    (is (= @#'ops/+ @#'core/+))
    (is (:macro (meta #'core/defsynth)))
    (is (:macro (meta #'core/play)))
    (is (seq (:arglists (meta #'core/sin-osc:ar))))))
