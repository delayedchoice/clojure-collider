;; Drone piece translated from an Overtone sketch (enosification.drone):
;; seeded chord-clouds through an FM/phase-mod voice with 15-45s attacks and
;; 30-60s releases, freed by detect-silence.
;; Run: clojure -M -m enosification-drone [seed=12345] [seconds=45]
;;   (boots its own scsynth)
;;
;; Translation notes (Overtone -> here):
;; - `pbind` with a MAP becomes pairs; infinite `repeatedly` seqs work as
;;   child streams directly (sequentials wrap to infinite pseq).
;; - `gen/*rnd*` binding becomes patterns.util `*rng*`; `uniform-double` is
;;   `rand-range`, `exprand` is `rand-exp`.
;; - Chord events (:midinote vectors) fan out to one voice per pitch in the
;;   cl-collider backend (Overtone/SC behavior).
;; - `calc-*` map over flattened notes (upstream maps the nested chords,
;;   which would throw); `:instrument` is a keyword, not the synth var.
(ns enosification-drone
  (:require [clojure.string :as str]
            [clojure-collider.server.server :as server]
            [clojure-collider.synth.sdef :as sdef]
            [clojure-collider.synth.operators :as ops]
            [clojure-collider.ugens.osc :as osc]
            [clojure-collider.ugens.env-gen :as e]
            [clojure-collider.ugens.in-out :as io]
            [clojure-collider.ugens.pan :as pan]
            [clojure-collider.ugens.trig :as trg]
            [clojure-collider.ugens.line :as ln]
            [clojure-collider.ugens.noise :as noise]
            [clojure-collider.ugens.compander :as lim]
            [patterns.util :as u]
            [patterns.patterns :as p]
            [patterns.pbind :as pb]
            [patterns.backend :as backend]
            [patterns.clock :as clock]))

(def bpm 120)

(sdef/defsynth :drone-synth
  [freq 440 atk 0.005 rel 0.3 amp 1 pan 0 gain 1
   mod-index 2 phase-mod-index 2 lpf-freq 18000
   resonance 0 hold 6.0 freq-deviation 0.975
   transition-time 0.875 rq 0.1 random-seed 4]
  (let [frq (trg/clip:kr freq 20 20000)
        _ (noise/rand-seed:ar random-seed)
        ;; upstream: (* (sin-osc (/ frq 2)) #_(...)) — second factor
        ;; reader-discarded, so md is just the sub sine.
        md (osc/sin-osc:ar (ops// frq 2))
        phase-mod (ops/* (osc/sin-osc:ar frq) phase-mod-index)
        sgn (nth [ops/+ ops/-] (u/rand-int-range 0 1))
        sig (osc/sin-osc:ar (ops/+ (ln/x-line:ar (sgn freq-deviation) (sgn freq) atk)
                                   md)
                            phase-mod)
        env (e/env-gen:kr (e/env [0 1 1 0] [atk hold rel] :sine) :act :free)
        sig (ops/* amp (pan/pan2:ar sig))
        ;; Upstream freed via detect-silence alone, but its 0.1s default
        ;; fires during the 15-45s fade-in (envelope still ~0), killing
        ;; every voice before it sounds. Envelope :free owns the lifetime
        ;; instead; silence detection would need attack-aware timing.
        sig (ops/* env sig)]
    (io/out:ar 0 (lim/limiter:ar sig 0.1))))

;; Sketch util fns, verbatim semantics. NOTE one upstream bug worked around:
;; they map over the NESTED chord list (where `(+ factor [44 ..])` would
;; throw); the `notes` binding they compute-but-ignore shows the intent, so
;; both fns map over the flattened notes. Per-voice split in the backend
;; then zips one deviation/lp value per pitch.
(defn calc-freq-deviation
  "harmonic * midicps(note - [0,0.25)) per note: each voice glides from its
   chord tone (detuned flat up to a quarter tone) scaled by harmonic."
  [harmonic midinotes]
  (let [factor (u/rand-range -0.25 0.0)]
    (mapv #(* (double harmonic) (ops/midicps (+ factor %)))
          (flatten midinotes))))

(defn calc-lp-freq
  "factor * harmonic * midicps(note) per note (factor in [3,9))."
  [harmonic midinotes]
  (let [factor (u/rand-range 3.0 9.0)]
    (mapv #(* factor (* (double harmonic) (ops/midicps %)))
          (flatten midinotes))))

(defn get-name-from-id
  "Segment after the last / in ID (nil-safe passthrough)."
  [id]
  (if (nil? id)
    id
    (let [index (str/index-of id "/")]
      (if (nil? index)
        id
        (subs id (inc index))))))

(def chords
  [[44 20 21 27]
   [32 20 39 40]
   [40 44 45 20 51]
   [44 56 20 51 52]
   [32 44 51 20 28]])

(defn- rnd-nth [coll]
  (nth coll (u/rand-int-range 0 (dec (count coll)))))
(defn create-phrase [seed]
  (binding [u/*rng* (java.util.Random. seed)]
    ;; Opening chord: the phrase-level :freq-deviation/:lpf-freq/:amp are
    ;; computed once from these (upstream starts empty, which yields empty
    ;; patterns downstream).
    (let [last-harmonic (atom 1)
          last-midinotes (atom [44 20 21 27])]
      (println (str "rnd: " (u/rand-range 0.0 1.0)))
      (pb/pbind
        :instrument :drone-synth
        :dur (repeatedly #(u/rand-range 0.1 10.0))
        :gain (repeatedly #(u/rand-exp 0.001 1.5))
        :transition-time (repeatedly #(rnd-nth [0.875 2.0]))
        :mod-index (repeatedly #(u/rand-range 0.00001 3.0))
        :phase-mod-index (repeatedly #(u/rand-range 0.00001 0.1))
        :midinote (repeatedly
                    (fn []
                      (let [n (* 2 (u/rand-int-range 2 7))
                            xs (vec (mapcat identity
                                            (repeatedly n #(rnd-nth chords))))]
                        (reset! last-midinotes xs))))
        :harmonic (repeatedly
                    #(reset! last-harmonic
                             (Math/round (double (u/rand-exp 1 9)))))
        ;; Phrase-level constants (derefed once, as upstream evaluates the
        ;; map values once — the per-event atom resets feed nothing live).
        :freq-deviation (calc-freq-deviation @last-harmonic @last-midinotes)
        :atk (repeatedly #(u/rand-range 15.0 45.0))
        :hold (repeatedly #(u/rand-range 4.0 14.0))
        :rel (repeatedly #(u/rand-range 30.0 60.0))
        :amp (* 7.5 (/ 1 @last-harmonic))
        :lpf-freq (calc-lp-freq @last-harmonic @last-midinotes)
        :rq (repeatedly #(u/rand-range 0.001 1.0))
        :resonance (repeatedly #(u/rand-range 0.05 0.6))
        :pan (repeatedly #(rnd-nth [(u/rand-range -1.0 -0.5)
                                    (u/rand-range 0.5 1.0)]))
        :type :note
        :random-seed seed))))

(defn player [seed]
  (clock/stop)
  (clock/start-clock-loop :tempo bpm)
  (pb/pdef :drone (create-phrase seed))
  (clock/play :drone))

(defn -main [& [seed-arg secs-arg]]
  (let [s (server/make-external-server "example" :port 57138)
        seed (if seed-arg (Long/parseLong seed-arg) 12345)
        secs (if secs-arg (Long/parseLong secs-arg) 45)]
    (server/server-boot s)
    (binding [server/*s* s]
      (clock/start-clock-loop :tempo bpm)
      (clock/enable-backend! (backend/make-cl-collider-backend s))
      (player seed)
      (Thread/sleep (* secs 1000))
      ;; end lets loops finish their cycle; tails die via detect-silence.
      (clock/end :drone)
      (Thread/sleep 5000))
    (server/server-quit s)
    (println :done)))
