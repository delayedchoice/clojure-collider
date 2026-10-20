;; Eli Fieldsteel FM Synthesis 1 (SuperCollider tutorial), translated from
;; ntrocado/cl-collider-examples 21.fm-synthesis-1.lisp
;; (https://www.youtube.com/watch?v=UoXMUQIqFk4).
;; Run: clojure -M -m fm-synthesis   (boots its own scsynth)
;;
;; Two halves: (1) a mouse-played FM drone. On headless machines MouseX/Y sit
;; at fixed values, so it still renders, just statically. (2) a percussive FM
;; voice sequenced by a real cl-patterns pb (pwhite/pexprand params) on the
;; patterns clock — the earlier hand-rolled doseq loop is gone.
(ns fm-synthesis
  (:require [clojure-collider.server.server :as server]
            [clojure-collider.synth.sdef :as sdef]
            [clojure-collider.synth.operators :as ops]
            [ugens :as ug]
            [pattern :as p]))

(defn -main [& _]
  (let [s (server/make-external-server "example" :port 57134)]
    (server/server-boot s)
    (binding [server/*s* s]
      ;; Carrier freq from mouse Y (exp 200-5000), modulator freq from mouse X
      ;; (exp 1-2000), mod depth wandered by stepped noise (20-10000, 8 steps/s).
      ;; poll.kr prints the mouse values server-side 10x/sec — the UGen(...):
      ;; <val> lines in the output ARE the demo. Slow the first poll arg or
      ;; drop poll to silence them. dup = mono to stereo pair.
      (let [drone (sdef/play
                    (ops/dup (ops/* (ug/sin-osc:ar
                                      (ops/+ (ug/poll:kr 10 (ug/mouse-y:kr 200 5000 :exp))
                                             (ug/sin-osc:ar
                                               (ug/poll:kr 10 (ug/mouse-x:kr 1 2000 :exp))
                                               0 (ops/range (ug/lf-noise0:kr 8) 20 10000)))
                                      0 0.2))))]
        (Thread/sleep 6000)
        (server/node-free drone))
      ;; Percussive FM voice: carrier + one modulator, perc envelope frees the
      ;; synth (like upstream :act :free), panned stereo out.
      (sdef/defsynth :fm [car-hz 500 mod-hz 100 mod-amp 200 atk 0.01 rel 1 amp 0.2 pan 0]
        (ug/out:ar 0 (ug/pan2:ar (ops/* (ug/env-gen:kr (ug/env-perc atk rel) :act :free)
                                        (ug/sin-osc:ar (ops/+ car-hz
                                                               (ug/sin-osc:ar mod-hz 0 mod-amp)))
                                        amp)
                                 pan)))
      ;; The pattern half: 32 eighth-notes (dur 1/8 at 60 BPM), every param
      ;; drawn fresh per note — pexprand for the exponential FM ranges,
      ;; pwhite for the uniform ones. Replaces the hand-rolled server/at loop.
      (p/start-clock-loop :tempo 60)
      (p/enable-backend! (p/make-cl-collider-backend s))
      (p/pb :p
        :instrument :fm
        :dur 1/8
        :car-hz (p/pexprand 20 10000)
        :mod-hz (p/pexprand 20 10000)
        :mod-amp (p/pwhite 0 10000)
        :amp (p/pexprand 0.1 0.5)
        :atk (p/pexprand 0.001 0.05)
        :rel (p/pexprand 0.05 1.2)
        :pan (p/pwhite -1.0 1.0))
      (p/play :p)
      ;; 32 eighth-notes at 60 BPM = 4s of notes + ~1.2s longest tail.
      (Thread/sleep 7000)
      (p/stop :p))
    (server/server-quit s)
    (println :done)))
