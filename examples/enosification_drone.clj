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
            [clojure-collider.server.options :as opts]
            [clojure-collider.synth.sdef :as sdef]
            [clojure-collider.synth.operators :as ops]
            [ugens :as ug]
            ;; patterns.util kept alongside the facade for one thing only:
            ;; binding *rng* (dynamics are not re-exported by design).
            [patterns.util :as u]
            [pattern :as p]))

(def bpm 120)


;; Sketch util fns, verbatim semantics. NOTE one upstream bug worked around:
;; they map over the NESTED chord list (where `(+ factor [44 ..])` would
;; throw); the `notes` binding they compute-but-ignore shows the intent, so
;; both fns map over the flattened notes. Per-voice split in the backend
;; then zips one deviation/lp value per pitch.
(defn calc-freq-deviation
  "harmonic * midicps(note - [0,0.25)) per note: each voice glides from its
   chord tone (detuned flat up to a quarter tone) scaled by harmonic."
  [harmonic midinotes]
  (let [factor (p/rand-range -0.25 0.0)]
    (mapv #(* (double harmonic) (ops/midicps (+ factor %)))
          (flatten midinotes))))

(defn calc-lp-freq
  "factor * harmonic * midicps(note) per note (factor in [3,9))."
  [harmonic midinotes]
  (let [factor (p/rand-range 3.0 9.0)]
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
  (nth coll (p/rand-int-range 0 (dec (count coll)))))
(defn create-phrase [seed]
  (binding [u/*rng* (java.util.Random. seed)]
    ;; Opening chord: the phrase-level :freq-deviation/:lpf-freq/:amp are
    ;; computed once from these (upstream starts empty, which yields empty
    ;; patterns downstream).
    (let [last-harmonic (atom 1)
          last-midinotes (atom [44 20 21 27])]
      (println (str "rnd: " (p/rand-range 0.0 1.0)))
      (p/pbind
        :instrument :drone-synth
        :dur (repeatedly #(p/rand-range 0.1 10.0))
        :gain (repeatedly #(p/rand-exp 0.001 1.5))
        :transition-time (repeatedly #(rnd-nth [0.875 2.0]))
        :mod-index (repeatedly #(p/rand-range 0.00001 3.0))
        :phase-mod-index (repeatedly #(p/rand-range 0.00001 0.1))
        :midinote (repeatedly
                    (fn []
                      (let [n (* 2 (p/rand-int-range 2 7))
                            xs (vec (mapcat identity
                                            (repeatedly n #(rnd-nth chords))))]
                        (reset! last-midinotes xs))))
        :harmonic (repeatedly
                    #(reset! last-harmonic
                             (Math/round (double (p/rand-exp 1 9)))))
        ;; Phrase-level constants (derefed once, as upstream evaluates the
        ;; map values once — the per-event atom resets feed nothing live).
        :freq-deviation (calc-freq-deviation @last-harmonic @last-midinotes)
        :atk (repeatedly #(p/rand-range 15.0 45.0))
        :hold (repeatedly #(p/rand-range 4.0 14.0))
        :rel (repeatedly #(p/rand-range 30.0 60.0))
        ;; Upstream fixes :amp per voice, which clips with 47-voice clouds
        ;; (7.5 x 47 at any envelope height). Read the 7.5 as the whole
        ;; cloud's budget instead: each event splits it across its pitches.
        ;; :midinote is pulled before :amp, so the atom holds this event.
        :amp (repeatedly #(* 7.5 (/ 1 @last-harmonic)
                             (/ 1 (max 1 (count @last-midinotes)))))
        :lpf-freq (calc-lp-freq @last-harmonic @last-midinotes)
        :rq (repeatedly #(p/rand-range 0.001 1.0))
        :resonance (repeatedly #(p/rand-range 0.05 0.6))
        :pan (repeatedly #(rnd-nth [(p/rand-range -1.0 -0.5)
                                    (p/rand-range 0.5 1.0)]))
        :type :note
        :out-bus 16
        :random-seed seed))))

(defn player [seed]
  (p/stop)
  (p/start-clock-loop :tempo bpm)
  (p/pdef :drone (create-phrase seed))
  (p/play :drone))

(defn define-voice!
  "Define :drone-synth on the current *s* (server or NRT score). Must run
  after boot / inside with-rendering: definitions only reach the synthesis
  engine when a server is listening."
  []
(sdef/defsynth :drone-synth
  [freq 440 atk 0.005 rel 0.3 amp 1 pan 0 gain 1
   mod-index 2 phase-mod-index 2 lpf-freq 18000
   resonance 0 hold 6.0 freq-deviation 0.975
   transition-time 0.875 rq 0.1 random-seed 4 out 0]
  (let [frq (ug/clip:kr freq 20 20000)
        _ (ug/rand-seed:ar random-seed)
        ;; upstream: (* (sin-osc (/ frq 2)) #_(...)) — second factor
        ;; reader-discarded, so md is just the sub sine.
        md (ug/sin-osc:ar (ops// frq 2))
        phase-mod (ops/* (ug/sin-osc:ar frq) phase-mod-index)
        sgn (nth [ops/+ ops/-] (p/rand-int-range 0 1))
        sig (ug/sin-osc:ar (ops/+ (ug/x-line:ar (sgn freq-deviation) (sgn freq) atk)
                                   md)
                            phase-mod)
        env (ug/env-gen:kr (ug/env [0 1 1 0] [atk hold rel] :sine) :act :free)
        sig (ops/* amp (ug/pan2:ar sig))
        ;; Upstream freed via detect-silence alone, but its 0.1s default
        ;; fires during the 15-45s fade-in (envelope still ~0), killing
        ;; every voice before it sounds. Envelope :free owns the lifetime
        ;; instead; silence detection would need attack-aware timing.
        sig (ops/* env sig)]
    (ug/out:ar out (ug/limiter:ar sig 0.1))))
  ;; Master stage: per-voice limiters cap individuals, but 47-voice clouds
  ;; still sum hot. Fixed 0.7 trim + Compander backstop, same as eno-ending.
  (sdef/defsynth :mix-limiter [bus 16]
    (let [sig (ug/in:ar bus 2)
          sig (ops/* sig 0.5)]
      (ug/out:ar 0 (ug/compander:ar sig sig 0.7 1.0 0.05 0.001 0.1)))))
(defn -main [& [seed-arg secs-arg]]
  ;; 47-voice clouds each carry a Limiter (lookahead buffers); the default
  ;; 8MB RT pool exhausts, so raise it (scsynth itself suggests -m).
  (let [s (server/make-external-server
             "example" :port 57138
             :options (opts/make-server-options {:realtime-mem-size 65536}))
        seed (if seed-arg (Long/parseLong seed-arg) 12345)
        secs (if secs-arg (Long/parseLong secs-arg) 45)]
    (server/server-boot s)
    (binding [server/*s* s]
      (p/start-clock-loop :tempo bpm)
      (p/enable-backend! (p/make-cl-collider-backend s))
      (define-voice!)
      ;; Private mix bus (0-15 hardware); master last so it hears the mix.
      (sdef/synth :mix-limiter :bus 16 :pos :tail)
      (player seed)
      (Thread/sleep (* secs 1000))
      ;; end lets loops finish their cycle; tails die on envelope end.
      (p/end :drone)
      (Thread/sleep 5000))
    (server/server-quit s)
    (println :done)))
