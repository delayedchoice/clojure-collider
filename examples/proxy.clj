;; Node proxy swap, mirroring the cl-collider README proxy example.
;; Run: clojure -M -m proxy   (boots its own scsynth)
(ns proxy
  (:require [clojure-collider.server.server :as server]
            [clojure-collider.synth.sdef :as sdef]
            [ugens :as ug]))

(defn -main [& _]
  (let [s (server/make-external-server "example" :port 57133)]
    (server/server-boot s)
    (binding [server/*s* s]
      (sdef/proxy :pad (ug/sin-osc:ar 220 0 0.1))
      (Thread/sleep 1000)
      (sdef/proxy :pad (ug/sin-osc:ar 330 0 0.1))
      (Thread/sleep 1000)
      (sdef/proxy-ctrl :pad :out-bus 0)
      (Thread/sleep 1000)
      (sdef/proxy :pad nil))
    (server/server-quit s)
    (println :done)))
