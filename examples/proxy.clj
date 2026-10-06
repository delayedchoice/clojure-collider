;; Node proxy swap, mirroring the cl-collider README proxy example.
;; Run: clojure -M examples/proxy.clj   (boots its own scsynth)
(require '[clojure-collider.server.server :as server]
         '[clojure-collider.synth.def :as def]
         '[clojure-collider.ugens.osc :as osc])

(let [s (server/make-external-server "example" :port 57133)]
  (server/server-boot s)
  (binding [server/*s* s]
    (def/proxy :pad (osc/sin-osc:ar 220 0 0.1))
    (Thread/sleep 1000)
    (def/proxy :pad (osc/sin-osc:ar 330 0 0.1))
    (Thread/sleep 1000)
    (def/proxy-ctrl :pad :out-bus 0)
    (Thread/sleep 1000)
    (def/proxy :pad nil))
  (server/server-quit s)
  (println :done))
