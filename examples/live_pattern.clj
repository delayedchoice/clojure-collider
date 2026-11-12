;; Live patterns, minimal: one looping line you re-define while it plays.
;; No stop/play needed - tasks re-resolve their pdef every step, so a
;; re-pdef sounds on the next note. Run: clojure -M -m live-pattern
;; (boots its own scsynth, ~20s demo). REPL:
;;   (require 'live-pattern)
;;   (def h (live-pattern/start!))
;;   (p/pdef :live-line (p/pbind :instrument :live-blip :midinote 79 :dur 0.5))
;;   (p/halt! h)
(ns live-pattern
  (:require [clojure-collider.server.server :as server]
            [clojure-collider.synth.sdef :as sdef]
            [clojure-collider.synth.operators :as ops]
            [pattern :as p]
            [ugens :as ug]))

(defn start!
  "Boot, define one blip voice, start one looping line. Returns
   {:server :clock} - pass it whole to p/halt!."
  [& {:keys [port] :or {port 57198}}]
  (let [s (server/make-external-server "live-pattern" :port port)
        clock (p/start-clock-loop :tempo 60)]
    (p/connect-server! clock s)
    (sdef/defsynth s :live-blip [freq 440 amp 0.15]
      (let [env (ug/env-gen:kr (ug/env [0.0 1.0 0.0] [0.01 0.3]) :act :free)]
        (ug/out:ar 0 (ug/pan2:ar (ops/* (ug/sin-osc:ar freq) env amp) 0.0))))
    (p/pdef :live-line
      (p/pbind :instrument :live-blip :midinote 60 :dur 0.5))
    (p/play clock :live-line)
    {:server s :clock clock}))

(defn -main [& _]
  (let [h (start!)]
    (println :playing :midinote 60 :dur 0.5)
    (Thread/sleep 4000)
    (println :redefining :midinote 72)
    (p/pdef :live-line
      (p/pbind :instrument :live-blip :midinote 72 :dur 0.5))
    (Thread/sleep 4000)
    (println :redefining :melody [67 72 74 79])
    (p/pdef :live-line
      (p/pbind :instrument :live-blip
               :midinote (p/pseq [67 72 74 79])
               :dur 0.25))
    (Thread/sleep 6000)
    (p/halt! h)
    (println :done)))
