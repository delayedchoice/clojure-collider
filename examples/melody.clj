;; Scheduled melody, mirroring the cl-collider README saw-synth example.
;; Run: clojure -M examples/melody.clj   (boots its own scsynth)
(require '[clojure-collider.server.server :as server]
         '[clojure-collider.synth.def :as def]
         '[clojure-collider.synth.operators :as ops]
         '[clojure-collider.ugens.osc :as osc]
         '[clojure-collider.ugens.filter :as flt]
         '[clojure-collider.ugens.env-gen :as e]
         '[clojure-collider.ugens.in-out :as io])

(def notes [62 65 69 72])
(def durs [0 1 2 1.5])

(let [s (server/make-external-server "example" :port 57132)]
  (server/server-boot s)
  (binding [server/*s* s]
    (def/defsynth :saw-synth
      [note 60 dur 4.0]
      (let [env (e/env-gen:kr (e/env [0 0.2 0] [(ops/* dur 0.2) (ops/* dur 0.8)]) :act :free)
            freq (ops/midicps note)
            sig (flt/lpf:ar (osc/saw:ar freq env) (ops/* freq 2))]
        (io/out:ar 0 [sig sig])))
    (let [t0 (server/quant 4)]
      (loop [time t0 n 16]
        (when (pos? n)
          (let [note (+ 0 (rand-nth notes))]
            (server/callback time #(def/synth :saw-synth :note note))
            (recur (+ time (rand-nth durs)) (dec n)))))
      (Thread/sleep 9000)))
  (server/server-quit s)
  (println :done))
