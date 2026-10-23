;; Eli Fieldsteel FM Synthesis 2, translated from
;; ntrocado/cl-collider-examples 22.fm-synthesis-2.lisp
;; (https://www.youtube.com/watch?v=dLMSR2Kjq6Y).
;; Run: clojure -M -m fm-synthesis-2   (boots its own scsynth)
;;
;; Notes on the translation: upstream `(load "../util.lisp")` points at a file
;; that was never committed upstream; its `dupl` is our `dup`. `mod~` (audio
;; modulo UGen) is `ops/mod*` here. `pm-osc` was a plain CL function, so it is
;; a plain Clojure fn here. Envelope curve LISTS pass straight through to
;; `ug/env` / `ug/env-perc`.
(ns fm-synthesis-2
  (:require [clojure-collider.server.server :as server]
            [clojure-collider.synth.sdef :as sdef]
            [clojure-collider.synth.operators :as ops]
            [ugens :as ug]))

;; Phase-modulation pseudo-UGen (upstream defined it via defun, same idea).
(defn pm-osc [car-freq mod-freq pm-index mod-phase mul add]
  (ug/sin-osc:ar car-freq
                 (ops/mod* (ug/sin-osc:ar mod-freq mod-phase pm-index)
                           (* 2 Math/PI))
                 mul add))

(defn -main [& _]
  (let [s (server/make-external-server "example" :port 57135)]
    (server/server-boot s)
    (binding [server/*s* s]
      ;; Index-swept FM: the mod depth itself follows an envelope
      ;; (index -> index*i-scale -> index) over atk/rel.
      (sdef/defsynth :fm [freq 500
                            m-ratio 1
                            c-ratio 1
                            index 1
                            i-scale 5
                            amp 0.2
                            atk 0.01
                            rel 3
                            c-atk 4
                            c-rel -4
                            pan 0]
        (let [ienv (ug/env-gen:kr (ug/env [index (ops/* index i-scale) index]
                                          [atk rel] [c-atk c-rel]))
              env (ug/env-gen:kr (ug/env-perc atk rel 1 [c-atk c-rel]) :act :free)
              mod (ug/sin-osc:ar (ops/* freq m-ratio) 0 (ops/* freq m-ratio ienv))
              car (ops/* (ug/sin-osc:ar (ops/+ (ops/* freq c-ratio) mod))
                         env amp)]
          (ug/out:ar 0 (ug/pan2:ar car pan))))
      (sdef/synth :fm :freq (ops/midicps 35) :rel 4 :index 20 :i-scale 0.05 :m-ratio 0.5)
      (Thread/sleep 5000)
      ;; Three vibrato/PM flavors stacked, as in the tutorial.
      (let [n1 (sdef/play (ops/dup (ops/* (ug/sin-osc:ar (ops/+ 500 (ug/sin-osc:ar 4 0 (* 4 50)))) 0.2)))
            n2 (sdef/play (ops/dup (ops/* (ug/sin-osc:ar 500 (ug/sin-osc:ar 4 0 10)) 0.2)))
            n3 (sdef/play (ops/dup (ops/* (pm-osc 500 4 50 0 1 0) 0.2)))]
        (Thread/sleep 6000)
        (doseq [n [n1 n2 n3]] (server/node-free n)))
      ;; Two-operator FM with a sub-audio second modulator.
      (sdef/defsynth :fm2 [freq 500
                             m-ratio 1
                             c-ratio 1
                             index 1
                             i-scale 5
                             amp 0.2
                             atk 0.01
                             rel 3
                             c-atk 4
                             c-rel -4
                             pan 0]
        (let [ienv (ug/env-gen:kr (ug/env [index (ops/* index i-scale) index]
                                          [atk rel] [c-atk c-rel]))
              env (ug/env-gen:kr (ug/env-perc atk rel 1 [c-atk c-rel]) :act :free)
              mod2 (ug/sin-osc:ar (ops// freq 10) 0 (ops/* (ops// freq 10) ienv))
              mod (ug/sin-osc:ar (ops/+ (ops/* freq m-ratio) mod2) 0 (ops/* freq m-ratio ienv))
              car (ops/* (ug/sin-osc:ar (ops/+ (ops/* freq c-ratio) mod))
                         env amp)]
          (ug/out:ar 0 (ug/pan2:ar car pan))))
      (sdef/synth :fm2 :rel 3)
      (Thread/sleep 5000))
    (server/server-quit s)
    (println :done)))
