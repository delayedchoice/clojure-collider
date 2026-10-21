;; "endless drone": Ndef(\a).fadeTime_(15) + a forked loop that resets the
;; source every 9 seconds.
;; SC: Ndef(\a).fadeTime_(15).play;
;;     fork{loop{Ndef(\a,{Klank.ar(`[9.collect{[5000.0.rand,5000.0.rand]},
;;                                nil,1!4],PinkNoise.ar(7e-3))/3});9.wait}}
;; Run: clojure -M -m endless-drone [seed=7] [seconds=90]
;;   (boots its own scsynth)
;;
;; Translation notes (SuperCollider -> here):
;; - The SC spec rows are 9 [5000.rand, 5000.rand] pairs with amps nil:
;;   freqs are drawn fresh per iteration into 9 `fN` controls, amps stay
;;   1.0 (decays default to 1.0; the SC `1!4` only supplies four). This
;;   matches the SC, where the 5000-range values evidently never reach
;;   the amps.
;; - Ndef crossfade (fadeTime 15) = fixed 24s voice life, self-freeing:
;;   attack to 0.6 over the 9s period, full at +15s, silent by +24s.
;;   No gate, no previous-voice kill - possible only because the 9s
;;   period is fixed and known.
;; - Limiter at 0.8 is a safety net only; sane levels never touch it.
(ns endless-drone
  (:require [clojure-collider.server.server :as server]
            [clojure-collider.server.options :as opts]
            [clojure-collider.synth.sdef :as sdef]
            [clojure-collider.synth.operators :as ops]
            [ugens :as ug]
            [patterns.util :as u]))

(def bpm 120)
(def iteration-secs 9)
(def fade-secs 15)
(def amp-scale (/ 1.0 3.0))

(defn define-voice!
  "Single :endless def. Fixed 24s life (9s period + 15s fade): attack to
  0.6 over the first period, full at +15s, released by +24s - exactly the
  Ndef crossfade, self-freeing, so no gate and no previous-voice kill.
  (Possible only because the 9s period is fixed and known.)"
  []
  (sdef/defsynth :endless
    [f0 440 f1 440 f2 440 f3 440 f4 440 f5 440 f6 440 f7 440 f8 440
     a0 1.0 a1 1.0 a2 1.0 a3 1.0 a4 1.0 a5 1.0 a6 1.0 a7 1.0 a8 1.0
     out 0]
    (let [res (ug/klank:ar [[f0 f1 f2 f3 f4 f5 f6 f7 f8]
                            [a0 a1 a2 a3 a4 a5 a6 a7 a8]
                            nil]
                           (ug/pink-noise:ar 7e-3))
          env (ug/env-gen:kr (ug/env [0 (/ iteration-secs fade-secs) 1 0]
                                    [iteration-secs
                                     (- fade-secs iteration-secs)
                                     fade-secs] :sine)
                             :act :free)
          sig (ops/* res env)]
      (ug/out:ar out (ug/limiter:ar (ops/* sig amp-scale) 0.8)))))

(defn- draw-partials
  "Nine [freq amp] pairs: freqs uniform in [0, 5000), amps unity.
  (The SC spec rows are 9 [5000.rand, 5000.rand] pairs with amps nil -
  in SC this evidently does NOT apply the 5000-range values as amps
  (it sounds fine there), so amps stay 1.0 and only freqs are drawn.)"
  []
  (repeatedly 9 (fn [] [(u/rand-range 0 5000) 1.0])))

(defn- start-voice!
  "Start one iteration's voice."
  [pairs]
  (let [ks (mapcat (fn [i [f a]]
                     [(keyword (str "f" i)) f (keyword (str "a" i)) a])
                   (range)
                   pairs)]
    (apply sdef/synth :endless ks)))

(defn -main [& [seed-arg secs-arg]]
  (let [seed (if seed-arg (Long/parseLong seed-arg) 7)
        secs (if secs-arg (Long/parseLong secs-arg) 90)
        s (server/make-external-server
           "example" :port 57139
           :options (opts/make-server-options {:realtime-mem-size 65536
                                               :max-num-nodes 4096}))]
    (u/seed-rng! seed)
    (server/server-boot s)
    (binding [server/*s* s]
      (define-voice!)
      (loop [deadline (+ (System/currentTimeMillis) (* secs 1000))]
        (start-voice! (draw-partials))
        (Thread/sleep (* iteration-secs 1000))
        (if (< (System/currentTimeMillis) deadline)
          (recur deadline)
          (Thread/sleep (* (+ iteration-secs fade-secs) 1000)))))
    (server/server-quit s)
    (println :done)))
