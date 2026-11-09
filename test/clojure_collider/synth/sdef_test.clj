(ns clojure-collider.synth.sdef-test
  "defsynth/synth/play: metadata, macro parity, live fire."
  (:require [clojure.test :refer [deftest is testing]]
            [clojure-collider.synth.sdef :as sdef]
            [clojure-collider.synth.control :as ctl]
            [clojure-collider.synth.synthdef :as synthdef]
            [clojure-collider.ugens.osc :as osc]
            [clojure-collider.ugens.in-out :as io]
            [clojure-collider.server.server :as server]))

(deftest defsynth-metadata
  (sdef/defsynth nil md-probe [freq 440 amp 0.5] (io/out:ar 0 (osc/sin-osc:ar freq 0 1 amp)))
  (is (= 'md-probe (sdef/synthdef-metadata 'md-probe :name)))
  (is (= [['freq 440] ['amp 0.5]] (sdef/synthdef-metadata 'md-probe :controls)))
  (is (= 1 (count (sdef/synthdef-metadata 'md-probe :body)))))

(deftest defsynth-keyword-name
  (testing "keyword names normalize (no colon) so synth finds the def"
    (let [sd (sdef/defsynth nil :kw-probe [freq 440]
               (io/out:ar 0 (osc/sin-osc:ar freq)))]
      (is (= "kw-probe" (:name @sd))))))

(deftest defsynth-macro-parity
  ;; the macro must add no junk vs the manual graph
  (testing "vector params"
    (let [via-macro (sdef/defsynth nil parity-v [freq 440]
                      (io/out:ar 0 (osc/sin-osc:ar freq)))
          manual (let [m (synthdef/make-synthdef "parity-v")
                       root (ctl/with-controls m [freq 440]
                              (io/out:ar 0 (osc/sin-osc:ar freq)))]
                   (synthdef/build-synthdef m root)
                   m)]
      (is (java.util.Arrays/equals (synthdef/encode-synthdef via-macro)
                                   (synthdef/encode-synthdef manual)))))
  (testing "CL params"
    (let [via-macro (sdef/defsynth nil parity-c ((freq 440) (amp 0.5 :ar))
                      (io/out:ar 0 (osc/sin-osc:ar freq 0 1 amp)))
          manual (let [m (synthdef/make-synthdef "parity-c")
                       root (ctl/with-controls m ((freq 440) (amp 0.5 :ar))
                              (io/out:ar 0 (osc/sin-osc:ar freq 0 1 amp)))]
                   (synthdef/build-synthdef m root)
                   m)]
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
        (testing "defsynth sends, synth starts"
          (sdef/defsynth probe live-tone [freq 440]
            (io/out:ar 0 (osc/sin-osc:ar freq 0 1 0.2)))
          (let [n (sdef/synth probe 'live-tone :freq 442.0)]
            (is (wait-playing n))
            (server/node-free n)))
        (testing "play fades in and starts"
          (let [p (sdef/play probe (osc/sin-osc:ar 440 0 1 0.2) :gain 0.5)]
            (is (wait-playing p))
            (is (= 0.02 (sdef/synthdef-metadata "temp-synth" :fade-time)))
            (server/node-free p)))
        (finally
          (when (server/boot? probe)
            (server/server-quit probe)))))))

(deftest live-bundle-starts-synth
  (testing "explicit bundles (incl. int-addressed /s_new) start nodes now"
    (let [probe (try
                  (let [s (server/make-external-server "test" :port live-port
                                                       :just-connect? true)]
                    (server/server-boot s)
                    s)
                  (catch Exception e
                    (println "SKIP live bundle test:" (.getMessage e)) nil))]
      (when probe
        (try
          (sdef/defsynth probe live-bundled [freq 440]
            (io/out:ar 0 (osc/sin-osc:ar freq 0 1 0.2)))
          (let [[n msg] (sdef/synth-msg probe :live-bundled)]
            (server/send-bundle probe (server/now probe) [msg])
            (is (wait-playing n))
            (server/node-free n))
          (finally
            (when (server/boot? probe)
              (server/server-quit probe))))))))
