;; Offline render, mirroring the cl-collider README with-rendering example.
;; Run: clojure -M -m nrt   (no server needed; writes /tmp/nrt-ex.aiff)
(ns nrt
  (:require [clojure-collider.server.server :as server]
            [clojure-collider.synth.sdef :as sdef]
            [clojure-collider.ugens.osc :as osc]))

(defn -main [& _]
  (let [f (server/with-rendering ["/tmp/nrt-ex.aiff" :sr 44100 :pad 1.0]
            (sdef/play (osc/sin-osc:ar 440 0 0.2)))]
    (println :rendered f)))
