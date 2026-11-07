;; Ambient 1: Music for Airports (Brian Eno, 1978) homage: tracks 2/1
;; (sung loops) and 1/2 (piano loops), each loop its own period.
;; Run: clojure -M -m music-for-airports [2/1|1/2] [seconds=300]
;; Bounce: clojure -M -m music-for-airports bounce [2/1|1/2] [seconds] [out.aiff]
;;
;; After https://reverbmachine.com/blog/deconstructing-brian-eno-music-for-airports/
;; 2/1: seven sung loops (pitches pitch-detected from the article's own
;; Choir loops; loop times from Tero Parviainen's independent reconstruction
;; - the article publishes only two of the seven). The clock runs at 60bpm
;; so pattern beats ARE seconds.
;; 1/2: eight piano phrases (notes + onsets onset-detected from the
;; article's Piano loops; loop time = phrase length). D major with C
;; naturals throughout (Mixolydian colour, per the article).
;; 1/1 (slowed piano+Rhodes) is not included: no reliable note data.
(ns music-for-airports
  (:require [clojure-collider.server.server :as server]
            [clojure-collider.synth.sdef :as sdef]
            [clojure-collider.synth.operators :as ops]
            [ugens :as ug]
            [pattern :as p]))

;; 2/1 loops: [registry-name midi loop-secs]. Order = Choir-01..07.
(def airport-choir
  [[:ap2-01 68 19.7] ; Ab4
   [:ap2-02 65 17.8] ; F4
   [:ap2-03 60 21.3] ; C4
   [:ap2-04 63 18.5] ; Eb4
   [:ap2-05 61 20.0] ; Db4
   [:ap2-06 56 20.0] ; Ab3
   [:ap2-07 53 17.7]]); F3

;; 1/2 loops: [registry-name midis durs sustains]. Durs are inter-onset
;; gaps in seconds (loop time = phrase length); sustain 4 throughout.
(def airport-piano
  [[:ap1-01 [48 72] [11.66 5.44] [4 4]]
   [:ap1-02 [53 65] [0.51 20.29] [4 4]]
   [:ap1-03 [84 80 77 65] [3.84 0.92 1.09 17.35] [4 4 4 4]]
   [:ap1-04 [76 65] [9.71 18.29] [4 4]]
   [:ap1-05 [84 72 72] [13.12 3.11 13.27] [4 4 4]]
   [:ap1-06 [65 60] [8.7 21.3] [4 4]]
   [:ap1-07 [84 48] [5.7 25.4] [4 4]]
   [:ap1-08 [79 51 44] [6.76 2.82 28.42] [4 4 4]]])

(defn- define-voices! []
  (sdef/defsynth :air-choir
    [Freq 440
     amp 0.05
     gate 1
     out 0
     pan 0.0]
    (let [vib (ug/sin-osc:kr 0.4 0 1.0)
          f (ops/* Freq (ops/+ 1.0 (ops/* 0.003 vib)))
          src (ops/+ (ug/sin-osc:ar f 0 1.0)
                     (ops/* (ug/sin-osc:ar (ops/* f 2.0) 0 1.0) 0.3)
                     (ops/* (ug/sin-osc:ar (ops/* f 3.0) 0 1.0) 0.12))
          env (ug/env-gen:kr (ug/env-asr 1.5 1.0 3.0) :gate gate :act :free)]
      (ug/out:ar out (ug/pan2:ar (ops/* src env amp) pan 1.0))))
  (sdef/defsynth :air-keys
    [Freq 440
     amp 0.055
     gate 1
     out 0
     pan 0.0]
    (let [f Freq
          src (ops/+ (ug/sin-osc:ar f 0 1.0)
                     (ops/* (ug/sin-osc:ar (ops/* f 2.0) 0 1.0) 0.35)
                     (ops/* (ug/sin-osc:ar (ops/* f 3.0) 0 1.0) 0.15))
          dark (ug/lpf:ar src 2400.0 1.0 0.0)
          env (ug/env-gen:kr (ug/env-asr 0.01 1.0 2.5) :gate gate :act :free)]
      (ug/out:ar out (ug/pan2:ar (ops/* dark env amp) pan 1.0))))
  (sdef/defsynth :air-verb
    [inbus 16
     out 0
     gate 1]
    (let [src (ug/in:ar inbus 2)
          glued (ug/compander:ar src src 0.5 1.0 0.5 0.01 0.1)
          room (ug/freeverb:ar glued :mix 0.55 :room 0.9 :damp 0.35)
          fade-in (ug/env-gen:kr (ug/env [0.0 1.0] [15.0] :cubed))
          fade-out (ug/env-gen:kr (ug/env-asr 0.01 1.0 10.0) :gate gate)
          faded (ops/* room fade-in fade-out)]
      (ug/out:ar out faded))))

(defn- pan-for [midi lo hi]
  (-> midi (- lo) (/ (double (- hi lo))) (- 0.5) (* 0.6)))

(defn- define-choir! []
  (doseq [[nm midi secs] airport-choir]
    (p/pdef nm
      (apply p/pbind
        [:instrument :air-choir
         :midinote midi
         :dur secs
         :sustain 10
         :pan (pan-for midi 53 68)
         :out-bus 16
         :loop true]))))

(defn- define-piano! []
  (doseq [[nm midis durs suss] airport-piano]
    (p/pdef nm
      (apply p/pbind
        [:instrument :air-keys
         :midinote (p/pseq midis)
         :dur (p/pseq durs)
         :sustain (p/pseq suss)
         :pan (pan-for (apply max midis) 48 84)
         :out-bus 16
         :loop true]))))

(defn- loops-for [track]
  (case track
    "1/2" (mapv first airport-piano)
    (mapv first airport-choir)))

(defn- live-main [track secs]
  (let [s (server/make-external-server "airports" :port 57177)]
    (binding [server/*s* (p/connect-server! s :tempo 60)]
      (define-voices!)
      (if (= track "1/2") (define-piano!) (define-choir!))
      (let [verb (sdef/synth :air-verb :inbus 16)
            names (loops-for track)]
        (doseq [n names] (p/play n))
        (Thread/sleep (* 1000 (max 0 (- secs 10))))
        (when (> secs 10) (server/ctrl verb :gate 0))
        (Thread/sleep (if (> secs 10) 10000 (* 1000 secs)))
        (p/stop :all)
        (server/node-free verb)))
    (server/server-quit s)
    (println :done)))

(defn bounce [track secs out]
  (if (= track "1/2") (define-piano!) (define-choir!))
  (let [bps 1.0
        end-beat (double secs)
        dbg (p/make-debug-backend)
        evts (sort-by :beat
               (mapcat (fn [nm]
                         (take-while #(<= (:beat %) end-beat)
                           (p/play-take (p/pdef nm) 100000 dbg)))
                       (loops-for track)))]
    (server/with-rendering [out :sr 44100 :pad (+ (double secs) 12.0)]
      (define-voices!)
      (let [verb (sdef/synth :air-verb :inbus 16)
            be (p/make-cl-collider-backend server/*s*)]
        (doseq [{:keys [beat event]} evts]
          (p/play-event be (assoc event :tempo bps) (double beat)))
        (server/at (double (max 0 (- secs 10)))
          (server/ctrl verb :gate 0))
        (server/at (double secs)
          (server/node-free verb))))))

(defn -main [& args]
  (if (= "bounce" (first args))
    (let [[_ track-arg secs-arg out] args
          track (or track-arg "2/1")
          secs (if secs-arg (Long/parseLong secs-arg) 300)]
      (println :rendered (bounce track secs (or out "/tmp/airports.aiff"))))
    (let [[track-arg secs-arg] args]
      (live-main (or track-arg "2/1")
                 (if secs-arg (Long/parseLong secs-arg) 300)))))
