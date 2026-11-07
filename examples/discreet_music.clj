;; Discreet Music (Brian Eno, 1975) homage: two slow synth loops phasing.
;; Run: clojure -M -m discreet-music [seconds=150]   (boots its own scsynth)
;;
;; After https://reverbmachine.com/blog/deconstructing-brian-enos-discreet-music/
;; (synth recipe, tape-loop system) with loop structure after Tero Parviainen's
;; "JavaScript Systems Music" reconstruction. Key is Ab major per the article
;; (right loop outlines the Ab triad + 6th; left adds the Db rub against C);
;; rhythms follow teropa's transcription. The half-speed lore is baked in:
;; loops run at final-record speed (68s left / 74s right) with the 5.65s
;; Frippertronics delay, so no resampling tricks are needed.
;;
;; Chain per side: Synthi-ish voice (sine + touch of saw, pitch-tracking
;; vibrato, dark LPF, slow attack, 160ms Echoplex-style comb) panned L/R,
;; both feeding one Frippertronics loop (5.65s DelayL, feedback ~0.72,
;; wet ~0.45) with light compression and room, out to hardware.
(ns discreet-music
  (:require [clojure-collider.server.server :as server]
            [clojure-collider.synth.sdef :as sdef]
            [clojure-collider.synth.operators :as ops]
            [ugens :as ug]
            [pattern :as p]))

;; Ab major material. Right loop (74s): triad Ab-C-Eb plus the 6th F.
;; Left loop (68s): C-Db semitone rub up front, calm Eb/Ab singles,
;; four-note C-Eb-F-Eb closing motif. Durs are inter-onset gaps in beats
;; (@120bpm); the last dur of each loop spans the closing silence.
(def right-notes [56 58 63 60 65]) ; Ab3 Bb3 Eb4 C4 F4
(def right-durs [4 22.5 2 45.5 54])
(def right-sus [4 22.5 2 45.5 8])

(def left-notes [72 73 63 68 72 75 77 75]) ; C5 Db5 Eb4 Ab4 C5 Eb5 F5 Eb5
(def left-durs [2 22 22 30 1.5 1.5 1.5 55.5])
(def left-sus [2 22 22 30 1.5 1.5 1.5 7])

(defn- define-voices! []
  (sdef/defsynth :discreet
    [Freq 440
     amp 0.22
     gate 1
     out 0
     pan 0.0]
    (let [rate (ops/+ 0.35 (ops// Freq 2400.0))
          vib (ug/sin-osc:kr rate 0 1.0)
          f (ops/* Freq (ops/+ 1.0 (ops/* 0.004 vib)))
          src (ops/+ (ops/* (ug/sin-osc:ar f 0 1.0) 0.6)
                     (ops/* (ug/saw:ar f 1.0 0.0) 0.22))
          dark (ug/lpf:ar src 900.0 1.0 0.0)
          env (ug/env-gen:kr (ug/env-asr 0.9 1.0 2.5) :gate gate :act :free)
          echo (ug/comb-n:ar (ops/* dark env) 0.5 0.16 1.5 1.0 0.0)
          sig (ops/+ (ops/* dark env 0.6) (ops/* echo 0.4))]
      (ug/out:ar out (ug/pan2:ar (ops/* sig amp) pan 1.0))))
   (sdef/defsynth :discreet-loop
     [inbus 16
      out 0
      wet 0.45
      fb 0.72
      gate 1]
     (let [src (ug/in:ar inbus 2)
           echo (ug/local-in:ar 2 0.0)
           d (ug/delay-l:ar (ops/+ src echo) 6.0 5.65 1.0 0.0)
           _ (ug/local-out:ar (ops/* d fb))
           mixed (ops/+ (ops/* src (ops/- 1.0 wet)) (ops/* d wet))
           glued (ug/compander:ar mixed mixed 0.5 1.0 0.5 0.01 0.1)
           room (ug/freeverb:ar glued :mix 0.22 :room 0.8 :damp 0.4)
           ;; Master 10s fade in/out over everything (voices + repeats).
           fade-in (ug/env-gen:kr (ug/env [0.0 1.0] [10.0]))
           fade-out (ug/env-gen:kr (ug/env-asr 0.01 1.0 10.0) :gate gate)
           faded (ops/* room fade-in fade-out)]
       (ug/out:ar out faded))))
 (defn -main [& [secs-arg]]
   (let [secs (if secs-arg (Long/parseLong secs-arg) 150)
         s (server/make-external-server "discreet" :port 57175)]
     (binding [server/*s* (p/connect-server! s :tempo 120)]
       (define-voices!)
       (let [loop (sdef/synth :discreet-loop :inbus 16)]
        (p/pb :discreet-left
          :instrument :discreet
          :midinote (p/pseq left-notes)
          :dur (p/pseq left-durs)
          :sustain (p/pseq left-sus)
          :pan -0.5 :out-bus 16 :loop true)
        (p/pb :discreet-right
          :instrument :discreet
          :midinote (p/pseq right-notes)
          :dur (p/pseq right-durs)
          :sustain (p/pseq right-sus)
          :pan 0.5 :out-bus 16 :loop true)
        (p/play :discreet-left)
        (p/play :discreet-right)
         ;; 10s master fade-out on the loop starts here; voices keep
         ;; playing into it so the whole mix fades as one. Short pieces
         ;; (<= 10s) skip the fade and end plain.
         (Thread/sleep (* 1000 (max 0 (- secs 10))))
         (when (> secs 10) (server/ctrl loop :gate 0))
         (Thread/sleep (if (> secs 10) 10000 (* 1000 secs)))
         (p/stop :discreet-left)
         (p/stop :discreet-right)
         (server/node-free loop)))
    (server/server-quit s)
    (println :done)))
