(ns clojure-collider.synth.def-test
  "defsynth/synth/play: metadata, macro parity, live fire."
  (:require [clojure.test :refer [deftest is testing]]
            [clojure-collider.synth.def :as d]
            [clojure-collider.synth.ugen :as ugen]
            [clojure-collider.synth.control :as ctl]
            [clojure-collider.synth.synthdef :as synthdef]
            [clojure-collider.ugens.osc :as osc]
            [clojure-collider.ugens.in-out :as io]
            [clojure-collider.server.server :as server]))

(deftest defsynth-metadata
  (d/defsynth md-probe [freq 440 amp 0.5] (io/out:ar 0 (osc/sin-osc:ar freq 0 1 amp)))
  (is (= 'md-probe (d/synthdef-metadata 'md-probe :name)))
  (is (= [['freq 440] ['amp 0.5]] (d/synthdef-metadata 'md-probe :controls)))
  (is (= 1 (count (d/synthdef-metadata 'md-probe :body)))))

(deftest defsynth-keyword-name
  (testing "keyword names normalize (no colon) so synth finds the def"
    (let [sd (d/defsynth :kw-probe [freq 440]
               (io/out:ar 0 (osc/sin-osc:ar freq)))]
      (is (= "kw-probe" (:name @sd))))))

(deftest defsynth-macro-parity
  ;; the macro must add no junk vs the manual graph
  (testing "vector params"
    (let [via-macro (d/defsynth parity-v [freq 440]
                      (io/out:ar 0 (osc/sin-osc:ar freq)))
          manual (let [m (synthdef/make-synthdef "parity-v")]
                   (binding [ugen/*synthdef* m]
                     (ctl/with-controls [freq 440]
                       (io/out:ar 0 (osc/sin-osc:ar freq)))
                     (synthdef/build-synthdef m) m))]
      (is (java.util.Arrays/equals (synthdef/encode-synthdef via-macro)
                                   (synthdef/encode-synthdef manual)))))
  (testing "CL params"
    (let [via-macro (d/defsynth parity-c ((freq 440) (amp 0.5 :ar))
                      (io/out:ar 0 (osc/sin-osc:ar freq 0 1 amp)))
          manual (let [m (synthdef/make-synthdef "parity-c")]
                   (binding [ugen/*synthdef* m]
                     (ctl/with-controls ((freq 440) (amp 0.5 :ar))
                       (io/out:ar 0 (osc/sin-osc:ar freq 0 1 amp)))
                     (synthdef/build-synthdef m) m))]
      (is (java.util.Arrays/equals (synthdef/encode-synthdef via-macro)
                                   (synthdef/encode-synthdef manual))))))

(def live-port 57120)

(defn- wait-playing [node]
  (loop [i 0]
    (cond (server/is-playing? node) true
          (> i 100) false
          :else (do (Thread/sleep 20) (recur (inc i))))))

(deftest live-defsynth-synth-play
  (let [probe (try
                (let [s (server/make-external-server "test" :port live-port
                                                     :just-connect? true)]
                  (server/server-boot s)
                  s)
                (catch Exception e
                  (println "SKIP live defsynth tests:" (.getMessage e)) nil))]
    (when probe
      (try
        (binding [server/*s* probe]
          (testing "defsynth sends, synth starts"
            (d/defsynth live-tone [freq 440]
              (io/out:ar 0 (osc/sin-osc:ar freq 0 1 0.2)))
            (let [n (d/synth 'live-tone :freq 442.0)]
              (is (wait-playing n))
              (server/node-free n)))
          (testing "play fades in and starts"
            (let [p (d/play (osc/sin-osc:ar 440 0 1 0.2) :gain 0.5)]
              (is (wait-playing p))
              (is (= 0.02 (d/synthdef-metadata "temp-synth" :fade-time)))
              (server/node-free p))))
        (finally
          (when (server/boot? probe)
            (server/server-quit probe)))))))
