;; "basic waveforms": Seesaw port of sculpt's Basic Waveforms Interface (macOS).
;; A sine/saw/pulse/triangle voice with exp-mapped freq/amp/width sliders,
;; a waveform picker, LOAD / ON toggles, and a client-side preview canvas
;; (replacing the SC plot buttons + scope + freqscope).
;; Run: clojure -M -m basic-waveforms   (boots its own scsynth; close the window to quit)
;;
;; Translation notes (SuperCollider -> here):
;; - Fonts are logical families (Trattatello/Futura are macOS-only).
;; - Amp range unified to 0.01..1.0 (SC displays 0.1..1.0 but sends 0.01..1.0).
;; - LOAD resets the picker to off (SC leaves the which-button stale).
;; - ON auto-loads when nothing is loaded (SC messages a dead node).
(ns basic-waveforms
  (:require [seesaw.core :as s]
            [clojure-collider.server.server :as server]
            [clojure-collider.synth.sdef :as sdef]
            [clojure-collider.synth.operators :as ops]
            [ugens :as ug])
  (:import (java.awt Color)))

(def ^:private light (Color/decode "#97CADB"))
(def ^:private dark (Color/decode "#02457A"))

(def ^:private waves ["off" "sine" "saw" "pulse" "triangle"])

(def ^:private controls
  [{:key :freq :label "Frequency" :lo 50.0 :hi 16000.0 :default 65.41 :fmt "%.0f"}
   {:key :amp :label "Amplitude" :lo 0.01 :hi 1.0 :default 0.1 :fmt "%.2f"}
   {:key :width :label "Pulse Width" :lo 0.1 :hi 0.9 :default 0.5 :fmt "%.2f"}])

;; SC linexp/explin: slider lives in 0..1, values map exponentially.
(defn- linexp [v lo hi] (* lo (Math/pow (/ hi lo) v)))
(defn- explin [x lo hi] (/ (Math/log (/ x lo)) (Math/log (/ hi lo))))
(defn- clamp [x lo hi] (min hi (max lo x)))

(defn define-voice!
  "The SC \\basicwaveforms def: Select over silence/sine/saw/pulse/tri
   under a retriggerable attack-sustain gate envelope, out stereo."
  []
  (sdef/defsynth :basicwaveforms
    [freq 65.41
     amp 0.1
     width 0.5
     which 0
     t-trig 0]
    (let [env (ug/env-gen:kr (ug/env [0 1 0] [0.01 600] :lin) :gate t-trig)
          voice (ug/select:ar which [(ug/dc:ar 0)
                                    (ug/sin-osc:ar freq 0 amp)
                                    (ug/saw:ar freq amp)
                                    (ug/pulse:ar freq width amp)
                                    (ug/lf-tri:ar freq 0 amp)])
          stereo (ops/* env voice)]
      (ug/out:ar 0 [stereo stereo]))))

;; Client-side schematic of the selected wave (width applied). Fixed 25 ms
;; timebase, so frequency reads as density; display gain has a floor so quiet
;; amp settings stay visible (true amp is in the text field).
(def ^:private preview-window 0.025)
(defn- wave-sample [which width phase]
  (let [ph (mod phase 1)]
    (case which
      1 (Math/sin (* 2 Math/PI phase))
      2 (dec (* 2 ph))
      3 (if (< ph width) 1 -1)
      4 (dec (* 4 (Math/abs (- ph 0.5))))
      0.0)))

(defn- paint-preview [levels c g]
  (let [w (.getWidth c) h (.getHeight c)
        {:keys [freq amp width which]} @levels
        mid (/ h 2)
        y-scale (* (- mid 6) (min 1.0 (max 0.35 (* 2.5 amp))))]
    (doto g
      (.setColor dark) (.fillRect 0 0 w h)
      (.setColor light) (.drawLine 0 mid w mid))
    (let [prev (atom nil)]
      (doseq [x (range w)]
        (let [t (* preview-window (/ x (max 1 w)))
              y (- mid (* y-scale (wave-sample which width (* freq t))))]
          (when @prev (.drawLine g (dec x) (int @prev) x (int y)))
          (reset! prev y))))))

(defn start!
  "Build and show the GUI on `s` (an already-booted server with the voice
   defined). Returns handles for tests: {:frame :node :sliders ...}."
  [s]
  (let [node (atom nil)
        levels (atom {:freq 65.41 :amp 0.1 :width 0.5 :which 0})
        echo (atom false) ; suppress listeners during programmatic updates
        canvas (s/canvas :paint (partial paint-preview levels)
                         :background dark :preferred-size [560 :by 180])
        free! (fn [] (when @node (server/node-free @node) (reset! node nil)))
        send! (fn [k v] (swap! levels assoc k v)
                (when @node (server/ctrl @node k v))
                (s/repaint! canvas))
        rows (mapv
              (fn [{:keys [key label lo hi default fmt]}]
                (let [slider (s/slider :min 0 :max 1000
                                       :value (Math/round
                                               (* 1000 (explin default lo hi))))
                      field (s/text :text (format fmt (double default))
                                   :columns 7)
                      from-slider! (fn []
                                    (let [v (linexp (/ (s/value slider) 1000.0) lo hi)]
                                      (s/config! field :text (format fmt v))
                                      (send! key v)))
                      from-field! (fn []
                                    (try
                                      (let [v (clamp (Double/parseDouble
                                                      (s/text field)) lo hi)]
                                        (reset! echo true)
                                        (s/value! slider (Math/round
                                                           (* 1000 (explin v lo hi))))
                                        (reset! echo false)
                                        (from-slider!))
                                      (catch NumberFormatException _
                                        (s/config! field :text
                                                   (format fmt (double (key @levels)))))))]
                  (.addChangeListener slider
                   (reify javax.swing.event.ChangeListener
                     (stateChanged [_ _] (when-not @echo (from-slider!)))))
                  (s/listen field :action (fn [_] (from-field!)))
                  {:key key :slider slider :field field
                   :row [(s/label :text label :foreground light :background dark)
                         slider field]}))
              controls)
        picker (s/combobox :model waves)
        load-btn (s/toggle :text "LOAD")
        on-btn (s/toggle :text "ON")
        with-echo (fn [f] (reset! echo true) (f) (reset! echo false))
        load! (fn []
                (free!)
                (with-echo #(do (s/selection! picker "off")
                                (s/config! load-btn :selected? true)))
                (binding [server/*s* s]
                  (reset! node (sdef/synth :basicwaveforms
                                            :freq (:freq @levels)
                                            :amp (:amp @levels)
                                            :width (:width @levels)
                                            :which 0 :t-trig 0)))
                (send! :which 0))
        unload! (fn []
                  (free!)
                  (with-echo #(do (s/config! load-btn :selected? false)
                                  (s/config! on-btn :selected? false))))]
    (s/listen picker :selection
              (fn [_] (when-not @echo (send! :which (.getSelectedIndex picker)))))
    (s/listen load-btn :action
              (fn [_] (when-not @echo (if (s/value load-btn) (load!) (unload!)))))
    (s/listen on-btn :action
              (fn [_]
                (when-not @echo
                  (if (s/value on-btn)
                    (do (when-not @node (load!))
                        (server/ctrl @node :t-trig 1))
                    (unload!)))))
    (let [ctl-panel (s/grid-panel :rows 3 :columns 3 :background dark
                                  :items (mapcat :row rows))
          btn-panel (s/vertical-panel :background dark
                                      :items [(s/label :text "Waveform"
                                                       :foreground light :background dark)
                                              picker load-btn on-btn])
          frame (s/frame :title "Basic Waveforms"
                         :content (s/border-panel :background light
                                    :north (s/label :text "Basic Waveforms"
                                                    :font "SERIF-PLAIN-48"
                                                    :halign :center
                                                    :foreground dark :background light)
                                    :center (s/horizontal-panel :background light
                                              :items [ctl-panel btn-panel])
                                    :south canvas)
                         :on-close :dispose)]
      (s/listen frame :window-closed
                (fn [_] (free!) (when (server/boot? s) (server/server-quit s))))
      (s/invoke-later (-> frame s/pack! s/show!))
      {:frame frame :node node :levels levels :canvas canvas
       :sliders (into {} (map (juxt :key :slider)) rows)
       :fields (into {} (map (juxt :key :field)) rows)
       :picker picker :load-btn load-btn :on-btn on-btn})))

(defn -main [& _]
  (let [s (server/make-external-server "basic-waveforms" :port 57141)]
    (server/server-boot s)
    (binding [server/*s* s]
      (define-voice!))
    (start! s)
    (println :playing-close-window-to-quit)))
