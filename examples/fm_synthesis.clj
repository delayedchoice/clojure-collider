;; Eli Fieldsteel FM Synthesis 1 (SuperCollider tutorial), translated from
;; ntrocado/cl-collider-examples 21.fm-synthesis-1.lisp
;; (https://www.youtube.com/watch?v=UoXMUQIqFk4).
;; Run: clojure -M examples/fm_synthesis.clj   (boots its own scsynth)
;;
;; Two halves: (1) a mouse-played FM drone — move the mouse over the
;; SuperCollider window... on headless machines MouseX/Y sit at fixed values,
;; so it still renders, just statically. (2) a percussive FM voice with
;; randomized params. The original's second half uses cl-patterns
;; (pb/pexprand/pwhite/start-clock-loop), a separate upstream library this
;; port does not include — the doseq + server/at loop below is the direct
;; equivalent (32 eighth-notes, fresh random params per note).
(require '[clojure-collider.server.server :as server]
         '[clojure-collider.synth.sdef :as sdef]
         '[clojure-collider.synth.operators :as ops]
         '[clojure-collider.ugens.osc :as osc]
         '[clojure-collider.ugens.env-gen :as e]
         '[clojure-collider.ugens.in-out :as io]
         '[clojure-collider.ugens.mac-ugens :as mouse]
         '[clojure-collider.ugens.noise :as noise]
         '[clojure-collider.ugens.poll :as poll]
         '[clojure-collider.ugens.pan :as pan])
;; pexprand = exponential-uniform draw over [lo hi]: uniform in log space.
(defn- exprand [lo hi] (* lo (Math/pow (/ hi lo) (rand))))

(let [s (server/make-external-server "example" :port 57134)]
  (server/server-boot s)
  (binding [server/*s* s]
    ;; Carrier freq from mouse Y (exp 200-5000), modulator freq from mouse X
    ;; (exp 1-2000), mod depth wandered by stepped noise (20-10000, 8 steps/s).
    ;; poll.kr prints the mouse values server-side 10x/sec. dup = mono to
    ;; stereo pair.
    (let [drone (sdef/play
                  (ops/dup (ops/* (osc/sin-osc:ar
                                    (ops/+ (poll/poll:kr 10 (mouse/mouse-y:kr 200 5000 :exp))
                                           (osc/sin-osc:ar
                                             (poll/poll:kr 10 (mouse/mouse-x:kr 1 2000 :exp))
                                             0 (ops/range (noise/lf-noise0:kr 8) 20 10000)))
                                    0 0.2))))]
      (Thread/sleep 6000)
      (server/node-free s (:id drone)))
    ;; Percussive FM voice: carrier + one modulator, perc envelope frees the
    ;; synth (like upstream :act :free), panned stereo out.
    (sdef/defsynth :fm [car-hz 500 mod-hz 100 mod-amp 200 atk 0.01 rel 1 amp 0.2 pan 0]
      (io/out:ar 0 (pan/pan2:ar (ops/* (e/env-gen:kr (e/env-perc atk rel) :act :free)
                                       (osc/sin-osc:ar (ops/+ car-hz
                                                              (osc/sin-osc:ar mod-hz 0 mod-amp)))
                                       amp)
                                pan)))
    (let [t0 (server/now)]
      (doseq [i (range 32)]
        (server/at (+ t0 (* i 0.25))
                   (sdef/synth :fm
                               :car-hz (exprand 20 10000)
                               :mod-hz (exprand 20 10000)
                               :mod-amp (rand 10000)
                               :amp (exprand 0.1 0.5)
                               :atk (exprand 0.001 0.05)
                               :rel (exprand 0.05 1.2)
                               :pan (- (rand 2.0) 1.0)))))
    (Thread/sleep 12000))
  (server/server-quit s)
  (println :done))
