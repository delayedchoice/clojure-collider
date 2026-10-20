;; "A loss of self" by Daniel M. Karlsson, translated from
;; github.com/danielmkarlsson/a_loss_of_self ("A loss of self.scd"):
;; seeded runs of same-chord swells through an FM/PM voice with tanh,
;; Karplus-Strong, resonant lowpass, ringing filter and hearing
;; compensation, freed on envelope end.
;; Run: clojure -M -m clj-collider-drone [seed=2160] [seconds=45] [fade=5]
;;   (boots its own scsynth)
;;
;; Translation notes (SuperCollider -> here):
;; - `Pseed(n, ...)` is the seed arg (default 2160, as upstream).
;; - `Pstutter(n, pat)` = repeat-each with a count fn drawing its own
;;   white stream (n re-drawn per picked chord, as upstream).
;; - `Pkey(k)` = `(fn [ev] ...)` reading the partially built event (pbind
;;   evaluates pairs left-to-right, so referenced keys come first).
;; - `Prand([Pwhite..1, Pwhite..1])` embeds one single draw per pick.
;; - Reversed `Pwhite(0.6, 0.05)` works (negative span uniform).
(ns clj-collider-drone
  (:require [clojure-collider.server.server :as server]
            [clojure-collider.server.options :as opts]
            [clojure-collider.synth.sdef :as sdef]
            [clojure-collider.synth.operators :as ops]
            [ugens :as ug]
            [patterns.util :as u]
            [pattern :as p]))

(def bpm 120)

(defn define-voice!
  "Define :als + :mix-limiter on the current *s* (server or NRT score).
  Must run after boot / inside with-rendering."
  []
  (sdef/defsynth :als
    [freq 440 atk 0.005 rel 0.3 amp 1 pan 0 gain 1
     mod-index 2 phase-mod-index 2 lpf-freq 18000
     resonance 0 hold 6.0 freq-deviation 0.975
     transition-time 0.875 resonz-q 0.1 out 0]
    (let [frq (ug/clip freq 20 20000)
          ;; FM depth ramps 0 -> mod-index over transition-time, scaled by freq.
          mod (ug/sin-osc:ar (ops// frq 2) 0
                             (ops/* (ug/line:ar 0 mod-index transition-time) frq))
          phase-mod (ug/sin-osc:ar frq 0 phase-mod-index)
          ;; Glissando: exponential line from deviation to pitch.
          sig (ug/sin-osc:ar (ops/+ (ug/x-line:ar freq-deviation freq atk) mod)
                             phase-mod)
          env (ug/env-gen:kr (ug/env [0 1 1 0] [atk hold rel] :sine) :act :free)
          ;; Panning glides 0 -> pan; amp rides as the level.
          sig (ug/pan2:ar sig (ug/line:ar 0 pan transition-time) amp)
          ;; Karplus-Strong mixer.
          sig (ops/+ (ops/* sig 0.8)
                     (ops/* (ug/pluck:ar sig (ug/pink-noise:ar) 0.25
                                         (ops// 1 freq) 64.0 0.5)
                            0.4))
          ;; Resonant lowpass; the envelope applies here.
          sig (ops/* (ug/rlpf:ar sig
                                 (ug/clip lpf-freq 200 20000)
                                 (ug/clip (ug/line:ar 1.0 resonance resonance atk)
                                             0.1 1.0))
                     env)
          ;; tanh distortion with ramping gain.
          sig (ops/tanh (ops/* (ug/line:ar 0.001 gain transition-time) sig))
          ;; Ringing filter + hearing compensation.
          sig (ops/+ (ops/* sig 0.75)
                     (ops/* (ug/resonz:ar sig freq resonz-q) 1.125))
          sig (ops/* sig (ug/amp-comp-a:kr freq 12.978271799373))
          ;; Gentle compression, DC block, rumble filter, brick wall.
          sig (ug/compander:ar sig sig 0.25 0.33 1.0 0.002 0.1)
          sig (ug/leak-dc:ar sig)
          sig (ug/hpf:ar sig 32)]
      (ug/out:ar out (ug/limiter:ar sig 0.1))))
  (sdef/defsynth :mix-limiter [bus 16 amp 1.0]
    (let [sig (ug/in:ar bus 2)
          sig (ops/* sig 0.5 amp)]
      (ug/out:ar 0 (ug/compander:ar sig sig 0.7 1.0 0.05 0.001 0.1)))))
(def chords
  [[44 20 21 27]
   [32 20 39 40]
   [20 44 45 20 51]
   [44 56 20 51 52]
   [32 44 51 20 28]])

(defn create-phrase [seed]
  (binding [u/*rng* (java.util.Random. seed)]
    (let [stutter-n (p/as-pstream (p/pwhite 2 7))
          dev-white (p/as-pstream (p/pwhite -0.25 0.0))
          lp-white (p/as-pstream (p/pwhite 3.0 9.0))
          harm (p/as-pstream (p/pexprand 1 9))
          n-of (fn [_] (* 2 (Math/round (double (p/pnext! stutter-n)))))]
      (p/pbind
        :instrument :als
        :dur (p/pwhite 0.1 10.0)
        :gain (p/pexprand 0.001 1.5)
        :transition-time (p/prand [0.875 2.0])
        :mod-index (p/pwhite 0.00001 3.0)
        :phase-mod-index (p/pwhite 0.00001 0.1)
        ;; One picked chord repeated N events in a row (N re-drawn per pick).
        :midinote (p/repeat-each (p/prand chords) n-of)
        :harmonic (p/pfunc #(Math/round (double (p/pnext! harm))))
        ;; Per-event reads of sibling keys (upstream Pkey).
        :freq-deviation (fn [ev] (mapv #(* (:harmonic ev)
                                           (ops/midicps (+ (p/pnext! dev-white) %)))
                                       (:midinote ev)))
        :atk (p/pwhite 15.0 45.0)
        :hold (p/pwhite 4.0 14.0)
        :rel (p/pwhite 30.0 60.0)
        :amp (fn [ev] (* 0.75 (/ 1 (:harmonic ev))))
        :lpf-freq (fn [ev] (mapv #(* (p/pnext! lp-white)
                                     (* (:harmonic ev) (ops/midicps %)))
                                 (:midinote ev)))
        :resonz-q (p/pwhite 0.001 1.0)
        :resonance (p/pwhite 0.6 0.05)
        :pan (p/prand [(p/pwhite -1.0 -0.5 1) (p/pwhite 0.5 1.0 1)])
        :type :note
        :out-bus 16))))

(defn player [seed]
  (p/stop)
  (p/start-clock-loop :tempo bpm)
  (p/pdef :alspat (create-phrase seed))
  (p/play :alspat))

(defn -main [& [seed-arg secs-arg fade-arg]]
  ;; Dense overlapping runs + per-voice Limiter lookahead buffers: raise
  ;; the RT pool and the node table like the earlier drone sketch needed.
  (let [s (server/make-external-server
             "example" :port 57138
             :options (opts/make-server-options {:realtime-mem-size 65536
                                                 :max-num-nodes 4096}))
        seed (if seed-arg (Long/parseLong seed-arg) 2160)
        secs (if secs-arg (Long/parseLong secs-arg) 45)
        fade (if fade-arg (Long/parseLong fade-arg) 5)]
    (server/server-boot s)
    (binding [server/*s* s]
      (p/start-clock-loop :tempo bpm)
      (p/enable-backend! (p/make-cl-collider-backend s))
      (define-voice!)
      (let [master (sdef/synth :mix-limiter :bus 16 :pos :tail)]
        (player seed)
        (Thread/sleep (* secs 1000))
        (p/end :alspat)
        (doseq [i (range 20 0 -1)]
          (server/ctrl master :amp (/ (dec i) 20.0))
          (Thread/sleep (long (/ (* fade 1000) 20))))
        (Thread/sleep 2000)))
    (server/server-quit s)
    (println :done)))
