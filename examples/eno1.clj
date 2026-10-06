;; "An Ending (Ascent)" (Eno) bars 1-26 + repeat 22-26, from the
;; Annemie Van Riel piano transcription (Finale PDF in this directory).
;; 26 bars x 4 beats @ beat=2.5s (q=24) = 260s of music from 4s.
;; Chord changes every ~5.5s in the recording match the half-bar grid.
;; The scores 22-26 repeat (~50s) is not observed in the recording: omitted.
;; Run: clojure -M examples/eno1.clj (NRT render)
(require '[clojure-collider.server.server :as server]
         '[clojure-collider.synth.sdef :as sdef]
         '[clojure-collider.synth.operators :as ops]
         '[clojure-collider.ugens.osc :as osc]
         '[clojure-collider.ugens.env-gen :as e]
         '[clojure-collider.ugens.free-verb :as fv]
         '[clojure-collider.ugens.filter :as flt]
         '[clojure-collider.ugens.line :as ln]
         '[clojure-collider.ugens.trig :as trg]
         '[clojure-collider.ugens.compander :as lim]
         '[clojure-collider.bus :as bus]
         '[clojure-collider.ugens.in-out :as io])

(def beat 2.5)
(def bar (* 4 beat))

;; [bar-index beat-offset [midis] amp] — bar-index 0-based over the PLAYED
;; sequence (bars 1-21, 22-26, 22-26)
(def bars
  [;; 1: F6 -> Bb7
   [[0 [41 50 65 74] 0.55] [2 [50 56 70 77] 0.55]]
   ;; 2: echo Bb7
   [[2 [50 56 70 77] 0.5]]
   ;; 3: Cm colors -> Fm7-ish
   [[0 [41 51 63 67 72] 0.55] [2 [48 55 68 74] 0.5]]
   ;; 4: Eb6
   [[2 [51 60 70 75] 0.5]]
   ;; 5: Cm -> Abmaj7
   [[0 [48 51 55 63 67 70] 0.55] [2 [48 55 68 72] 0.5]]
   ;; 6: echo Bb7
   [[2 [50 56 70 77] 0.5]]
   ;; 7: F/Eb-G-D, C-G + Bb,D quarters
   [[0 [41 51 67 74] 0.55] [2 [48 55 70] 0.5] [3 [74] 0.5]]
   ;; 8: G+Ab cluster, Bb triad stab beat 4
   [[0 [67 68] 0.45] [3 [46 50 53] 0.55]]
   ;; 9: C-G, Ab-Eb
   [[0 [48 55] 0.5] [2 [44 51] 0.5]]
   ;; 10: Eb-Bb-F, D quarter; Fm bass
   [[0 [63 70 77] 0.5] [2 [74] 0.5] [0 [44 48 53] 0.55]]
   ;; 11: Eb triad; D-F
   [[0 [63 67 70] 0.5] [2 [50 53] 0.45]]
   ;; 12: Bb-F; low C-Eb (flag: C2)
   [[0 [36 51] 0.55] [2 [70 77] 0.5]]
   ;; 13: Eb triad; C melody? (flag); D-F
   [[0 [63 67 70] 0.5] [2 [72] 0.5] [2 [50 53] 0.45]]
   ;; 14: G-C, Bb-F; low C-F (flag); C-Eb-G
   [[0 [36 53 67 72] 0.55] [2 [48 51 55 70 77] 0.55]]
   ;; 15: Eb triad; D-F
   [[2 [63 67 70] 0.5] [2 [50 53] 0.45]]
   ;; 16: G-C, Ab-C; F-Eb? (flag), Bb-F
   [[0 [41 51 67 72] 0.55] [2 [46 53 68 72] 0.5]]
   ;; 17: Bb-F; D-C4
   [[2 [70 77] 0.5] [2 [50 60] 0.5]]
   ;; 18: G-C, Ab-C; Eb-C; Ab-Eb
   [[0 [39 48 67 72] 0.55] [2 [44 51 68 72] 0.5]]
   ;; 19: Bb-F; D-Ab
   [[2 [50 56 70 77] 0.5]]
   ;; 20: G-C; D-F + Eb-G quarters; Eb-C; F-D
   [[0 [39 48 67 72] 0.55] [2 [74 77] 0.5] [3 [75 79] 0.5]
    [2 [41 50] 0.55]]
   ;; 21: G-C, Bb-F; D-Ab
   [[0 [67 72] 0.5] [2 [50 56 70 77] 0.55]]
   ;; 22: Eb-G-C, G-C?
   [[0 [39 48 63 67 72] 0.55] [2 [67 72] 0.5]]
   ;; 23: Bb-F; D-Ab
   [[2 [50 56 70 77] 0.5]]
   ;; 24: G-C; Eb triad
   [[0 [39 48 67 72] 0.55] [2 [44 51 63 67 70] 0.55]]
   ;; 25: Bb-F; D-Ab
   [[2 [50 56 70 77] 0.5]]
   ;; 26: G-C; Ab-Eb
   [[0 [39 48 67 72] 0.55] [2 [44 51 70 77] 0.55]]])

(def play-seq bars)
(def start-at 4.0)
(def total-dur (+ start-at (* 26 bar) 15.0))

(let [f (server/with-rendering ["/tmp/eno1.aiff" :sr 44100 :pad total-dur]
          ;; everything mixes here; the limiter guarantees reproducible
          ;; headroom no matter how the transcription grows
          ;; first private bus (0-15 are hardware: 8 out + 8 in); nothing
          ;; else allocates, so a plain constant beats a pinned allocator call
          (def mixbus 16)
          (sdef/defsynth :felt-piano [note 60 amp 0.5 out 0]
            (let [fr (ops/midicps note)
                  env (e/env-gen:kr (e/env [0 1 0] [0.4 18.0]) :act :free)
                  sig (ops/+ (osc/sin-osc:ar fr 0 amp)
                             (osc/sin-osc:ar (ops/* fr 2.0) 0 (ops/* amp 0.22))
                             (osc/sin-osc:ar (ops/* fr 3.0) 0 (ops/* amp 0.06)))]
              (io/out:ar out [(fv/freeverb:ar (ops/* env sig)
                                              :mix 0.35 :room 0.85 :damp 0.3)
                              (fv/freeverb:ar (ops/* env sig)
                                              :mix 0.35 :room 0.85 :damp 0.3)])))
          (sdef/defsynth :mix-limiter [bus 16]
            (let [sig (io/in:ar bus 2)]
              ;; 0.7 headroom: worst-case 6-voice stack measured ~1.0; limiter is backstop
            (let [sig (ops/* sig 0.7)]
              (io/out:ar 0 (lim/compander:ar sig sig 0.7 1.0 0.05 0.001 0.1)))))
          ;; sustained pad wash under the piano (the recording's bed)
          (sdef/play
            (let [t (ln/line:kr 0 1 total-dur)
                  g (ln/lin-lin:kr (trg/clip:kr t 0.0 0.3) 0.0 0.3 0 1)
                  voice (fn [m]
                          (flt/lpf:ar (ops/+ (osc/saw:ar (ops/midicps m))
                                             (osc/saw:ar (ops/* (ops/midicps m) 1.004)))
                                      600))]
              (io/out:ar mixbus [(ops/* 0.035 g (apply ops/+ (map voice [39 44 46 51])))
                                 (ops/* 0.035 g (apply ops/+ (map voice [39 44 46 51])))]))
            :out-bus mixbus)
          ;; equal-loudness chords: each event's notes share its amp budget
          (doseq [[bi events] (map-indexed vector play-seq)
                  [b midis amp] events
                  m midis]
            (when (pos? amp)
              (server/at (+ start-at (* bi bar) (* b beat))
                         (sdef/synth :felt-piano :note m
                                    :amp (ops// amp (count midis))
                                    :out mixbus))))
          ;; limiter last so it hears the full mix
          (sdef/synth :mix-limiter :bus mixbus :pos :tail))]
  (println :rendered f))
