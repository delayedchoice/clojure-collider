;; Bluetooth headphones bring-up (e.g. AirPods buzzing/crackling).
;; Run from a REPL - one scenario at a time, they all grab the audio device:
;;   (require 'bluetooth-headphones)
;;   (bluetooth-headphones/baseline)          ; Step 1: defaults
;;   (bluetooth-headphones/explicit-device)   ; Step 2: pin -H to the AirPods
;;   (bluetooth-headphones/big-buffer)        ; Step 3: -Z 1024 (or 2048)
;;   (bluetooth-headphones/forced-rate)       ; Step 4: -S 48000 (or 44100)
;;   (bluetooth-headphones/kitchen-sink)      ; everything at once
;; Each plays a 440/442 test chord for 15s (buzz is obvious on a pure
;; tone), then quits its server. Note which step goes clean and report it.
;;
;; Before ANY scenario, do the macOS half (this fixes most cases alone):
;;   1. System Settings -> Sound -> Output = your AirPods (NOT "Multi-Output
;;      Device" - aggregates drift and buzz).
;;   2. Same pane -> Input = "MacBook Pro Microphone". Any app holding the
;;      AirPods mic drops the whole headset to low-quality headset mode.
;;      The orange mic dot in the menu bar must be OFF (quit Zoom/Meet/etc).
;;   3. Audio MIDI Setup (Utilities) -> AirPods output -> note Format rate.
(ns bluetooth-headphones
  (:require [clojure-collider.server.server :as server]
            [clojure-collider.server.options :as opts]
            [clojure-collider.synth.sdef :as sdef]
            [ugens :as ug]))

(def default-device "Robert’s AirPods P")
;; NB: the ’ above is a CURLY quote (U+2019) - copy it from the scsynth
;; device list, don't retype it as ASCII ', or -H won't match.

(defn- play-chord!
  "Boot on PORT with OPTS, play a 440/442 chord for SECS, quit. Private."
  [port options secs]
  (let [s (server/make-external-server "bluetooth" :port port
            :options (opts/make-server-options options))]
    (server/server-boot s)
    (binding [server/*s* s]
      (let [n (sdef/play (ug/sin-osc:ar [440 442] 0 0.2))]
        (Thread/sleep (* 1000 secs))
        (server/node-free n)))
    (server/server-quit s)
    (println :done)))

(defn baseline
  "Step 1: stock output-only boot (no extra options). If this is clean,
   the macOS settings above were the whole problem - stop here.
   Run: (bluetooth-headphones/baseline)"
  []
  (play-chord! 57170 {} 15))

(defn explicit-device
  "Step 2: pin scsynth straight at the AirPods with -H, bypassing whatever
   macOS has as default (often a drifting Multi-Output aggregate).
   Run: (bluetooth-headphones/explicit-device)
   Other headphones: (bluetooth-headphones/explicit-device \"Name Here\")"
  [& [device]]
  (play-chord! 57171 {:device (or device default-device)} 15))

(defn big-buffer
  "Step 3: device + a larger hardware buffer (-Z) for Bluetooth jitter.
   Defaults to 1024; if still choppy, rerun with 2048.
   Run: (bluetooth-headphones/big-buffer)
   Bigger: (bluetooth-headphones/big-buffer 2048)"
  [& [size]]
  (play-chord! 57172 {:device default-device
                      :hardware-buffer-size (or size 1024)} 15))

(defn forced-rate
  "Step 4: device + buffer + a forced hardware sample rate (-S). Start
   with the Format rate Audio MIDI Setup shows for the AirPods (usually
   48000); if it still buzzes, try the other one.
   Run: (bluetooth-headphones/forced-rate)
   Other rate: (bluetooth-headphones/forced-rate 44100)"
  [& [rate]]
  (play-chord! 57173 {:device default-device
                      :hardware-buffer-size 1024
                      :hardware-samplerate (or rate 48000)} 15))

(defn kitchen-sink
  "Everything at once: device + 2048 buffer + forced rate. When the
   earlier steps disagree, this is the most forgiving combo; narrow it
   down afterwards by dropping keys.
   Run: (bluetooth-headphones/kitchen-sink)"
  []
  (play-chord! 57174 {:device default-device
                      :hardware-buffer-size 2048
                      :hardware-samplerate 48000} 15))
