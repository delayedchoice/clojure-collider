;; Hack music: two detuned sines, then stop.
;; Run: clojure -M -m sine   (boots its own scsynth)
(ns sine
  (:require [clojure-collider.server.server :as server]
            [clojure-collider.synth.sdef :as sdef]
            [ugens :as ug]))

(defn -main [& _]
  (let [s (server/make-external-server "example" :port 57130)]
    (server/server-boot s)
    (binding [server/*s* s]
      (let [n (sdef/play (ug/sin-osc:ar [320 321] 0 0.2 0))]
        (Thread/sleep 1500)
        (server/node-free n)))
    (server/server-quit s)
    (println :done)))
