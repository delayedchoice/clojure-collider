;; "drone collider": SuperCollider port of the NI Reaktor Space Drone
;; instrument (minouminou/DroneCollider): 24 parallel voices spread
;; across the spectrum - noise into an enveloped bandpass, panned.
;; Run: clojure -M -m drone-collider [seconds=0]
;;   (boots its own scsynth; seconds 0 = play until Ctrl-C)
;;
;; Translation notes (SuperCollider -> here):
;; - Knob defaults are the "Polar Wind" preset; all knobs are synth
;;   controls, so they stay live-tweakable.
;; - Upstream bakes `voiceConst` (and the pitch math) from knob defaults
;;   at def time; here they are computed in-graph from the controls, so
;;   offset/damp/fundamental stay live. Same values at defaults.
;; - The SC `kDynamic` knob is dead upstream (defined, never referenced);
;;   kept as a (currently unused) control for documentation.
;; - SVF is SC3plugins-only server-side, so the example uses core BPF
;;   (rq = 1 - res, clamped): same bandpass role, narrower as resonance
;;   rises. The library still ships `svf:ar` for plugin users.
;; - Each voice seeds itself (RandID/RandSeed on `voiceid`), so the
;;   instrument is deterministic per boot without a global seed.
(ns drone-collider
  (:require [clojure-collider.server.server :as server]
            [clojure-collider.synth.sdef :as sdef]
            [clojure-collider.synth.operators :as ops]
            [ugens :as ug]
            [pattern :as p]))

(def voicesnum 24)
(def bpm 120)

(defn- p-to-f [pitch]
  (ops/expt 2 (ops/+ (ops// pitch 12) 3.03135)))

(defn- f-to-p [freq]
  (ops/* 12 (ops/- (ops/log2 freq) 3.03135)))

(defn define-voice!
  "One :drone-collider def. Module numbering follows the SC source."
  []
  (sdef/defsynth :drone-collider
    [out 0 voiceid 1
     attack 80 res 0.85 density -72 pan -96 damp 1.35 decay 80
     fundamental 36 offset 0 pitch 0 speed 2.5 amt 0.12 rnd 24
     rnd2 -72 dynamic 0.5 gain 0.98]
    (ug/rand-id:ir voiceid)
    (ug/rand-seed:ir 1 voiceid)
    ;; Module 1: this voice's center pitch.
    (let [sout1 (f-to-p (ops/* (ops/+ voiceid offset)
                               (p-to-f fundamental)))
          ;; Module 2: geiger retrigger (LocalIn feedback + LFPulse
          ;; into an ADSR squared).
          cntFreq (p-to-f (ops/+ (ug/local-in:ar 1)
                                 (ug/i-rand:ir (ops/neg rnd) rnd)
                                 density))
          geiger0 (ug/lf-pulse:ar cntFreq 0 0.5)
          _ (ug/local-out:ar geiger0)
          attackT (ops/+ (ops// (ops/expt 10 (ops// attack 20)) 1000)
                         0.001)
          decayT (ops/+ (ops// (ops/expt 10 (ops// decay 20)) 1000)
                        0.001)
          geiger* (ug/env-gen:ar (ug/env-adsr attackT decayT 0 decayT
                                             1.0 :lin)
                                 :gate geiger0)
          sout2 (ops/* geiger* geiger*)
          ;; Module 3: random walk + enveloped bandpass.
          ;; Core-UGen build: SVF needs the SC3plugins server plugin,
          ;; so this uses BPF (2nd-order bandpass). rq tracks the SVF
          ;; resonance inversely (narrower as res -> 1), clamped at 0.01
          ;; (Q 100). With SC3plugins installed, swap back to:
          ;; (ug/svf:ar (ug/white-noise:ar) filterP filterRes 0 1 0).
          slowRandom (ug/t-rand:kr (ops/neg amt) amt
                                   (ug/dust:kr speed))
          filterP (p-to-f (ops/+ sout1 (ops/* sout2 pitch)
                                 slowRandom))
          filterRes (ops/expt res 0.03125)
          sout3 (ug/bpf:ar (ug/white-noise:ar) filterP
                           (ops/max 0.01 (ops/- 1 filterRes)))
          ;; Module 4: harmonic level share (1/(i+offset)^damp
          ;; normalized over all voices) times gain^3.
          vconst (apply ops/+ (map (fn [i]
                                     (ops/expt (ops// 1 (ops/+ i offset))
                                               damp))
                                   (range 1 (inc voicesnum))))
          sout4 (ops/* (ops// (ops/expt (ops// 1 (ops/+ voiceid offset))
                                       damp)
                              vconst)
                       (ops/expt gain 3))
          ;; Module 5: voice signal.
          sout (ops/* sout3 sout2 sout4)
          ;; Module 6: wandering stereo placement.
          slowRandom2 (ug/t-rand:kr (ops/neg (p-to-f rnd2))
                                    (p-to-f rnd2)
                                    (ug/dust:kr (p-to-f rnd2)))
          panLFO (ug/lf-tri:ar (ops/+ (p-to-f pan) slowRandom2))
          stereo (ug/pan2:ar sout panLFO)]
      (ug/out:ar out stereo))))

(defn -main [& [secs-arg]]
  (let [secs (if secs-arg (Long/parseLong secs-arg) 0)
        s (p/setup-performance! :port 57140 :tempo bpm :name "example")]
    (binding [server/*s* s]
      (define-voice!)
      (doseq [voiceid (range 1 (inc voicesnum))]
        (sdef/synth :drone-collider :voiceid voiceid))
      (if (pos? secs)
        (do (Thread/sleep (* secs 1000))
            (server/server-quit server/*s*))
        (do (println :playing-forever-ctrl-c-to-stop)
            (.addShutdownHook (Runtime/getRuntime)
              (Thread. ^Runnable
                (fn [] (server/server-quit server/*s*))))
            (while true
              (Thread/sleep 60000)))))
    (println :done)))
