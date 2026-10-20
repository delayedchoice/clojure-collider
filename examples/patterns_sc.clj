;; cl-patterns SuperCollider demo, translated from upstream
;; doc/supercollider-example.lisp: two looping pdefs (kick ostinato + random
;; major-scale melody) started on the patterns clock, then ended.
;; Run: clojure -M -m patterns-sc   (boots its own scsynth)
(ns patterns-sc
  (:require [clojure-collider.server.server :as server]
            [clojure-collider.synth.sdef :as sdef]
            [clojure-collider.ugens.osc :as osc]
            [clojure-collider.ugens.env-gen :as e]
            [clojure-collider.ugens.in-out :as io]
            [clojure-collider.ugens.pan :as pan]
            [patterns.patterns :as p]
            [patterns.pbind :as pb]
            [patterns.backend :as backend]
            [patterns.clock :as clock]))

(defn -main [& _]
  (let [s (server/make-external-server "example" :port 57136)]
    (server/server-boot s)
    (binding [server/*s* s]
      ;; Pitch-swept kick: the sweep tracks :freq via :level-scale.
      (sdef/defsynth :kik [freq 440 out 0]
        (let [env (e/env-gen:kr (e/env [0 1 0] [0.001 1]) :act :free)
              fenv (e/env-gen:kr (e/env [1 0] [0.25]) :level-scale freq)
              sig (osc/sin-osc:ar fenv 0 0.2)]
          (io/out:ar out (pan/pan2:ar sig 0 env))))
      ;; Gated voice for the melody (backend releases :gate per sustain).
      (sdef/defsynth :default [gate 1 freq 440 out 0]
        (let [env (e/env-gen:kr (e/env-asr 0.01 1 0.1) :gate gate :act :free)
              sig (osc/sin-osc:ar freq 0 0.2)]
          (io/out:ar out (pan/pan2:ar sig 0 env))))
      ;; 110 BPM clock + cl-collider backend driving the server above.
      (clock/start-clock-loop :tempo 110)
      (clock/enable-backend! (backend/make-cl-collider-backend s))
      ;; Four-on-the-floor kick, 4 events per loop, starts on a 4-beat grid.
      (pb/pb :foo
        :instrument :kik
        :play-quant 4
        :dur 1
        :pfin 4)
      ;; Random walk over the first 7 major degrees, 4 beats per loop.
      (pb/pb :bar
        :instrument :default
        :play-quant 4
        :dur 1/2
        :scale :major
        :degree (p/pwhite 0 7)
        :pfindur 4)
      (clock/play :foo)
      (clock/play :bar)
      (Thread/sleep 16000)
      ;; end = finish the current loop (kinder than stop's guillotine).
      (clock/end :foo)
      (clock/end :bar)
      (Thread/sleep 4000))
    (server/server-quit s)
    (println :done)))
