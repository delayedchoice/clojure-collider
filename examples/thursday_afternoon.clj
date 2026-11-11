;; Thursday Afternoon (Brian Eno, 1985) homage: ~16 incommensurable loops
;; over a G-triard drone. Run: clojure -M -m thursday-afternoon [seconds=300]
;; Bounce: clojure -M -m thursday-afternoon bounce [seconds] [out.aiff]
;; Silence: clojure -M -m thursday-afternoon silence (kills every local
;; scsynth, no REPL state needed - for when stop/panic can't reach it)
;; REPL: (def h (thursday-afternoon/start!)) ... (thursday-afternoon/stop! h)
;; for the 10s musical fade, (thursday-afternoon/halt! h) to stop at once.
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

(defn- define-voices! [s]
  (sdef/defsynth s :thursday-keys
    [freq 440
     amp 0.05
     gate 1
     out 0
     pan 0.0]
    (let [f freq
          src (ops/+ (ug/sin-osc:ar f 0 1.0)
                     (ops/* (ug/sin-osc:ar (ops/* f 2.0) 0 1.0) 0.35)
                     (ops/* (ug/sin-osc:ar (ops/* f 3.0) 0 1.0) 0.15))
          dark (ug/lpf:ar src 2600.0 1.0 0.0)
          env (ug/env-gen:kr (ug/env-asr 0.01 1.0 2.0) :gate gate :act :free)]
      (ug/out:ar out (ug/pan2:ar (ops/* dark env amp) pan 1.0))))
  (sdef/defsynth s :thursday-sine
    [freq 440
     amp 0.045
     gate 1
     out 0
     pan 0.0]
    ;; DX7 single sine: 2.11s decay to -18dB-ish, then a short tail.
    (let [env (ug/env-gen:kr (ug/env [0.0 1.0 0.13 0.0] [0.01 2.11 3.0])
                             :act :free)
          sig (ug/sin-osc:ar freq 0 1.0)]
      (ug/out:ar out (ug/pan2:ar (ops/* sig env amp) pan 1.0))))
  (sdef/defsynth s :thursday-drone
    [freq 110
     amp 0.025
     gate 1
     out 0
     pan 0.0]
    (let [wob (ug/sin-osc:kr 0.07 0 1.0)
          trem (ops/+ 1.0 (ops/* 0.2 wob))
          src (ops/+ (ug/sin-osc:ar freq 0 1.0)
                     (ug/sin-osc:ar (ops/* freq 1.002) 0 1.0)
                     (ops/* (ug/sin-osc:ar (ops/* freq 2.003) 0 1.0) 0.4))
          dark (ug/lpf:ar src 380.0 1.0 0.0)
          env (ug/env-gen:kr (ug/env-asr 2.0 1.0 8.0) :gate gate :act :free)]
      (ug/out:ar out (ug/pan2:ar (ops/* dark trem env amp) pan 1.0))))
  (sdef/defsynth s :thursday-verb
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

(defn- start-drones! [s]
  ;; :out 16 routes the drone through the verb's master fade; without it
  ;; drones hit hardware directly (abrupt start, hard cut at the end).
  (mapv (fn [m pan] (sdef/synth s :thursday-drone :freq (p/midinote-freq m)
                                :out 16 :pan pan))
        drone-notes [-0.2 0.0 0.2]))

(defn- play-all! [clock]
  (doseq [[nm _ _ _] loops] (p/play clock nm)))

(defn- stop-all! [clock]
  (p/stop clock :all))
(defrecord ThursdayHandle [server clock verb drones])
;; The handle owns pattern streams and scheduler queues; the default
;; record printer dumps all of it (tens of KB, single line) as every eval
;; value. Terminal REPLs stream that fine, but nREPL+Conjure must render
;; the whole value before the session serves the next eval - a stuck
;; looking session after an otherwise healthy start!. Print one line.
(defmethod print-method ThursdayHandle [h ^java.io.Writer w]
  (.write w (str "#thursday[" (get-in h [:server :name]) " "
                 (if (try (server/boot? (:server h)) (catch Throwable _ false))
                   "up" "down") "]")))


(defn start!
  "Non-blocking Thursday for REPL use: boots scsynth, defines voices and
   sequences, starts the 15 loops and the drone. Returns a ThursdayHandle
   (destructures as {:server :clock :verb :drones}) for stop!/halt!.
   The handle carries the server record and the clock map, so no dynamic
   state is needed to stop from any thread. Prints as one line so eval
   values never dump megabytes into the REPL. Keep in step with live-main
   (same fade shape)."
  [& {:keys [port] :or {port 57176}}]
  (let [s (server/make-external-server "thursday" :port port)
        clock (p/start-clock-loop :tempo 60)]
    (p/connect-server! clock s)
    (define-voices! s)
    (define-sequences!)
    (let [verb (sdef/synth s :thursday-verb :inbus 16)
          drones (start-drones! s)]
      (play-all! clock)
      (println :thursday-started :port port :tasks (count @(:tasks clock)))
      (->ThursdayHandle s clock verb drones))))

(defonce ^:private stopping! (atom []))
;; Servers with a fade-out in flight (identity-compared). The fade runs on
;; a background thread so stop! never parks the calling REPL session thread;
;; on JDK 21 nREPL cannot interrupt a parked eval (Thread.stop throws UOE),
;; so parking the session thread wedges every later eval behind it.
(defn- claim-stopping! [server]
  (let [[old _] (swap-vals! stopping!
                   (fn [v]
                     (if (some #(identical? % server) v) v (conj v server))))]
    (not (some #(identical? % server) old))))

(defn- release-stopping! [server]
  (swap! stopping! (fn [v] (vec (remove #(identical? % server) v))))
  nil)

(defn stop!
  "Fade Thursday out from handle H, without blocking the caller: gates
   the verb (10s release) under which everything else goes quiet, stops
   all tasks, frees verb and drones, quits the server. The fade runs on a
   background thread; stop! itself returns :stopping immediately (a second
   call while the fade is in flight returns :already-stopping, and once
   the server is down calls return :already-stopped). Phases print as
   they happen (:thursday-stopping ... :thursday-tasks-stopped
   :thursday-stopped); a failure prints :thursday-stop-failed and releases
   the claim so a retry is possible. Returns :stopping."
  [{:keys [server clock verb drones]}]
  (cond
    (nil? server)
    (do (println :thursday-stop-skipped :reason :no-server)
        :already-stopped)
    (not (server/boot? server))
    (do (println :thursday-stop-skipped :reason :not-booted)
        :already-stopped)
    (not (claim-stopping! server))
    (do (println :thursday-stop-skipped :reason :already-stopping)
        :already-stopping)
    :else
    (do (println :thursday-stopping :tasks (count @(:tasks clock)))
        (future
          (try
            (server/ctrl verb :gate 0)
            (Thread/sleep 11000)
            (stop-all! clock)
            (println :thursday-tasks-stopped)
            ;; A halt! may have torn everything down while the fade slept;
            ;; that is the expected override, not a failure.
            (if (server/boot? server)
              (do (server/node-free verb)
                  (doseq [d drones] (server/node-free d))
                  (server/server-quit server)
                  (println :thursday-stopped))
              (println :thursday-stopped :already :halted))
            (catch Throwable t
              (println :thursday-stop-failed (.getName (class t)) (ex-message t)))
            (finally
              (release-stopping! server))))
        :stopping)))

(defn halt!
  "Stop Thursday immediately (Overtone-stop semantics): drop all pattern
   tasks, free verb and drones now with no fade, quit the server.
   Synchronous and fast (no sleeps); returns :halted. Overrides a fade
   in flight - the fade then wakes, sees the server down, and stands
   down. For the musical 10s fade, use stop!."
  [{:keys [server clock verb drones]}]
  (cond
    (nil? server)
    (do (println :thursday-halt-skipped :reason :no-server)
        :already-stopped)
    (not (server/boot? server))
    (do (println :thursday-halt-skipped :reason :not-booted)
        :already-stopped)
    :else
    (do (println :thursday-halting)
        (try
          (stop-all! clock)
          (server/node-free verb)
          (doseq [d drones] (server/node-free d))
          (server/server-quit server)
          (println :thursday-halted)
          :halted
          (catch Throwable t
            (println :thursday-halt-failed (.getName (class t)) (ex-message t)))))))

(defn- live-main [secs]
  (let [s (server/make-external-server "thursday" :port 57176)
        clock (p/start-clock-loop :tempo 60)]
    (p/connect-server! clock s)
    (define-voices! s)
    (define-sequences!)
    (let [verb (sdef/synth s :thursday-verb :inbus 16)
          drones (start-drones! s)]
      (play-all! clock)
      (Thread/sleep (* 1000 (max 0 (- secs 10))))
      (when (> secs 10) (server/ctrl verb :gate 0))
      (Thread/sleep (if (> secs 10) 10000 (* 1000 secs)))
      (stop-all! clock)
      (server/node-free verb)
      (doseq [d drones] (server/node-free d)))
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
    (server/with-rendering [nrt out :sr 44100 :pad (+ (double secs) 12.0)]
      (define-voices! nrt)
      (let [verb (sdef/synth nrt :thursday-verb :inbus 16)
            drones (start-drones! nrt)
            be (p/make-cl-collider-backend nrt)]
        (doseq [{:keys [beat event]} evts]
          (p/play-event be (assoc event :tempo bps) (double beat)))
        (server/send-bundle nrt (double (max 0 (- secs 10)))
          [(server/ctrl-msg verb :gate 0)])
        (server/send-bundle nrt (double secs)
          (into [(server/node-free-msg verb)]
                (map server/node-free-msg drones)))))))

(defn silence []
  ;; Find every live scsynth on this machine and silence it. No dynamic
  ;; state or registry needed - works from a bare process when stop/panic
  ;; in another JVM can't reach the sound.
  (let [live (server/find-live-servers)]
    (println :live-servers live)
    (doseq [p live] (server/panic-port! p))
    (println :silenced live)))

(defn -main [& args]
  (cond (= "bounce" (first args))
        (let [[_ secs-arg out] args
              secs (if secs-arg (Long/parseLong secs-arg) 300)]
          (println :rendered (bounce secs (or out "/tmp/thursday.aiff"))))
        (= "silence" (first args))
        (silence)
        :else
        (live-main (if-let [secs-arg (first args)] (Long/parseLong secs-arg) 300))))
