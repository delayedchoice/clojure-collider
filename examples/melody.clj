;; Scheduled melody, mirroring the cl-collider README saw-synth example.
;; Run: clojure -M -m melody   (boots its own scsynth)
(ns melody
  (:require [clojure-collider.server.server :as server]
            [clojure-collider.synth.sdef :as sdef]
            [clojure-collider.synth.operators :as ops]
            [ugens :as ug]))

(def notes [62 65 69 72])
(def durs [0 1 2 1.5])

(defn -main [& _]
  (let [s (server/make-external-server "example" :port 57132)]
    (server/server-boot s)
    (binding [server/*s* s]
      (sdef/defsynth :saw-synth
        [note 60 dur 4.0]
        (let [env (ug/env-gen:kr (ug/env [0 0.2 0] [(ops/* dur 0.2) (ops/* dur 0.8)]) :act :free)
              freq (ops/midicps note)
              sig (ug/lpf:ar (ug/saw:ar freq env) (ops/* freq 2))]
          (ug/out:ar 0 [sig sig])))
      (let [t0 (server/quant 4)]
        (loop [time t0 n 16]
          (when (pos? n)
            (let [note (+ 0 (rand-nth notes))]
              (server/callback time #(sdef/synth :saw-synth :note note))
              (recur (+ time (rand-nth durs)) (dec n)))))
        (Thread/sleep 9000)))
    (server/server-quit s)
    (println :done)))
