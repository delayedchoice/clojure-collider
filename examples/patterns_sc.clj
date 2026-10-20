;; cl-patterns SuperCollider demo, translated from upstream
;; doc/supercollider-example.lisp: two looping pdefs (kick ostinato + random
;; major-scale melody) started on the patterns clock, then ended.
;; Run: clojure -M -m patterns-sc   (boots its own scsynth)
(ns patterns-sc
  (:require [clojure-collider.server.server :as server]
            [clojure-collider.synth.sdef :as sdef]
            [ugens :as ug]
            [pattern :as p]))

(defn -main [& _]
  (let [s (server/make-external-server "example" :port 57136)]
    (server/server-boot s)
    (binding [server/*s* s]
      ;; Pitch-swept kick: the sweep tracks :freq via :level-scale.
      (sdef/defsynth :kik [freq 440 out 0]
        (let [env (ug/env-gen:kr (ug/env [0 1 0] [0.001 1]) :act :free)
              fenv (ug/env-gen:kr (ug/env [1 0] [0.25]) :level-scale freq)
              sig (ug/sin-osc:ar fenv 0 0.2)]
          (ug/out:ar out (ug/pan2:ar sig 0 env))))
      ;; Gated voice for the melody (backend releases :gate per sustain).
      (sdef/defsynth :default [gate 1 freq 440 out 0]
        (let [env (ug/env-gen:kr (ug/env-asr 0.01 1 0.1) :gate gate :act :free)
              sig (ug/sin-osc:ar freq 0 0.2)]
          (ug/out:ar out (ug/pan2:ar sig 0 env))))
      ;; 110 BPM clock + cl-collider backend driving the server above.
      (p/start-clock-loop :tempo 110)
      (p/enable-backend! (p/make-cl-collider-backend s))
      ;; Four-on-the-floor kick, 4 events per loop, starts on a 4-beat grid.
      (p/pb :foo
        :instrument :kik
        :play-quant 4
        :dur 1
        :pfin 4)
      ;; Random walk over the first 7 major degrees, 4 beats per loop.
      (p/pb :bar
        :instrument :default
        :play-quant 4
        :dur 1/2
        :scale :major
        :degree (p/pwhite 0 7)
        :pfindur 4)
      (p/play :foo)
      (p/play :bar)
      (Thread/sleep 16000)
      ;; end = finish the current loop (kinder than stop's guillotine).
      (p/end :foo)
      (p/end :bar)
      (Thread/sleep 4000))
    (server/server-quit s)
    (println :done)))
