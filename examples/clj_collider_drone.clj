;; "A loss of self" by Daniel M. Karlsson, translated from
;; github.com/danielmkarlsson/a_loss_of_self ("A loss of self.scd"):
;; seeded runs of same-chord swells through an FM/PM voice with tanh,
;; Karplus-Strong, resonant lowpass, ringing filter and hearing
;; compensation, freed on envelope end.
;; Run: clojure -M -m clj-collider-drone [seed=2160] [seconds=45] [fade=5] [device]
;;   (boots its own scsynth; device = index into scsynth's list or exact
;;   name, e.g. 3 or "BlackHole 2ch"; omitted = system default)
;; Bounce (bit-identical offline render, no server):
;;   clojure -M -m clj-collider-drone bounce SEED SECS OUTFILE [fade=5]
;;
;; Translation notes (SuperCollider -> here):
;; - `Pseed(n, ...)` is the seed arg (default 2160, as upstream).
;; - `Pstutter(n, pat)` = repeat-each with a count fn drawing its own
;;   white stream (n re-drawn per picked chord, as upstream).
;; - `Pkey(k)` = `(fn [ev] ...)` reading the partially built event (pbind
;;   evaluates pairs left-to-right, so referenced keys come first).
;; - `Prand([Pwhite..1, Pwhite..1])` embeds one single draw per pick.
;; - Reversed `Pwhite(0.6, 0.05)` works (negative span uniform).
(ns clj-collider-drone
  (:require [clojure.string :as str]
            [clojure-collider.server.server :as server]
            [clojure-collider.server.options :as opts]
            [clojure-collider.synth.sdef :as sdef]
            [clojure-collider.synth.operators :as ops]
            [ugens :as ug]
            [patterns.util :as u]
            [pattern :as p]))

(def bpm 120)

(defn define-voice!
  "Define :als + :mix-limiter on the current *s* (server or NRT score).
  Must run after boot / inside with-rendering."
  []
  (sdef/defsynth :als
    [freq 440
     atk 0.005
     rel 0.3
     amp 1
     pan 0
     gain 1
     mod-index 2
     phase-mod-index 2
     lpf-freq 18000
     resonance 0
     hold 6.0
     freq-deviation 0.975
     transition-time 0.875
     resonz-q 0.1
     out 0
     ;; Bounce determinism: constant trig fires once at voice start and
     ;; fixes server noise. Live defaults (trig 0) never fire: inert.
     rseed-trig 0
     rseed 1234]
    (ug/rand-seed:ar rseed-trig rseed)
    (let [frq (ug/clip freq 20 20000)
          ;; FM depth ramps 0 -> mod-index over transition-time, scaled by freq.
          mod (ug/sin-osc:ar (ops// frq 2) 0
                             (ops/* (ug/line:ar 0 mod-index transition-time) frq))
          phase-mod (ug/sin-osc:ar frq 0 phase-mod-index)
          ;; Glissando: exponential line from deviation to pitch.
          sig (ug/sin-osc:ar (ops/+ (ug/x-line:ar freq-deviation freq atk) mod)
                             phase-mod)
          env (ug/env-gen:kr (ug/env [0 1 1 0] [atk hold rel] :sine) :act :free)
          ;; Panning glides 0 -> pan; amp rides as the level.
          sig (ug/pan2:ar sig (ug/line:ar 0 pan transition-time) amp)
          ;; Karplus-Strong mixer.
          sig (ops/+ (ops/* sig 0.8)
                     (ops/* (ug/pluck:ar sig (ug/pink-noise:ar) 0.25
                                         (ops// 1 freq) 64.0 0.5)
                            0.4))
          ;; Resonant lowpass; the envelope applies here.
          sig (ops/* (ug/rlpf:ar sig
                                 (ug/clip lpf-freq 200 20000)
                                 (ug/clip (ug/line:ar 1.0 resonance resonance atk)
                                             0.1 1.0))
                     env)
          ;; tanh distortion with ramping gain.
          sig (ops/tanh (ops/* (ug/line:ar 0.001 gain transition-time) sig))
          ;; Ringing filter + hearing compensation.
          sig (ops/+ (ops/* sig 0.75)
                     (ops/* (ug/resonz:ar sig freq resonz-q) 1.125))
          sig (ops/* sig (ug/amp-comp-a:kr freq 12.978271799373))
          ;; Gentle compression, DC block, rumble filter, brick wall.
          sig (ug/compander:ar sig sig 0.25 0.33 1.0 0.002 0.1)
          sig (ug/leak-dc:ar sig)
          sig (ug/hpf:ar sig 32)]
      (ug/out:ar out (ug/limiter:ar sig 0.1))))
  (sdef/defsynth :mix-limiter [bus 16
                                 amp 1.0]
    (let [sig (ug/in:ar bus 2)
          sig (ops/* sig 0.5 amp)]
      (ug/out:ar 0 (ug/compander:ar sig sig 0.7 1.0 0.05 0.001 0.1)))))
(def chords
  [[44 20 21 27]
   [32 20 39 40]
   [20 44 45 20 51]
   [44 56 20 51 52]
   [32 44 51 20 28]])

(defn create-phrase [seed]
  ;; Seed the ROOT rng: pulls happen on the scheduler thread (raw Thread,
  ;; no binding conveyance), which sees only the root value. The binding
  ;; keeps direct (calling-thread) pulls deterministic too.
  (u/seed-rng! seed)
  (binding [u/*rng* (java.util.Random. seed)]
    (let [stutter-n (p/as-pstream (p/pwhite 2 7))
          dev-white (p/as-pstream (p/pwhite -0.25 0.0))
          lp-white (p/as-pstream (p/pwhite 3.0 9.0))
          harm (p/as-pstream (p/pexprand 1 9))
          n-of (fn [_] (* 2 (Math/round (double (p/pnext! stutter-n)))))]
      (p/pbind
        :instrument :als
        :dur (p/pwhite 0.1 10.0)
        :gain (p/pexprand 0.001 1.5)
        :transition-time (p/prand [0.875 2.0])
        :mod-index (p/pwhite 0.00001 3.0)
        :phase-mod-index (p/pwhite 0.00001 0.1)
        ;; One picked chord repeated N events in a row (N re-drawn per pick).
        :midinote (p/repeat-each (p/prand chords) n-of)
        :harmonic (p/pfunc #(Math/round (double (p/pnext! harm))))
        ;; Per-event reads of sibling keys (upstream Pkey).
        :freq-deviation (fn [ev] (mapv #(* (:harmonic ev)
                                           (ops/midicps (+ (p/pnext! dev-white) %)))
                                       (:midinote ev)))
        :atk (p/pwhite 15.0 45.0)
        :hold (p/pwhite 4.0 14.0)
        :rel (p/pwhite 30.0 60.0)
        :amp (fn [ev] (* 0.75 (/ 1 (:harmonic ev))))
        :lpf-freq (fn [ev] (mapv #(* (p/pnext! lp-white)
                                     (* (:harmonic ev) (ops/midicps %)))
                                 (:midinote ev)))
        :resonz-q (p/pwhite 0.001 1.0)
        :resonance (p/pwhite 0.6 0.05)
        :pan (p/prand [(p/pwhite -1.0 -0.5 1) (p/pwhite 0.5 1.0 1)])
        :type :note
        :out-bus 16))))

(defn player [seed]
  (p/stop)
  (p/start-clock-loop :tempo bpm)
  (p/pdef :alspat (create-phrase seed))
  (p/play :alspat))

(defn bounce
  "Deterministic offline render of the phrase to OUTF (same SEED ->
  bit-identical audio). Events pulled synchronously with the seeded
  pattern RNG, started on an NRT score at exact beat times; every voice
  carries :rseed-trig 1 so its RandSeed fixes server noise. No server
  needed. Returns the absolute output path."
  [seed secs out fade]
  (u/seed-rng! seed)
  (let [bps (/ (double bpm) 60.0)
        end-beat (* (double secs) bps)
        dbg (p/make-debug-backend)
        ;; One pull: play-take builds a fresh stream per call, so chunking
        ;; would replay from the start. 100k events far covers the window
        ;; (:dur >= 0.1 beat -> <= 10 events/beat).
        _ (p/play-take (create-phrase seed) 100000 dbg)
        evts (take-while #(<= (:beat %) end-beat) (p/captured-events dbg))
        total (+ (double secs) (double fade) 2.0)]
    (server/with-rendering [out :sr 44100 :pad total]
      (define-voice!)
      (let [master (sdef/synth :mix-limiter :bus 16 :pos :tail)
            be (p/make-cl-collider-backend server/*s*)]
        (doseq [{:keys [beat event]} evts]
          (p/play-event be
                        (assoc event :rseed-trig 1 :rseed seed)
                        (* (double beat) (/ 60.0 bps))))
        (doseq [i (range 20 0 -1)]
          (server/at (+ (double secs) (* (double fade) (/ (- 20 i) 20.0)))
            (server/ctrl master :amp (/ (dec i) 20.0))))))
    out))

(declare -live-main)

(defn -main [& args]
  (if (= "bounce" (first args))
    (let [[_ seed-arg secs-arg out fade-arg] args
          seed (Long/parseLong seed-arg)
          secs (Long/parseLong secs-arg)
          fade (if fade-arg (Long/parseLong fade-arg) 5)]
      (println :rendered (bounce seed secs out fade)))
    (apply -live-main args)))

(defn- list-audio-devices
  "Probe scsynth with a bogus -H; it prints its numbered device list
  (then exits or hangs on the bad device). Returns [[index name] ...]."
  []
  (let [pb (doto (ProcessBuilder. ^java.util.List
                                  [server/*sc-synth-program* "-u" "57999"
                                   "-H" "__no-such-device__"])
             (.redirectErrorStream true))
        proc (.start pb)]
    ;; Bogus -H may exit or hang after printing; wait, drain what's there
    ;; (never slurp to EOF), then kill.
    (.waitFor proc 10 java.util.concurrent.TimeUnit/SECONDS)
    (let [s (.getInputStream proc)
          baos (java.io.ByteArrayOutputStream.)
          buf (byte-array 8192)]
      (loop []
        (let [n (.available s)]
          (when (pos? n)
            (let [r (.read s buf 0 (min n 8192))]
              (when (pos? r)
                (.write baos buf 0 r)
                (recur))))))
      (.destroy proc)
      (->> (str/split-lines (.toString baos))
           (keep #(re-matches #"\s*(\d+)\s*:\s*\"(.*)\"" %))
           (mapv (fn [[_ i nm]] [(Long/parseLong i) nm]))))))

(defn- resolve-device
  "CLI device arg -> scsynth -H name. Bare index looks up the probe list;
  anything else passes through as a device name. nil -> default device."
  [s]
  (cond (nil? s) nil
        (re-matches #"\d+" s)
        (let [i (Long/parseLong s)
              devs (list-audio-devices)]
          (or (some (fn [[j nm]] (when (= i j) nm)) devs)
              (throw (ex-info "device index out of range"
                              {:index i :devices devs}))))
        :else s))

(defn -live-main [& [seed-arg secs-arg fade-arg device-arg]]
  (let [seed (if seed-arg (Long/parseLong seed-arg) 2160)
        secs (if secs-arg (Long/parseLong secs-arg) 45)
        fade (if fade-arg (Long/parseLong fade-arg) 5)
        device (resolve-device device-arg)
        _ (when device (println :device device))]
    (binding [server/*s* (p/setup-performance!
                           :seed seed :port 57138 :tempo bpm :name "example"
                           :options (opts/make-server-options
                                     {:realtime-mem-size 65536
                                      :max-num-nodes 4096
                                      :device device}))]
      (define-voice!)
      (let [master (sdef/synth :mix-limiter :bus 16 :pos :tail)]
        (player seed)
        (Thread/sleep (* secs 1000))
        (p/end :alspat)
        (doseq [i (range 20 0 -1)]
          (server/ctrl master :amp (/ (dec i) 20.0))
          (Thread/sleep (long (/ (* fade 1000) 20))))
        (Thread/sleep 2000)
        (server/server-quit server/*s*)))
    (println :done)))
