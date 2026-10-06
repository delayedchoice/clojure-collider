(ns clojure-collider.volume
  "Master-volume control synth (volume.lisp): set-volume!/volume/mute/unmute.
   The control synth is a fade-gated play graph ending in XOut, placed
   after group 1; later adjustments go through ctrl."
  (:require [clojure-collider.server.server :as server]
            [clojure-collider.synth.sdef :as sdef]
            [clojure-collider.synth.control :as ctl]
            [clojure-collider.synth.operators :as ops]
            [clojure-collider.ugens.in-out :as io]
            [clojure-collider.ugens.env-gen :as e]
            [clojure-collider.ugens.filter :as flt]))

(defn volume
  "Current master volume in dB."
  [server]
  @(:volume server))

(defn set-volume!
  "Set master volume (clamped to [-90 6] dB). Starts the control synth on
   first non-unity level, adjusts it while running, releases it at unity."
  [server db]
  (let [db (max -90.0 (min 6.0 (double db)))
        amp (ops/dbamp db)
        ctl-synth @(:volume-control-synth server)
        playing? (and ctl-synth (server/is-playing? ctl-synth))]
    (if (not= amp 1.0)
      (if-not playing?
        (let [num-out (:num-output-bus (:options server))]
          (reset! (:volume-control-synth server)
                  (binding [server/*s* server
                            sdef/*temp-synth-name* (str "volume-amp-control-" num-out)]
                    (sdef/play
                      (ctl/with-controls ((amp amp) (gate 1))
                        (io/x-out:ar 0 (e/linen:kr gate 0.01 1.0 0.05 :act :free)
                                     (ops/* (io/in:ar 0 num-out)
                                            (flt/lag:kr amp 0.1))))
                      :to 1 :pos :after))))
        (server/ctrl ctl-synth :amp amp))
      (when playing?
        (server/ctrl ctl-synth :amp 1.0 :gate 0)))
    (reset! (:volume server) db)
    server))

(defn mute
  "Drop to -90 dB, remembering the level for unmute."
  [server]
  (let [prev (volume server)]
    (set-volume! server -90)
    (reset! (:volume server) prev)
    server))

(defn unmute
  "Restore the pre-mute level."
  [server]
  (set-volume! server (volume server)))
