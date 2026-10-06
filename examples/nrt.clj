;; Offline render, mirroring the cl-collider README with-rendering example.
;; Run: clojure -M examples/nrt.clj   (no server needed; writes /tmp/nrt-ex.aiff)
(require '[clojure-collider.server.server :as server]
         '[clojure-collider.synth.sdef :as sdef]
         '[clojure-collider.ugens.osc :as osc])

(let [f (server/with-rendering ["/tmp/nrt-ex.aiff" :sr 44100 :pad 1.0]
          (sdef/play (osc/sin-osc:ar 440 0 0.2)))]
  (println :rendered f))
