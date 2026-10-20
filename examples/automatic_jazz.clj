;; "Automatic jazz" from upstream doc/cookbook.org: shuffled minor-scale notes
;; with random octave/root per cycle over a 1/3-1/4 shuffle rhythm.
;; Run: clojure -M -m automatic-jazz   (boots its own scsynth)
;;
;; Deviation: the cookbook leaves :instrument implicit (SC's default synth);
;; we define a small gated voice here since cl-collider has no stock default.
(ns automatic-jazz
  (:require [clojure-collider.server.server :as server]
            [clojure-collider.synth.sdef :as sdef]
            [clojure-collider.ugens.osc :as osc]
            [clojure-collider.ugens.env-gen :as e]
            [clojure-collider.ugens.in-out :as io]
            [clojure-collider.ugens.pan :as pan]
            [patterns.patterns :as p]
            [patterns.pbind :as pb]
            [patterns.scales :as scales]
            [patterns.backend :as backend]
            [patterns.clock :as clock]))

(defn -main [& _]
  (let [s (server/make-external-server "example" :port 57137)]
    (server/server-boot s)
    (binding [server/*s* s]
      (sdef/defsynth :jazz [gate 1 freq 440 amp 0.3 out 0]
        (let [env (e/env-gen:kr (e/env-asr 0.01 1 0.1) :gate gate :act :free)
              sig (osc/sin-osc:ar freq 0 amp)]
          (io/out:ar out (pan/pan2:ar sig 0 env))))
      (clock/start-clock-loop :tempo 90)
      (clock/enable-backend! (backend/make-cl-collider-backend s))
      (pb/pb :automatic-jazz
        :instrument :jazz
        :note (p/pshuf (scales/scale-notes :minor) 4)
        :octave (p/pr (p/pwhite 2 7))
        :root (p/pr (p/pwhite 0 12))
        :dur (p/pshuf [1/3 1/4]))
      (clock/play :automatic-jazz)
      (Thread/sleep 20000)
      (clock/end :automatic-jazz)
      (Thread/sleep 3000))
    (server/server-quit s)
    (println :done)))
