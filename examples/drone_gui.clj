;; Generic-GUI test seat for the drone-collider voice: derives slider specs
;; from the :drone-collider defsynth metadata and opens one live voice.
;; Run: clojure -M -m drone-gui (boots its own scsynth; PLAY starts voice 1)
(ns drone-gui
  (:require [clojure-collider.server.server :as server]
            [drone-collider :as dc]
            [synth-gui :as g]))

(def ranges
  {:attack {:lo 1.0 :hi 320.0 :exp? true}
   :decay {:lo 1.0 :hi 320.0 :exp? true}
   :fundamental {:lo 8.0 :hi 128.0 :exp? true}
   :speed {:lo 0.1 :hi 10.0 :exp? true}
   :offset {:lo -24.0 :hi 24.0}
   :pitch {:lo -4.0 :hi 4.0}})

(defn -main [& _]
  (let [s (server/make-external-server "drone-gui" :port 57145)]
    (server/server-boot s)
    (binding [server/*s* s]
      (dc/define-voice!))
    (let [specs (g/specs-from-synthdef :drone-collider
                                       :skip #{:out :voiceid}
                                       :ranges ranges)]
      (println :controls (count specs))
      (g/start! s :drone-collider
                :specs specs :title "Drone Collider"
                :extra-args {:voiceid 1}))
    (println :playing-press-play)))
