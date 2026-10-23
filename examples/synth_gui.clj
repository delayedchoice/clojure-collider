;; Generic Seesaw control panel for any synthdef: one slider + text field per
;; control, derived from `sdef/synthdef-metadata`, plus a PLAY/STOP toggle.
;; Run against the drone: clojure -M -m drone-gui
;;
;; Range inference (overridable per control): positive default d -> [0, 2d],
;; negative -> [2d, 0], zero -> [-1, 1]; all linear unless :exp? is set
(ns synth-gui
  (:require [clojure.string :as str]
            [seesaw.core :as s]
            [clojure-collider.server.server :as server]
            [clojure-collider.synth.sdef :as sdef])
  (:import (java.awt Color)))

(def ^:private light (Color/decode "#97CADB"))
(def ^:private dark (Color/decode "#02457A"))

(defn- linexp [v lo hi] (* lo (Math/pow (/ hi lo) v)))
(defn- explin [x lo hi] (/ (Math/log (/ x lo)) (Math/log (/ hi lo))))
(defn- clamp [x lo hi] (min hi (max lo x)))

(defn specs-from-synthdef
  "Slider specs for SYNTH-NAME from its defsynth metadata.
   SKIP excludes keys (default #{:out}); RANGES maps key -> partial spec
   ({:lo :hi :exp? :fmt}) merged over the inference."
  [synth-name & {:keys [skip ranges] :or {skip #{:out} ranges {}}}]
  (let [pairs (sdef/synthdef-metadata synth-name :controls)]
    (when-not (seq pairs)
      (throw (ex-info "no defsynth metadata (require the defining ns first?)"
                      {:synth synth-name})))
    (into []
          (comp (filter (fn [[k v]]
                          (and (number? v)
                               (not (contains? skip (keyword (str/lower-case
                                                            (name k))))))))
                (map (fn [[k d]]
                       (let [key (keyword (str/lower-case (name k)))
                             [lo hi] (cond (pos? d) [0.0 (* 2.0 d)]
                                           (neg? d) [(* 2.0 d) 0.0]
                                           :else [-1.0 1.0])]
                         (merge {:key key
                                 :label (str/capitalize (name k))
                                 :lo (double lo) :hi (double hi)
                                 :default (double d) :fmt "%.4g"}
                                (get ranges key))))))
          pairs)))

(defn start!
  "Build and show the control panel for SYNTH-NAME on `s`.
   SPECS from `specs-from-synthdef`; EXTRA-ARGS fixed synth kwargs
   (e.g. {:voiceid 1}). Returns test handles."
  [s synth-name & {:keys [specs title extra-args]
                   :or {title (str synth-name) extra-args {}}}]
  (let [node (atom nil)
        levels (atom (into {} (map (juxt :key :default)) specs))
        echo (atom false)
        free! (fn [] (when @node (server/node-free @node) (reset! node nil)))
        send! (fn [k v] (swap! levels assoc k v)
                (when @node (server/ctrl @node k v)))
        rows (mapv
              (fn [{:keys [key label lo hi default fmt exp?]}]
                (let [exp? (and exp? (pos? lo))
                      to-real (fn [sv] (let [v (/ sv 1000.0)]
                                        (if exp? (linexp v lo hi)
                                            (+ lo (* v (- hi lo))))))
                      to-slider (fn [x] (Math/round
                                        (* 1000 (if exp? (explin (max x lo) lo hi)
                                                 (/ (- x lo) (- hi lo))))))
                      slider (s/slider :min 0 :max 1000 :value (to-slider default))
                      field (s/text :text (format fmt (double default)) :columns 8)
                      from-slider! (fn []
                                     (let [v (to-real (s/value slider))]
                                       (s/config! field :text (format fmt v))
                                       (send! key v)))
                      from-field! (fn []
                                    (try
                                      (let [v (clamp (Double/parseDouble
                                                      (s/text field)) lo hi)]
                                        (reset! echo true)
                                        (s/value! slider (to-slider v))
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
              specs)
        play-btn (s/toggle :text "PLAY")
        status (s/label :text "stopped" :foreground light :background dark)]
    (s/listen play-btn :action
              (fn [_]
                (when-not @echo
                  (if (s/value play-btn)
                    (do (free!)
                        (binding [server/*s* s]
                          (reset! node (apply sdef/synth synth-name
                                             (mapcat (fn [[k v]] [k v])
                                                      (merge @levels extra-args)))))
                        (s/config! status :text "playing"))
                    (do (free!)
                        (s/config! status :text "stopped"))))))
    (let [exit-btn (s/button :text "EXIT")
          panel (s/grid-panel :rows (count rows) :columns 3 :background dark
                              :items (mapcat :row rows))
          scroll (s/scrollable panel :background dark)
          frame (s/frame :title title
                         :content (s/border-panel :background light
                                    :north (s/horizontal-panel :background dark
                                             :items [play-btn status exit-btn])
                                    :center scroll)
                         :on-close :dispose)]
      ;; :window-closed does the teardown (free + server-quit); EXIT just
      ;; takes that path so the two can't diverge.
      (s/listen exit-btn :action (fn [_] (.dispose ^java.awt.Frame frame)))
      (s/listen frame :window-closed
                (fn [_] (free!) (when (server/boot? s) (server/server-quit s))))
      (s/invoke-later (-> frame (s/config! :size [420 :by 600]) s/show!))
      {:frame frame :node node :levels levels
       :sliders (into {} (map (juxt :key :slider)) rows)
       :fields (into {} (map (juxt :key :field)) rows)
       :play-btn play-btn :exit-btn exit-btn})))
