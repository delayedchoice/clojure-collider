(ns clojure-collider.volume-test
  "Volume graph parity + live control."
  (:require [clojure.test :refer [deftest is testing]]
            [clojure-collider.volume :as vol]
            [clojure-collider.synth.ugen :as ugen]
            [clojure-collider.synth.control :refer [with-controls]]
            [clojure-collider.synth.synthdef :as synthdef]
            [clojure-collider.synth.operators :as ops]
            [clojure-collider.ugens.env-gen :as e]
            [clojure-collider.ugens.filter :as flt]
            [clojure-collider.ugens.in-out :as io]
            [clojure-collider.server.server :as server])
  (:import [java.util Arrays]
           [java.nio.file Files Paths]))

(defn- want [f]
  (Files/readAllBytes (Paths/get (str "test/resources/golden/" f)
                                 (into-array String []))))

(deftest golden-volume-bytes
  (testing "cl-volume.scsyndef: XOut/In/Lag/Linen graph matches CL"
    (let [sd (synthdef/make-synthdef "volume")]
      (binding [ugen/*synthdef* sd]
        (with-controls ((amp 0.5) (gate 1))
          (io/x-out:ar 0 (e/linen:kr gate 0.01 1.0 0.05 :act :free)
                       (ops/* (io/in:ar 0 2) (flt/lag:kr amp 0.1))))
        (synthdef/build-synthdef sd)
        (is (= ["Control" "Linen" "Lag" "In" "BinaryOpUGen" "BinaryOpUGen" "XOut"]
               (mapv :name (:children @sd))))
        (is (Arrays/equals ^bytes (want "cl-volume.scsyndef")
                           ^bytes (synthdef/encode-synthdef sd))
            "cl-volume.scsyndef differs from CL output")))))

(def live-port 57120)

(deftest live-volume-cycle
  (let [probe (try
                (let [s (server/make-external-server "test" :port live-port
                                                     :just-connect? true)]
                  (server/server-boot s)
                  s)
                (catch Exception e
                  (println "SKIP live volume tests:" (.getMessage e)) nil))]
    (when probe
      (try
        (binding [server/*s* probe]
          (testing "set starts the control synth"
            (vol/set-volume! probe -6.0)
            (is (= -6.0 (vol/volume probe)))
            (let [n @(:volume-control-synth probe)]
              (is (some? n))
              (is (loop [i 0]
                    (cond (server/is-playing? n) true
                          (> i 100) false
                          :else (do (Thread/sleep 20)
                                    (recur (inc i))))))))
          (testing "mute remembers, unmute restores"
            (vol/mute probe)
            (is (= -6.0 (vol/volume probe)))
            (vol/unmute probe)
            (is (= -6.0 (vol/volume probe))))
          (testing "unity releases the control synth"
            (vol/set-volume! probe 0.0)
            (is (= 0.0 (vol/volume probe)))))
        (finally
          (when (server/boot? probe)
            (server/server-quit probe)))))))
