;; Homage v2 to /home/bobby/an_ending_ascent.mp3.
;; Analysis showed the original's harmony is static (Eb throughout) and its
;; ascent is purely dynamic — so v2 takes creative license: a slow
;; Eb -> Ab -> Db -> Eb chord walk from the piece's own palette, swells with
;; real contrast (arc^1.5), and sparse pentatonic plucks in the second half.
;; Run: clojure -M examples/ascent2.clj (NRT render, ~266s)
(require '[clojure-collider.server.server :as server]
         '[clojure-collider.synth.sdef :as sdef]
         '[clojure-collider.synth.operators :as ops]
         '[clojure-collider.ugens.osc :as osc]
         '[clojure-collider.ugens.filter :as flt]
         '[clojure-collider.ugens.env-gen :as e]
         '[clojure-collider.ugens.line :as ln]
         '[clojure-collider.ugens.trig :as trg]
         '[clojure-collider.ugens.in-out :as io])

(def dur 266.0)
(def exit-t 244.0)

(def arc-levels
  [0.030 0.206 0.503 0.581 0.670 0.698 0.832 0.725 0.772 0.740
   0.717 0.743 0.789 0.843 0.802 0.768 0.839 0.853 0.859 0.974
   0.982 0.903 1.000 0.850 0.398 0.040 0.0])
(def arc-times (conj (vec (repeat 25 10.0)) 16.0))

;; [midi enter exit amp] — chord walk with crossfaded changes
(def voices
  [;; Eb (0-70)
   [39 0 70 0.30] [43 5 70 0.20] [46 10 70 0.20] [51 15 130 0.18]
   ;; Ab (60-130)
   [44 60 130 0.24] [48 65 130 0.18] [56 75 130 0.12]
   ;; Db (120-190)
   [37 120 190 0.26] [41 125 190 0.18] [44 120 190 0.16] [49 130 190 0.12]
   ;; Eb home (180-244) + shimmer
   [39 180 exit-t 0.28] [43 185 exit-t 0.18] [46 190 exit-t 0.18]
   [51 130 exit-t 0.16] [63 190 exit-t 0.08]
   ;; swells (thirds, octave up)
   [55 64 95 0.10] [53 134 165 0.10] [55 194 225 0.12]])

;; sparse plucks across the second half (Eb major pentatonic)
(def arp
  [[63 122] [65 133] [67 144] [70 155] [72 166] [70 177]
   [67 188] [65 199] [75 210] [72 221] [70 232] [67 241]])

(defn window
  [t enter exit attack release]
  (let [rin (ln/lin-lin:kr (trg/clip:kr t (/ enter dur) (/ (+ enter attack) dur))
                            (/ enter dur) (/ (+ enter attack) dur) 0 1)
        rout (ops/- 1 (ln/lin-lin:kr (trg/clip:kr t (/ (- exit release) dur) (/ exit dur))
                                     (/ (- exit release) dur) (/ exit dur) 0 1))]
    (ops/* rin rout)))

(let [f (server/with-rendering ["/tmp/ascent2.aiff" :sr 44100 :pad dur]
          ;; pluck voice for the arp
          (sdef/defsynth :pluck [note 63]
            (let [env (e/env-gen:kr (e/env [0 1 0] [0.01 2.5]) :act :free)
                  sig (ops/+ (osc/sin-osc:ar (ops/midicps note) 0 0.12)
                             (osc/sin-osc:ar (ops/* (ops/midicps note) 2.0) 0 0.04))]
              (io/out:ar 0 [sig sig])))
          (sdef/play
            (let [t (ln/line:kr 0 1 dur)
                  arc (e/env-gen:kr (e/env arc-levels arc-times) :act :no-action)
                  dyn (ops/+ 0.25 (ops/* 0.75 arc))
                  cutoff (ops/+ 250 (ops/* 2400 dyn))
                  sig (apply ops/+
                             (map-indexed
                              (fn [i [m enter exit amp]]
                                (let [fr (ops/midicps m)
                                      lfo (ops/+ 0.85 (ops/* 0.15
                                                             (osc/sin-osc:kr (+ 0.04 (* 0.01 i)))))]
                                  (ops/* amp lfo (window t enter exit 18.0 16.0)
                                         (flt/lpf:ar (ops/+ (osc/saw:ar fr)
                                                            (osc/saw:ar (ops/* fr 1.003)))
                                                     cutoff))))
                              voices))]
              (io/out:ar 0 [(ops/* 0.26 dyn sig) (ops/* 0.26 dyn sig)])))
          ;; schedule the arp (messages timestamped into the score)
          (doseq [[m at] arp]
            (server/at at (sdef/synth :pluck :note m))))]
  (println :rendered f))
