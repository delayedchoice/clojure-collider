(ns patterns.conversions
  "Unit conversions ported from cl-patterns conversions.lisp.
   Pure functions; no dependency on patterns.util or scales.
   Scale-aware degree fns resolve scales through the injected
   `*scale-lookup*` hook (or a per-call `:lookup` option) so this
   namespace never requires the scales namespace (which requires
   conversions back). A scale lookup fn takes a scale key and returns
   {:notes [semitone ...] :steps-per-octave n :octave-ratio r};
   plain maps with :notes and sequential note lists pass through."
  (:require [clojure.string :as string]))

(def ^:dynamic *scale-lookup*
  "Injected fn: scale key -> {:notes [...] :steps-per-octave n :octave-ratio r}.
   Bound by the scales namespace. Nil means only :major (built-in) resolves."
  nil)

(defn set-scale-lookup!
  "Install the global scale lookup fn (scales-namespace wiring;
   prefer `binding` in tests)."
  [f]
  (alter-var-root #'*scale-lookup* (constantly f))
  *scale-lookup*)

(def ^:private major-notes [0 2 4 5 7 9 11])

(defn- resolve-scale
  "Normalize SCALE (key, note list, or scale map) to a scale map."
  [scale lookup]
  (cond
    (nil? scale) {:notes major-notes :steps-per-octave 12 :octave-ratio 2.0}
    (map? scale)
    (if (:notes scale)
      {:notes (vec (:notes scale))
       :steps-per-octave (or (:steps-per-octave scale) 12)
       :octave-ratio (or (:octave-ratio scale) 2.0)}
      (throw (ex-info "scale map missing :notes" {:scale scale})))
    (sequential? scale) {:notes (vec scale) :steps-per-octave 12 :octave-ratio 2.0}
    :else
    (let [lk (or lookup *scale-lookup*)]
      (if (and lk (or (keyword? scale) (string? scale)))
        (resolve-scale (lk scale) nil)
        (if (and (keyword? scale) (= :major (keyword (name scale))))
          {:notes major-notes :steps-per-octave 12 :octave-ratio 2.0}
          (throw (ex-info "no scale lookup bound and unknown scale"
                          {:scale scale})))))))

(defn bipolar-unipolar
  "Convert bipolar (-1..1) to unipolar (0..1)."
  [number]
  (+ 1/2 (/ (double number) 2)))

(defn unipolar-bipolar
  "Convert unipolar (0..1) to bipolar (-1..1)."
  [number]
  (- (* 2 (double number)) 1))

(defn amp-db
  "Convert amplitude to decibels."
  [amp]
  (* 20 (/ (Math/log (double amp)) (Math/log 10))))

(defn db-amp
  "Convert decibels to amplitude."
  [db]
  (Math/pow 10 (/ (double db) 20)))

(defn dur-duration
  "Convert DUR in beats to seconds at TEMPO (beats per second, default 1)."
  ([dur] (dur-duration dur 1))
  ([dur tempo] (/ (double dur) (double (or tempo 1)))))

(defn duration-dur
  "Convert DURATION in seconds to beats at TEMPO (default 1)."
  ([duration] (duration-dur duration 1))
  ([duration tempo] (* (double duration) (double (or tempo 1)))))

(defn dur-time
  "Alias for dur-duration."
  ([dur] (dur-duration dur 1))
  ([dur tempo] (dur-duration dur tempo)))

(defn time-dur
  "Alias for duration-dur."
  ([t] (duration-dur t 1))
  ([t tempo] (duration-dur t tempo)))

(defn dur-freq
  "Convert DUR in beats to frequency in Hz at TEMPO (default 1)."
  ([dur] (dur-freq dur 1))
  ([dur tempo] (/ (double (or tempo 1)) (double dur))))

(defn freq-dur
  "Convert FREQ in Hz to beats at TEMPO (default 1)."
  ([freq] (freq-dur freq 1))
  ([freq tempo] (/ (double (or tempo 1)) (double freq))))

(defn rate-freq
  "Convert playback RATE to frequency at BASE-FREQ (default 440)."
  ([rate] (rate-freq rate 440))
  ([rate base-freq] (* (double base-freq) (double rate))))

(defn freq-rate
  "Convert FREQ to playback rate at BASE-FREQ (default 440)."
  ([freq] (freq-rate freq 440))
  ([freq base-freq] (/ (double freq) (double base-freq))))

(def c4-midinote
  "MIDI note number of C4; sets global tuning of pitch conversions."
  60)

(defn midinote-freq
  "Convert MIDI note number to frequency."
  [midinote]
  (* 440.0 (Math/pow 2.0 (/ (- (double midinote) 69.0) 12.0))))

(defn freq-midinote
  "Convert frequency to MIDI note number."
  [freq]
  (+ 69.0 (* 12.0 (/ (Math/log (/ (double freq) 440.0)) (Math/log 2.0)))))

(defn rate-midinote
  "Convert playback RATE to midinote relative to BASE-NOTE (default 69)."
  ([rate] (rate-midinote rate 69))
  ([rate base-note] (freq-midinote (rate-freq rate (midinote-freq base-note)))))

(defn midinote-rate
  "Convert MIDINOTE to playback rate relative to BASE-NOTE (default 69)."
  ([midinote] (midinote-rate midinote 69))
  ([midinote base-note]
   (freq-rate (midinote-freq midinote) (midinote-freq base-note))))

(defn ratio-midi
  "Convert frequency RATIO to a MIDI-note difference."
  [ratio]
  (* 12 (/ (Math/log (double ratio)) (Math/log 2.0))))

(defn midi-ratio
  "Convert a MIDI-note difference to a frequency ratio."
  [midi]
  (Math/pow 2.0 (/ (double midi) 12.0)))

(defn octave-midinote
  "Lowest MIDI note number of OCTAVE."
  [octave]
  (+ c4-midinote (* 12 (- (long octave) 4))))

(defn midinote-octave
  "Octave number containing MIDINOTE."
  [midinote]
  (+ 4 (long (Math/floor (/ (- (double midinote) c4-midinote) 12.0)))))

(defn octave-freq
  "Base frequency of OCTAVE."
  [octave]
  (midinote-freq (octave-midinote octave)))

(defn freq-octave
  "Octave number containing FREQ."
  [freq]
  (midinote-octave (freq-midinote freq)))

(def note-names
  "Note names per chromatic index (12ET)."
  [[:c :b#] [:c# :db] [:d] [:d# :eb] [:e :fb] [:f :e#]
   [:f# :gb] [:g] [:g# :ab] [:a] [:a# :bb] [:b :cb]])

(defn- wrap-index [n len]
  (mod (long n) (long len)))

(defn- parse-note-string
  "Split a note designator string into [name octave-or-nil]."
  [s]
  (let [lower (string/lower-case (str s))
        m (re-matches #"([a-g][#b]?)(-?\d*)" lower)]
    (when-not m
      (throw (ex-info "invalid note name" {:note s})))
    [(keyword (nth m 1))
     (let [oct (nth m 2)] (when-not (empty? oct) (Long/parseLong oct)))]))

(defn note-name-and-octave
  "Split NOTE into [name-keyword octave], defaulting octave to 4.
   Accepts keywords/strings with optional octave suffix, or numbers
   (treated as :c with that octave... numbers map to :c OCTAVE)."
  [note]
  (cond
    (number? note) [:c (long note)]
    (or (keyword? note) (string? note))
    (let [[nm oct] (parse-note-string (if (keyword? note) (name note) note))]
      [nm (or oct 4)])
    :else (throw (ex-info "invalid note" {:note note}))))

(defn chromatic-index-note
  "Note name of CHROMATIC-INDEX (int, wraps) or name part of a note
   designator. Always returns the first (canonical) name."
  [chromatic-index]
  (cond
    (integer? chromatic-index) (first (nth note-names (wrap-index chromatic-index 12)))
    (number? chromatic-index) (first (nth note-names (wrap-index (long chromatic-index) 12)))
    (or (keyword? chromatic-index) (string? chromatic-index))
    (first (note-name-and-octave chromatic-index))
    :else (throw (ex-info "invalid chromatic index" {:index chromatic-index}))))
(defn note-chromatic-index
  "Chromatic index of NOTE (number passes through; designator looked up)."
  [note]
  (cond
    (number? note) note
    (or (keyword? note) (string? note))
    (let [nm (name (chromatic-index-note note))
          idx (first (keep-indexed (fn [i names]
                                     (when (some #(= nm (name %)) names) i))
                                   note-names))]
      (if (nil? idx)
        (throw (ex-info "unknown note name" {:note note}))
        idx))
    :else (throw (ex-info "invalid note" {:note note}))))

(defn note-midinote
  "MIDI note number of NOTE (number or name designator).
   Options: :root :octave :steps-per-octave :gtranspose :octave-ratio."
  [note & {:keys [root octave steps-per-octave gtranspose octave-ratio]
           :or {root 0 octave 4 steps-per-octave 12 gtranspose 0 octave-ratio 2.0}}]
  (if (and (or (keyword? note) (string? note))
           (re-find #"[a-gA-G]" (if (keyword? note) (name note) (str note))))
    (let [[nm note-oct] (note-name-and-octave note)]
      (note-midinote (note-chromatic-index nm)
                     :root root :octave note-oct
                     :steps-per-octave steps-per-octave
                     :gtranspose gtranspose :octave-ratio octave-ratio))
    (+ (* (+ (/ (+ (double note) (double gtranspose) (double root))
                (double steps-per-octave))
             (double octave)
             -4)
          (* 12 (/ (Math/log (double octave-ratio)) (Math/log 2.0))))
       c4-midinote)))

(defn note-freq
  "Frequency of NOTE (number or name designator); same options as note-midinote."
  [note & args]
  (midinote-freq (apply note-midinote note args)))

(defn midinote-note
  "Note keyword (e.g. :c4) of MIDINOTE."
  [midinote]
  (let [m (Math/round (double midinote))]
    (keyword (str (name (chromatic-index-note (mod m 12)))
                  (midinote-octave m)))))

(defn freq-note
  "Note keyword of FREQ."
  [freq]
  (midinote-note (freq-midinote freq)))

(defn note-octave
  "Octave of NOTE."
  [note]
  (second (note-name-and-octave note)))

(defn octave-note
  "Note keyword :c<OCTAVE> of OCTAVE."
  [octave]
  (keyword (str "c" (long octave))))

(defn chromatic-index-midinote
  "Midinote of CHROMATIC-INDEX at OCTAVE (default 4)."
  ([chromatic-index] (chromatic-index-midinote chromatic-index 4))
  ([chromatic-index octave]
   (+ (long chromatic-index) c4-midinote (* 12 (- (long octave) 4)))))

(defn midinote-chromatic-index
  "Chromatic index of MIDINOTE."
  [midinote]
  (note-chromatic-index (midinote-note midinote)))

(defn chromatic-index-freq
  "Frequency of CHROMATIC-INDEX at OCTAVE (default 4)."
  ([chromatic-index] (chromatic-index-freq chromatic-index 4))
  ([chromatic-index octave]
   (midinote-freq (chromatic-index-midinote chromatic-index octave))))

(defn freq-chromatic-index
  "Chromatic index of FREQ."
  [freq]
  (midinote-chromatic-index (freq-midinote freq)))

(defn- nearest-to
  "Element of COLL nearest to INPUT."
  [input coll]
  (reduce (fn [a b]
            (if (> (Math/abs (- (double input) (double a)))
                   (Math/abs (- (double input) (double b))))
              b a))
          coll))

(defn degree-key
  "Note (in semitone units) at index DEGREE of SCALE, with wraparound.
   Options: :scale (default :major), :steps-per-octave, :accidental."
  [degree & {:keys [scale steps-per-octave accidental lookup]
             :or {scale :major accidental 0}}]
  (let [{resolved-steps :steps-per-octave resolved-notes :notes} (resolve-scale scale lookup)
        steps (or steps-per-octave resolved-steps 12)
        notes (vec resolved-notes)
        n (count notes)
        d (long degree)
        base (+ (* steps (long (Math/floor (/ (double d) (double n)))))
                (nth notes (wrap-index d n)))]
    (if (zero? accidental)
      base
      (+ base (* (double accidental) (/ (double steps) 12.0))))))

(defn degree-note
  "Note number of DEGREE in SCALE. Options: :scale :steps-per-octave
   :mtranspose :lookup."
  [degree & {:keys [scale steps-per-octave mtranspose lookup]
             :or {scale :major mtranspose 0}}]
  (let [{dsteps :steps-per-octave} (resolve-scale scale lookup)]
    (degree-key (+ (long degree) (long mtranspose))
                :scale scale
                :steps-per-octave (or steps-per-octave dsteps)
                :lookup lookup)))

(defn note-degree
  "Degree of NOTE in SCALE (truncates to nearest scale tone).
   Options: :root :scale :lookup."
  [note & {:keys [root scale lookup] :or {root 0 scale :major}}]
  (let [{:keys [notes steps-per-octave]} (resolve-scale scale lookup)
        transposed (mapv #(+ % (double root)) notes)
        target (mod (long (note-chromatic-index note)) (long steps-per-octave))
        res (nearest-to target transposed)]
    (first (keep-indexed (fn [i v] (when (= v res) i)) transposed))))

(defn degree-midinote
  "MIDI note of DEGREE. Options: :root :octave :scale :lookup."
  [degree & {:keys [root octave scale lookup]
             :or {root 0 octave 4 scale :major}}]
  (let [{:keys [steps-per-octave octave-ratio]} (resolve-scale scale lookup)]
    (note-midinote (degree-note degree :scale scale
                                :steps-per-octave steps-per-octave
                                :lookup lookup)
                   :root root :octave octave
                   :steps-per-octave steps-per-octave
                   :octave-ratio octave-ratio)))

(defn midinote-degree
  "Degree of MIDINOTE. Options: :root :scale :lookup."
  [midinote & {:keys [root scale lookup] :or {root 0 scale :major}}]
  (note-degree (midinote-note midinote) :root root :scale scale :lookup lookup))

(defn degree-freq
  "Frequency of DEGREE. Options: :root :octave :scale :lookup."
  [degree & args]
  (midinote-freq (apply degree-midinote degree args)))

(defn freq-degree
  "Degree of FREQ. Options: :root :scale :lookup."
  [freq & {:keys [root scale lookup] :or {root 0 scale :major}}]
  (midinote-degree (freq-midinote freq) :root root :scale scale :lookup lookup))

(defn degree-octave
  "Octave of DEGREE; always 4 (degrees carry no octave)."
  [_degree]
  4)

(defn octave-degree
  "Degree of OCTAVE; always 0."
  [_octave]
  0)

(defn degree-chromatic-index
  "Chromatic index of DEGREE. Options: :scale :steps-per-octave
   :mtranspose :lookup."
  [degree & args]
  (note-chromatic-index (apply degree-note degree args)))

(defn chromatic-index-degree
  "Degree of CHROMATIC-INDEX. Options: :root :scale :lookup."
  [chromatic-index & {:keys [root scale lookup] :or {root 0 scale :major}}]
  (note-degree (chromatic-index-note chromatic-index)
               :root root :scale scale :lookup lookup))

(defn midi-truncate-clamp
  "Truncate NUMBER and clamp to 0..MAX (default 127)."
  ([number] (midi-truncate-clamp number 127))
  ([number max]
   (let [t (long number) m (long max)]
     (clojure.core/max 0 (clojure.core/min m t)))))
