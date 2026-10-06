;; Hack music: two detuned sines, then stop.
;; Run: clojure -M examples/sine.clj   (boots its own scsynth)
(require '[clojure-collider.server.server :as server]
         '[clojure-collider.synth.def :as def]
         '[clojure-collider.ugens.osc :as osc])

(let [s (server/make-external-server "example" :port 57130)]
  (server/server-boot s)
  (binding [server/*s* s]
    (let [n (def/play (osc/sin-osc:ar [320 321] 0 0.2 0))]
      (Thread/sleep 1500)
      (server/node-free s (:id n))))
  (server/server-quit s)
  (println :done))
