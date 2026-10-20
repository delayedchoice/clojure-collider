(ns patterns.backend
  "Sound-server backends for patterns.
   Mirrors upstream backend.lisp (MVP slice) + backends/supercollider.lisp
   + backends/debug.lisp: a Backend protocol (play-event [b ev beat-sec]),
   a DebugBackend record capturing {:beat ev} into an atom for tests, and a
   ClColliderBackend record {:server} that starts synths via sdef/synth
   scheduled with server/at and releases gated synths via server/ctrl."
  (:require [patterns.util :as u]
            [patterns.event :as ev]
            [clojure-collider.server.server :as server]
            [clojure-collider.synth.sdef :as sdef]))

(defprotocol Backend
  "A destination events can be played on.
   Mirrors upstream backend-play-event (MVP: single timestamp, seconds)."
  (play-event [b ev beat-sec]
    "Play event map EV scheduled at BEAT-SEC (seconds). Returns the synth
     node for sounding events, nil for rests/silence."))

;; -- reserved keys ------------------------------------------------------------

(def reserved-keys
  "Event keys consumed by the clock/backend, never forwarded as synth controls.
   Per DECISIONS.md 2026-10-20."
  #{:type :dur :delta :duration :sustain :legato :quant :play-quant
    :beat :tempo :timing-offset :group :out :backend})

(defn control-args
  "Strip reserved keys (plus :instrument) from EV, resolve :freq via
   event-value, and return a flat [:k v ...] control-arg seq for sdef/synth.
   Nil-valued controls are dropped."
  [event-map]
  (let [freq (ev/event-value event-map :freq)
        kept (apply dissoc event-map :instrument reserved-keys)]
    (into []
          (comp (remove (fn [[_ v]] (nil? v)))
                (mapcat identity))
          (assoc kept :freq freq))))

(defn- instrument-name
  "Resolve the synth name from EV's :instrument (keyword/string/symbol,
   default :default)."
  [event-map]
  (let [i (get event-map :instrument :default)]
    (cond (keyword? i) i
          (string? i) (keyword i)
          (symbol? i) (keyword (name i))
          :else :default)))

;; -- debug backend ------------------------------------------------------------

(defrecord DebugBackend [captured]
  Backend
  (play-event [_ event-map beat-sec]
    (let [entry {:beat beat-sec :event event-map}]
      (swap! captured conj entry)
      entry)))

(defn make-debug-backend
  "Make a DebugBackend capturing {:beat beat-sec :event ev} into an atom.
   Mirrors upstream debug-backend (MVP: no print-stream)."
  []
  (->DebugBackend (atom [])))

(defn captured-events
  "Events captured by backend B (oldest first)."
  [backend]
  @(:captured backend))

(defn clear-captured!
  "Clear backend B's captured events."
  [backend]
  (reset! (:captured backend) [])
  nil)

;; -- cl-collider backend ------------------------------------------------------

(defrecord ClColliderBackend [server]
  Backend
  (play-event [b event-map beat-sec]
    (let [etype (ev/event-value event-map :type)]
      (when-not (= :rest etype)
        (let [srv (:server b)
              iname (instrument-name event-map)
              args (control-args event-map)
              node (binding [server/*s* srv]
                     (server/at beat-sec
                       (apply sdef/synth iname args)))]
          (when-some [sus (ev/event-value event-map :sustain)]
            (when (and (number? sus) (pos? sus))
              (let [tempo (or (ev/event-value event-map :tempo) 1)
                    gate-sec (+ (double beat-sec)
                                (* (double sus) (/ 1.0 (double tempo))))]
                (binding [server/*s* srv]
                  (server/callback gate-sec
                                   (fn [] (server/ctrl node :gate 0)))))))
          node)))))

(defn make-cl-collider-backend
  "Make a ClColliderBackend bound to SERVER (defaults to server/*s*).
   :type :rest events produce silence (no synth). Synths exposing a gate
   are released sustain-beats later; gateless synths play their envelope."
  ([] (make-cl-collider-backend server/*s*))
  ([srv] (->ClColliderBackend srv)))

;; Re-exported so the end-of-pattern contract is visible from this ns too.
(def eop
  "End-of-pattern marker (same keyword as patterns.util/eop)."
  u/eop)
