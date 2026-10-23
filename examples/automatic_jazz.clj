;; "Automatic jazz" from upstream doc/cookbook.org: shuffled minor-scale notes
;; with random octave/root per cycle over a 1/3-1/4 shuffle rhythm.
;; Run: clojure -M -m automatic-jazz   (boots its own scsynth)
;;
;; Deviation: the cookbook leaves :instrument implicit (SC's default synth);
;; we define a small gated voice here since cl-collider has no stock default.
(ns automatic-jazz
  (:require [clojure-collider.server.server :as server]
            [clojure-collider.synth.sdef :as sdef]
            [ugens :as ug]
            [pattern :as p]))

(defn -main [& _]
  (let [s (server/make-external-server "example" :port 57137)]
    (server/server-boot s)
    (binding [server/*s* s]
      (sdef/defsynth :jazz [gate 1
                              freq 440
                              amp 0.3
                              out 0]
        (let [env (ug/env-gen:kr (ug/env-asr 0.01 1 0.1) :gate gate :act :free)
              sig (ug/sin-osc:ar freq 0 amp)]
          (ug/out:ar out (ug/pan2:ar sig 0 env))))
      (p/start-clock-loop :tempo 90)
      (p/enable-backend! (p/make-cl-collider-backend s))
      (p/pb :automatic-jazz
        :instrument :jazz
        :note (p/pshuf (p/scale-notes :minor) 4)
        :octave (p/repeat-each (p/pwhite 2 7))
        :root (p/repeat-each (p/pwhite 0 12))
        :dur (p/pshuf [1/3 1/4]))
      (p/play :automatic-jazz)
      (Thread/sleep 20000)
      (p/end :automatic-jazz)
      (Thread/sleep 3000))
    (server/server-quit s)
    (println :done)))
