;; Thursday Afternoon (Brian Eno, 1985) homage: ~16 incommensurable loops
;; over a G-triard drone. Run: clojure -M -m thursday-afternoon [seconds=300]
;; Bounce: clojure -M -m thursday-afternoon bounce [seconds] [out.aiff]
;;
;; After https://reverbmachine.com/blog/deconstructing-brian-eno-thursday-afternoon/
;; Harmonically everything belongs to a G dominant 7th (G B D F) with an
;; added C; the piano and DX7-sine loops play the same pool of notes, and
;; the drone holds a G triad. Loop notes were pitch-detected from the
;; article's own sample pack (sine1-8, piano2-8, thursday-drone-1-3); loop
;; times were not published per-loop, so each runs 16-19s (the article's
;; range) with +-0.6s jitter per repeat,stand-ins documented as such.
;; The clock runs at 60bpm so pattern beats ARE seconds.
(ns thursday-afternoon
  (:require [clojure-collider.server.server :as server]
            [clojure-collider.synth.sdef :as sdef]
            [clojure-collider.synth.operators :as ops]
            [ugens :as ug]
            [pattern :as p]))

;; [registry-name instrument midi loop-center-secs]. Sine centers and piano
;; centers interleave across 16.0-19.3 so no two loops share a period.
(def loops
  [[:thu-sine-1 :thursday-sine 55 16.0] ; G3
   [:thu-piano-2 :thursday-keys 59 16.7] ; B3
   [:thu-sine-2 :thursday-sine 59 17.4] ; B3
   [:thu-piano-3 :thursday-keys 65 18.1] ; F4
   [:thu-sine-3 :thursday-sine 62 18.8] ; D4
   [:thu-piano-4 :thursday-keys 62 16.2] ; D4
   [:thu-sine-4 :thursday-sine 65 16.9] ; F4
   [:thu-piano-5 :thursday-keys 67 17.6] ; G4
   [:thu-sine-5 :thursday-sine 67 18.3] ; G4
   [:thu-piano-6 :thursday-keys 71 19.0] ; B4
   [:thu-sine-6 :thursday-sine 71 16.4] ; B4
   [:thu-piano-7 :thursday-keys 72 17.1] ; C5
   [:thu-sine-7 :thursday-sine 72 17.8] ; C5
   [:thu-piano-8 :thursday-keys 74 18.5] ; D5
   [:thu-sine-8 :thursday-sine 74 19.2]]); D5

(def drone-notes [43 47 50]) ; G2 B2 D3

(defn- define-voices! []
  (sdef/defsynth :thursday-keys
    [Freq 440
     amp 0.05
     gate 1
     out 0
     pan 0.0]
    (let [f Freq
          src (ops/+ (ug/sin-osc:ar f 0 1.0)
                     (ops/* (ug/sin-osc:ar (ops/* f 2.0) 0 1.0) 0.35)
                     (ops/* (ug/sin-osc:ar (ops/* f 3.0) 0 1.0) 0.15))
          dark (ug/lpf:ar src 2600.0 1.0 0.0)
          env (ug/env-gen:kr (ug/env-asr 0.01 1.0 2.0) :gate gate :act :free)]
      (ug/out:ar out (ug/pan2:ar (ops/* dark env amp) pan 1.0))))
  (sdef/defsynth :thursday-sine
    [Freq 440
     amp 0.045
     gate 1
     out 0
     pan 0.0]
    ;; DX7 single sine: 2.11s decay to -18dB-ish, then a short tail.
    (let [env (ug/env-gen:kr (ug/env [0.0 1.0 0.13 0.0] [0.01 2.11 3.0])
                             :act :free)
          sig (ug/sin-osc:ar Freq 0 1.0)]
      (ug/out:ar out (ug/pan2:ar (ops/* sig env amp) pan 1.0))))
  (sdef/defsynth :thursday-drone
    [Freq 110
     amp 0.025
     gate 1
     out 0
     pan 0.0]
    (let [wob (ug/sin-osc:kr 0.07 0 1.0)
          trem (ops/+ 1.0 (ops/* 0.2 wob))
          src (ops/+ (ug/sin-osc:ar Freq 0 1.0)
                     (ug/sin-osc:ar (ops/* Freq 1.002) 0 1.0)
                     (ops/* (ug/sin-osc:ar (ops/* Freq 2.003) 0 1.0) 0.4))
          dark (ug/lpf:ar src 380.0 1.0 0.0)
          env (ug/env-gen:kr (ug/env-asr 2.0 1.0 8.0) :gate gate :act :free)]
      (ug/out:ar out (ug/pan2:ar (ops/* dark env trem amp) pan 1.0))))
  (sdef/defsynth :thursday-verb
    [inbus 16
     out 0
     gate 1]
    (let [src (ug/in:ar inbus 2)
          glued (ug/compander:ar src src 0.5 1.0 0.5 0.01 0.1)
          room (ug/freeverb:ar glued :mix 0.5 :room 0.9 :damp 0.3)
          fade-in (ug/env-gen:kr (ug/env [0.0 1.0] [15.0] :cubed))
          fade-out (ug/env-gen:kr (ug/env-asr 0.01 1.0 10.0) :gate gate)
          faded (ops/* room fade-in fade-out)]
      (ug/out:ar out faded))))

(defn- pan-for [midi] (-> midi (- 55) (/ 19.0) (- 0.5) (* 0.8)))

(defn- define-sequences! []
  (doseq [[nm inst midi center] loops]
    (p/pdef nm
      (apply p/pbind
        [:instrument inst
         :midinote midi
         :dur (p/pwhite (- center 0.6) (+ center 0.6))
         :sustain 8
         :pan (pan-for midi)
         :out-bus 16
         :loop true]))))

(defn- start-drones! []
  (mapv (fn [m pan] (sdef/synth :thursday-drone :freq (p/midinote-freq m)
                                :pan pan))
        drone-notes [-0.2 0.0 0.2]))

(defn- play-all! []
  (doseq [[nm _ _ _] loops] (p/play nm)))

(defn- stop-all! []
  (p/stop :all))

(defn- live-main [secs]
  (let [s (server/make-external-server "thursday" :port 57176)]
    (binding [server/*s* (p/connect-server! s :tempo 60)]
      (define-voices!)
      (define-sequences!)
      (let [verb (sdef/synth :thursday-verb :inbus 16)
            drones (start-drones!)]
        (play-all!)
        (Thread/sleep (* 1000 (max 0 (- secs 10))))
        (when (> secs 10) (server/ctrl verb :gate 0))
        (Thread/sleep (if (> secs 10) 10000 (* 1000 secs)))
        (stop-all!)
        (server/node-free verb)
        (doseq [d drones] (server/node-free d))))
    (server/server-quit s)
    (println :done)))

(defn bounce [secs out]
  (define-sequences!)
  (let [bps 1.0
        end-beat (double secs)
        dbg (p/make-debug-backend)
        evts (sort-by :beat
               (mapcat (fn [[nm _ _ _]]
                         (take-while #(<= (:beat %) end-beat)
                           (p/play-take (p/pdef nm) 100000 dbg)))
                       loops))]
    (server/with-rendering [out :sr 44100 :pad (+ (double secs) 12.0)]
      (define-voices!)
      (let [verb (sdef/synth :thursday-verb :inbus 16)
            drones (start-drones!)
            be (p/make-cl-collider-backend server/*s*)]
        (doseq [{:keys [beat event]} evts]
          (p/play-event be (assoc event :tempo bps) (double beat)))
        (server/at (double (max 0 (- secs 10)))
          (server/ctrl verb :gate 0))
        (server/at (double secs)
          (server/node-free verb)
          (doseq [d drones] (server/node-free d)))))))

(defn -main [& args]
  (if (= "bounce" (first args))
    (let [[_ secs-arg out] args
          secs (if secs-arg (Long/parseLong secs-arg) 300)]
      (println :rendered (bounce secs (or out "/tmp/thursday.aiff"))))
    (live-main (if-let [secs-arg (first args)] (Long/parseLong secs-arg) 300))))
