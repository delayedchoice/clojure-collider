(ns patterns.event
  "Events as plain maps plus resolved value lookup.

  Ports the MVP slice of cl-patterns' event.lisp. An event is an ordinary
  Clojure map; `event-value` derives keys from related keys and defaults:

  - type :note, instrument :default, group 1, out 0, pan 0, tempo 1
  - amp 0.5 cross-converting with db (via conversions)
  - beat as stored; dur 1; delta := dur; duration := dur/tempo;
    sustain := legato*dur (default legato 0.8)
  - freq: explicit :freq wins, else :midinote (default 69 -> 440 Hz),
    else :note (default 0, via conversions), else :degree/:root/:octave/
    :scale (defaults 5/0/5/:major, via scales; note 0 and degree 0 agree);
    rate := freq/base-freq (base default 440 Hz)
  - anything else reads raw (nil when absent)

  Vector raw values multichannel-expand: resolving such a key maps the
  resolution over the elements. `split-event-by-lists` fans an event out
  into one event per channel (max list length, wrap-index)."
  (:require [patterns.util :as u]
            [patterns.conversions :as conv]
            [patterns.scales :as scales]))

;; -- construction ------------------------------------------------------------

(defn event
  "Create an event map from key/value pairs.

  See also: `event-value`, `e`, `*event*`."
  [& kvs]
  (apply hash-map kvs))

(defn event?
  "True if X is an event map."
  [x]
  (map? x))

(defn raw-event-value
  "Get the value of K in event EV without any conversion or default."
  [ev k]
  (get ev k))

(def ^:dynamic *event*
  "Dynamically bound current event for the `e` sugar."
  nil)

;; -- resolution --------------------------------------------------------------

(defn- vmap
  "Apply F to X, mapping over vectors element-wise."
  [f x]
  (if (vector? x)
    (mapv f x)
    (f x)))

(declare event-value)

(defn- pitch-freq
  "Resolve :freq for EV. Precedence: explicit :freq, :midinote, :note,
  degree family (:degree/:root/:octave/:scale), else 440. The note and
  degree paths share conversions' octave numbering, so :note 0 agrees
  with :degree 0 under the same root/octave/scale."
  [ev]
  (cond
    (contains? ev :freq)
    (:freq ev)

    (contains? ev :midinote)
    (vmap conv/midinote-freq (:midinote ev))

    (contains? ev :note)
    (let [root (event-value ev :root)
          octave (event-value ev :octave)]
      (vmap #(conv/midinote-freq (conv/note-midinote % :root root :octave octave)) (:note ev)))

    (or (contains? ev :degree) (contains? ev :root)
        (contains? ev :octave) (contains? ev :scale))
    (let [root (event-value ev :root)
          octave (event-value ev :octave)
          scale (event-value ev :scale)]
      (vmap #(conv/midinote-freq
              (scales/degree-midinote % :root root :octave octave :scale scale))
            (get ev :degree 5)))

    :else 440))

(defn- base-freq
  "Resolve the reference frequency for :rate (explicit :base-freq,
  else :base-note as a midinote, else 440)."
  [ev]
  (cond
    (contains? ev :base-freq) (:base-freq ev)
    (contains? ev :base-note) (conv/midinote-freq (:base-note ev))
    :else 440))

(defn event-value
  "Get the value of K in event EV, running cross-key derivation and defaults.
  Vector raw values resolve element-wise. Yields nil for the end-of-pattern
  marker or unknown keys with no stored value.

  See also: `event`, `e`, `raw-event-value`."
  [ev k]
  (cond
    (= ev u/eop) nil
    (not (map? ev)) nil
    (vector? (get ev k))
    (let [raw (get ev k)]
      (mapv (fn [v] (event-value (assoc ev k v) k)) raw))
    :else
    (case k
      :type (get ev :type :note)
      :instrument (get ev :instrument :default)
      :group (get ev :group 1)
      :out (get ev :out 0)
      :amp (cond (contains? ev :amp) (:amp ev)
                 (contains? ev :db) (conv/db-amp (:db ev))
                 :else 0.5)
      :db (cond (contains? ev :db) (:db ev)
                (contains? ev :amp) (conv/amp-db (:amp ev))
                :else (conv/amp-db 0.5))
      :pan (get ev :pan 0)
      :tempo (get ev :tempo 1)
      :beat (get ev :beat)
      :dur (get ev :dur 1)
      :delta (event-value ev :dur)
      :duration (/ (event-value ev :dur) (event-value ev :tempo))
      :sustain (if (contains? ev :sustain)
                 (:sustain ev)
                 (* (event-value ev :legato) (event-value ev :dur)))
      :legato (cond (contains? ev :legato) (:legato ev)
                    (contains? ev :sustain) (/ (:sustain ev) (event-value ev :dur))
                    :else 0.8)
      :freq (pitch-freq ev)
      :midinote (get ev :midinote 69)
      :note (get ev :note 0)
      :degree (get ev :degree 5)
      :root (get ev :root 0)
      :octave (get ev :octave 5)
      :scale (get ev :scale :major)
      :base-freq (base-freq ev)
      :base-note (get ev :base-note 69)
      :rate (let [f (event-value ev :freq)
                  b (base-freq ev)]
              (vmap #(/ % b) f))
      (get ev k))))

(defn e
  "Syntax sugar; like `event-value' but always reads from `*event*`."
  [k]
  (event-value *event* k))

;; -- compare, combine, copy, split -------------------------------------------

(defn event-equal
  "True if A and B are equivalent events (same raw keys, same resolved values).
  Sequential collections compare element-wise; anything else via `=`."
  [a b]
  (cond
    (and (map? a) (map? b))
    (let [ka (set (keys a))]
      (and (= ka (set (keys b)))
           (every? (fn [k] (= (event-value a k) (event-value b k))) ka)))

    (and (sequential? a) (sequential? b))
    (and (= (count a) (count b))
         (every? true? (map event-equal a b)))

    :else (= a b)))

(defn every-event-equal
  "True if every collection in LISTS holds pairwise-equal events and all
  collections are the same length."
  [& lists]
  (and (apply = (map count lists))
       (every? true? (apply map event-equal lists))))

(defn events-differing-keys
  "Keys whose resolved values differ between EVENTS."
  [& evs]
  (let [ref (first evs)
        ks (distinct (mapcat keys (filter map? evs)))]
    (filterv (fn [k]
               (not (every? (fn [ev] (= (event-value ref k)
                                       (event-value ev k)))
                            evs)))
             ks)))

(defn combine-events
  "Merge EVENTS into one new event; later events win per key.
  Any end-of-pattern argument poisons the result to eop; any key resolving
  to nil aborts the whole combination to nil."
  [& evs]
  (if (some #(= % u/eop) evs)
    u/eop
    (reduce (fn [acc ev]
              (if (nil? acc)
                nil
                (reduce (fn [a k]
                          (let [v (event-value ev k)]
                            (if (nil? v)
                              (reduced nil)
                              (assoc a k v))))
                        acc
                        (keys (or ev {})))))
            {}
            evs)))

(defn copy-event
  "A fresh event with the same entries as EV."
  [ev]
  (combine-events ev))

(defn split-event-by-lists
  "Multichannel expansion: split EV into one event per channel. The channel
  count is the max vector length over keys (scalars repeat, short vectors
  wrap). An empty event yields one empty event."
  [ev]
  (if (not (map? ev))
    [ev]
    (let [ks (keys ev)
          width (fn [v] (if (and (vector? v) (pos? (count v))) (count v) 1))
          n (if (empty? ks) 1 (apply max (map #(width (get ev %)) ks)))
          pick (fn [v i] (if (and (vector? v) (pos? (count v)))
                           (nth v (mod i (count v)))
                           v))]
      (mapv (fn [i]
              (into {} (map (fn [k] [k (pick (get ev k) i)]) ks)))
            (range n)))))
