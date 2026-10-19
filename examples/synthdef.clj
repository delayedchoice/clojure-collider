;; SynthDef + synth + ctrl, mirroring the cl-collider README sine-wave.
;; Run: clojure -M -m synthdef   (boots its own scsynth)
(ns synthdef
  (:require [clojure-collider.server.server :as server]
            [clojure-collider.synth.sdef :as sdef]
            [clojure-collider.synth.operators :as ops]
            [clojure-collider.ugens.osc :as osc]
            [clojure-collider.ugens.in-out :as io]))

(defn -main [& _]
  (let [s (server/make-external-server "example" :port 57131)]
    (server/server-boot s)
    (binding [server/*s* s]
      (sdef/defsynth :sine-wave
        [note 60]
        (let [freq (ops/midicps note)
              sig (osc/sin-osc:ar [freq (ops/+ freq 2)] 0 0.2)]
          (io/out:ar 0 sig)))
      (let [n (sdef/synth :sine-wave)]
        (Thread/sleep 800)
        (server/ctrl n :note 72)
        (Thread/sleep 800)
        (server/node-free s (:id n))))
    (server/server-quit s)
    (println :done)))
