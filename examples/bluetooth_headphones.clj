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
 ;; Why TWO AirPods entries in Audio MIDI Setup? macOS exposes one
 ;; physical headset as two logical CoreAudio devices, one per Bluetooth
 ;; profile: the hands-free/mic endpoint ("...Pro 1": 1 in, 0 out, ~24k)
 ;; and the stereo music endpoint ("...Pro 2": 0 in, 2 out, 48k). This is
 ;; also what your original boot failure was: with inputs enabled,
 ;; scsynth tried to open the 24k mic endpoint while output ran at 48k.
 ;; We now boot output-only (-i 0), so we only ever touch the "Pro 2"
 ;; music endpoint - the mic one is never opened. But the converse still
 ;; bites: if ANY app opens the mic endpoint, the headset drops to
 ;; low-quality headset mode and our music endpoint buzzes with it.
 ;;
 ;; Which name to use for -H? The 0-in / 2-out MUSIC endpoint. Get its
 ;; exact spelling from the device list our own boot prints (look for the
 ;; entry whose Output block shows 2 channels), or from Audio MIDI Setup;
 ;; pass it explicitly, e.g. (explicit-device "Robert's Airpods Pro 2").
 ;; Watch the quote character: macOS names often use a curly ’ (U+2019) -
 ;; copy it, don't retype it as ASCII ', or -H won't match.
 ;;
 ;; Before ANY scenario, do the macOS half (this fixes most cases alone):
 ;;   1. System Settings -> Sound -> Output = the MUSIC endpoint above
 ;;      (NOT "Multi-Output Device" - aggregates drift and buzz).
 ;;   2. Same pane -> Input = "MacBook Pro Microphone". This keeps the
 ;;      AirPods mic endpoint untouched so macOS holds high-quality A2DP
 ;;      instead of dropping to headset mode.
 ;;   3. Quit anything holding the mic (Zoom/Meet/etc). The orange mic dot
 ;;      in the menu bar must be OFF.
 ;;   4. The rate to force in Step 4 is the music endpoint's rate (48k);
 ;;      the mic endpoint's 24k is irrelevant to us - we never open it.
 (ns bluetooth-headphones
   (:require [clojure-collider.server.server :as server]
             [clojure-collider.server.options :as opts]
             [clojure-collider.synth.sdef :as sdef]
             [ugens :as ug]))

 (def default-device "Robert’s AirPods P")
 ;; Placeholder: replace with YOUR 0-in / 2-out music endpoint, copied
 ;; exactly from the device list our boot prints (or Audio MIDI Setup).
 ;; NB: macOS names often use a curly ’ (U+2019) - copy it, don't retype
 ;; it as ASCII ', or -H won't match. Or pass the name per call, e.g.
 ;; (explicit-device "Robert's Airpods Pro 2").

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
   "Step 2: pin scsynth straight at the AirPods MUSIC endpoint with -H,
   bypassing whatever macOS has as default (often a drifting Multi-Output
   aggregate). Use the 0-in / 2-out entry (the 1-in mic entry is the wrong
   device - opening it drops the headset to low-quality mode).
   Run: (bluetooth-headphones/explicit-device)
   Exact name: (bluetooth-headphones/explicit-device \"Robert's Airpods Pro 2\")"
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
   "Step 4: device + buffer + a forced hardware sample rate (-S). Use the
   MUSIC endpoint's rate (48k on yours) - the mic endpoint's 24k is
   irrelevant, we never open it. If 48k still buzzes, try 44100.
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
