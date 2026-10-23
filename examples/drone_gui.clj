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
   :offset {:lo 0.0 :hi 24.0} ; negative poisons voice 1: log2(0)/1/0 -> sink-killing inf/nan
   :pitch {:lo -4.0 :hi 4.0}
   :res {:hi 1.0} ; rq clamps above ~0.85: higher is identical
   :pan {:lo -144.0 :hi 12.0} ; LFO rate in disguise; lower is frozen
   :amt {:lo 0.0 :hi 2.0} ; semitone wander: shimmer -> seasick
   :rnd2 {:lo -120.0 :hi 0.0}
   :gain {:hi 1.0}}) ; cubed downstream, no limiter: hotter just clips

(defn -main [& _]
  (let [s (server/make-external-server "drone-gui" :port 57145)]
    (server/server-boot s)
    ;; Ctrl-C kills the JVM without touching the window listener: without
    ;; this the scsynth child survives orphaned, still sounding.
    (.addShutdownHook (Runtime/getRuntime)
      (Thread. ^Runnable
        (fn [] (try (when (server/boot? s) (server/server-quit s))
                    (catch Exception e
                      (binding [*out* *err*]
                        (println :shutdown-quit-failed (ex-message e))))))))
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
