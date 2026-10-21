;; "endless drone": Ndef(\a).fadeTime_(15) + a forked loop that resets the
;; source every 9 seconds.
;; SC: Ndef(\a).fadeTime_(15).play;
;;     fork{loop{Ndef(\a,{Klank.ar(`[9.collect{[5000.0.rand,5000.0.rand]},
;;                                nil,1!4],PinkNoise.ar(7e-3))/3});9.wait}}
;; Run: clojure -M -m endless-drone [seed=7] [seconds=90]
;;   (boots its own scsynth; seconds 0 = play until Ctrl-C)
;;
;; Translation notes (SuperCollider -> here):
;; - The SC spec rows are 9 [5000.rand, 5000.rand] pairs with amps nil:
;;   freqs stream from nine `pwhite` keys, amps stay 1.0 (decays default
;;   to 1.0; the SC `1!4` only supplies four). This matches the SC, where
;;   the 5000-range values evidently never reach the amps.
;; - Ndef crossfade (fadeTime 15) = fixed 24s voice life, self-freeing:
;;   attack to 0.6 over the 9s period, full at +15s, silent by +24s.
;;   No gate, no previous-voice kill - possible only because the 9s
;;   period is fixed and known.
;; - Limiter at 0.8 is a safety net only; sane levels never touch it.
(ns endless-drone
  (:require [clojure-collider.server.server :as server]
            [clojure-collider.synth.sdef :as sdef]
            [clojure-collider.synth.operators :as ops]
            [ugens :as ug]
            [pattern :as p]))

(def bpm 120)
(def iteration-secs 9)
(def fade-secs 15)
(def amp-scale (/ 1.0 3.0))

(defn define-voice!
  "Single :endless def. Fixed 24s life (9s period + 15s fade),
  self-freeing - see header."
  []
  (sdef/defsynth :endless
    [f0 440 f1 440 f2 440 f3 440 f4 440 f5 440 f6 440 f7 440 f8 440
     out 0]
    (let [res (ug/klank:ar [[f0 f1 f2 f3 f4 f5 f6 f7 f8]
                            [1.0 1.0 1.0 1.0 1.0 1.0 1.0 1.0 1.0]
                            nil]
                           (ug/pink-noise:ar 7e-3))
          env (ug/env-gen:kr (ug/env [0 (/ iteration-secs fade-secs) 1 0]
                                    [iteration-secs
                                     (- fade-secs iteration-secs)
                                     fade-secs] :sine)
                             :act :free)
          sig (ops/* res env)]
      (ug/out:ar out (ug/limiter:ar (ops/* sig amp-scale) 0.8)))))

(defn create-phrase
  "Looping pbind: every 9 beats a fresh 9-partial chord on :endless."
  []
  (apply p/pbind :dur iteration-secs :instrument :endless
         (mapcat (fn [i] [(keyword (str "f" i)) (p/pwhite 0 5000)])
                 (range 9))))

(defn player []
  (p/stop)
  (p/start-clock-loop :tempo bpm)
  (p/pdef :endpat (create-phrase))
  (p/play :endpat))

(defn -main [& [seed-arg secs-arg]]
  (let [seed (if seed-arg (Long/parseLong seed-arg) 7)
        secs (if secs-arg (Long/parseLong secs-arg) 90)]
    (binding [server/*s* (p/setup-performance!
                           :seed seed :port 57139 :tempo bpm
                           :name "endless")]
      (define-voice!)
      (player)
      (if (pos? secs)
        (do (Thread/sleep (* secs 1000))
            (p/end :endpat)
            (Thread/sleep (* (+ iteration-secs fade-secs) 1000)))
        (do (println :playing-forever-ctrl-c-to-stop)
            (.addShutdownHook (Runtime/getRuntime)
              (Thread. ^Runnable
                (fn [] (server/server-quit server/*s*))))
            (while true
              (Thread/sleep 60000)))))
    (println :done)))
