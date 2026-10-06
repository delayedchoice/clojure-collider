;; Homage to /home/bobby/an_ending_ascent.mp3 (4:26 ambient drone).
;; Analysis: Eb/Ab key area, energy arc rising 30s->3:30 then fade.
;; Seven detuned saw voices enter over 0-150s under the track's own
;; measured RMS arc; everything exits by 244s. NRT render (~266s).
;; Run: clojure -M examples/ascent.clj
(require '[clojure-collider.server.server :as server]
         '[clojure-collider.synth.def :as def]
         '[clojure-collider.synth.operators :as ops]
         '[clojure-collider.ugens.osc :as osc]
         '[clojure-collider.ugens.filter :as flt]
         '[clojure-collider.ugens.env-gen :as e]
         '[clojure-collider.ugens.line :as ln]
         '[clojure-collider.ugens.trig :as trg]
         '[clojure-collider.ugens.in-out :as io])

(def dur 266.0)
(def exit-t 244.0)

;; Measured 10s-block RMS of the original, normalized (26 blocks + fade).
(def arc-levels
  [0.030 0.206 0.503 0.581 0.670 0.698 0.832 0.725 0.772 0.740
   0.717 0.743 0.789 0.843 0.802 0.768 0.839 0.853 0.859 0.974
   0.982 0.903 1.000 0.850 0.398 0.040 0.0])
(def arc-times (conj (vec (repeat 25 10.0)) 16.0))

;; [midi enter-sec amp]
(def voices
  [[39 0 0.30] [37 12 0.26] [44 25 0.22] [51 45 0.20]
   [45 70 0.16] [58 120 0.12] [63 150 0.10]])

(defn window
  "0->1 ramp over [enter,enter+attack], 1->0 over [exit-t,dur]."
  [t enter attack]
  (let [rin (ln/lin-lin:kr (trg/clip:kr t (/ enter dur) (/ (+ enter attack) dur))
                            (/ enter dur) (/ (+ enter attack) dur) 0 1)
        rout (ops/- 1 (ln/lin-lin:kr (trg/clip:kr t (/ exit-t dur) 1.0)
                                     (/ exit-t dur) 1.0 0 1))]
    (ops/* rin rout)))

(let [f (server/with-rendering ["/tmp/ascent-homage.aiff" :sr 44100 :pad dur]
          (def/play
            (let [t (ln/line:kr 0 1 dur)
                  arc (e/env-gen:kr (e/env arc-levels arc-times) :act :no-action)
                  cutoff (ops/+ 250 (ops/* 2000 arc))
                  sig (apply ops/+
                             (map-indexed
                              (fn [i [m enter amp]]
                                (let [fr (ops/midicps m)
                                      lfo (ops/+ 0.85 (ops/* 0.15
                                                             (osc/sin-osc:kr (+ 0.04 (* 0.01 i)))))]
                                  (ops/* amp lfo (window t enter 12.0)
                                         (flt/lpf:ar (ops/+ (osc/saw:ar fr)
                                                            (osc/saw:ar (ops/* fr 1.003)))
                                                     cutoff))))
                              voices))]
              (io/out:ar 0 [(ops/* 0.3 arc sig) (ops/* 0.3 arc sig)]))))]
  (println :rendered f))
