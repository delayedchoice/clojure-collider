(ns patterns.scales
  "Tunings, scales, and chords ported from cl-patterns scales.lisp (v0.7).

   Tunings, scales, and chords are plain data maps held in three registry
   atoms. Each registry maps canonical keys to definition maps, plus alias
   keys that point at canonical keys (mirroring upstream's alias scheme):

   - tuning  {:name str :key kw :pitches [...] :octave-ratio n}
   - scale   {:name str :key kw :notes [...] :tuning tuning-key}
   - chord   {:name str :key kw :indexes [...] :scale scale-key}

   Key normalization: names and lookups are lowercased, runs of
   whitespace/underscores/slashes/commas become a single hyphen, and all
   other punctuation is dropped, e.g. \"Equal Temperament 12\" becomes
   :equal-temperament-12 and \"Meantone, 1/4 Syntonic Comma\" becomes
   :meantone-1-4-syntonic-comma. Upstream auto-generates abbreviated
   aliases (MAJOR->MAJ, MINOR->MIN, ...); those are generated here too and
   registered first-wins, but only the canonical keys plus the explicit
   aliases from the tables (e.g. :et12, :just, :maj7, :dimtri) are
   guaranteed stable. Obscure auto-aliases may differ from upstream's
   friendly-symbol spellings and should not be relied upon.

   Lookup fns (tuning/scale/chord) accept a keyword, a string (normalized
   the same way), or a definition map (passed through). Accessor fns
   accept either a key or a definition map."
  (:require [clojure.string :as str]
            [patterns.conversions :as conv]))
(def ^:private abbreviations
  "Full-word -> abbreviation pairs used to auto-generate aliases.
   Transcribed from upstream *abbreviations*."
  [["MAJOR" "MAJ"]
   ["MINOR" "MIN"]
   ["AUGMENTED" "AUG"]
   ["DOMINANT" "DOM"]
   ["DIMINISHED" "DIM"]
   ["SUSPENDED" "SUS"]
   ["TRIAD" "TRI"]
   ["OTONALITY" "O"]
   ["UTONALITY" "U"]
   ["PENTATONIC" "PENT"]
   ["HARMONIC" "HARM"]])

(defn- canon-key
  "Normalize a name (string, keyword, or symbol) to a canonical keyword."
  [x]
  (keyword (-> (str (if (keyword? x) (name x) x))
               (str/lower-case)
               (str/replace #"[\s_/,]+" "-")
               (str/replace #"[^a-z0-9-]+" "")
               (str/replace #"--+" "-")
               (str/replace #"^-|-$" ""))))

(defn- subsets
  "All subsets of COLL, from largest to smallest."
  [coll]
  (if (empty? coll)
    [[]]
    (let [rest-subs (subsets (rest coll))]
      (concat (map #(cons (first coll) %) rest-subs) rest-subs))))

(defn- replace-first
  "Replace the first occurrence of OLD with NEW in S."
  [^String s ^String old ^String new]
  (let [i (.indexOf s old)]
    (if (neg? i)
      s
      (str (subs s 0 i) new (subs s (+ i (count old)))))))

(defn- generate-aliases
  "Auto-generate alias keywords for canonical key K, mirroring upstream
   generate-aliases: every combination of applicable abbreviations, each
   with and without spaces."
  [k]
  (let [up (str/upper-case (name k))
        found (filterv #(.contains ^String up ^String (first %)) abbreviations)]
    (into #{}
          (mapcat (fn [combo]
                    (let [s (reduce (fn [acc [full abbr]]
                                      (replace-first acc full abbr))
                                    up combo)]
                      [(canon-key s)
                       (canon-key (str/replace s " " ""))]))
                  (subsets found)))))

(defn- register!
  "Assoc canonical KEY -> DEF in REG, then add ALIASES plus generated
   aliases as indirections (alias -> KEY). First registration wins for
   aliases pointing at a different definition."
  [reg def-map key aliases]
  (swap! reg assoc key def-map)
  (doseq [a (distinct (concat aliases (generate-aliases key)))]
    (when (and (not= a key) (not (contains? @reg a)))
      (swap! reg assoc a key))))

(defn- resolve-reg
  "Follow alias indirections in REG for K; return the definition map or nil."
  [reg k]
  (loop [v (get @reg k)]
    (cond (nil? v) nil
          (map? v) v
          :else (recur (get @reg v)))))

(defn- as-key
  "Normalize a keyword/string lookup to a canonical key."
  [x]
  (canon-key x))

;;; tunings

;; Registry: canonical key -> tuning map, plus alias key -> canonical key.
(defonce ^:private tuning-reg (atom {}))

(defn define-tuning
  "Define a tuning NAME (string or keyword) with PITCHES (semitone offsets
   from the root) and OCTAVE-RATIO, plus optional explicit ALIASES.
   Returns the tuning map."
  ([name pitches octave-ratio]
   (define-tuning name pitches octave-ratio nil))
  ([name pitches octave-ratio aliases]
   (let [key (as-key name)
         m {:name (if (string? name) name (str (name name)))
            :key key
            :pitches (vec pitches)
            :octave-ratio octave-ratio}]
     (register! tuning-reg m key (map as-key aliases))
     m)))

(defn all-tunings
  "All defined tuning maps, sorted by name."
  []
  (->> @tuning-reg vals (filter map?) (sort-by :name) vec))

(defn tuning
  "Look up a tuning by keyword/string key or alias; maps pass through.
   Returns nil when unknown."
  [x]
  (cond (map? x) (when (:pitches x) x)
        (or (keyword? x) (string? x)) (resolve-reg tuning-reg (as-key x))
        :else nil))

(defn tuning-name
  "Full name of tuning map or key."
  [t]
  (:name (if (map? t) t (tuning t))))

(defn tuning-pitches
  "Semitone offsets of tuning map or key."
  [t]
  (:pitches (if (map? t) t (tuning t))))

(defn tuning-octave-ratio
  "Octave ratio of tuning map or key."
  [t]
  (:octave-ratio (if (map? t) t (tuning t))))

(defn tuning-steps-per-octave
  "Number of pitches per octave of tuning map or key."
  [t]
  (count (tuning-pitches t)))

;;; scales

;; Registry: canonical key -> scale map, plus alias key -> canonical key.
(defonce ^:private scale-reg (atom {}))

(defn define-scale
  "Define a scale NAME with NOTES (degree semitone offsets) in tuning
   TUNING (default :et12), plus optional explicit ALIASES.
   Returns the scale map."
  ([name notes]
   (define-scale name notes :et12 nil))
  ([name notes tuning]
   (define-scale name notes tuning nil))
  ([name notes tuning aliases]
   (let [key (as-key name)
         m {:name (if (string? name) name (str (name name)))
            :key key
            :notes (vec notes)
            :tuning (as-key tuning)}]
     (register! scale-reg m key (map as-key aliases))
     m)))

(defn all-scales
  "All defined scale maps, sorted by name."
  []
  (->> @scale-reg vals (filter map?) (sort-by :name) vec))

(defn scale
  "Look up a scale by keyword/string key or alias; maps pass through.
   Returns nil when unknown."
  [x]
  (cond (map? x) (when (:notes x) x)
        (or (keyword? x) (string? x)) (resolve-reg scale-reg (as-key x))
        :else nil))

(defn scale-name
  "Full name of scale map or key."
  [s]
  (:name (if (map? s) s (scale s))))

(defn scale-notes
  "Degree semitone offsets of scale map or key."
  [s]
  (:notes (if (map? s) s (scale s))))

(defn scale-tuning
  "Tuning map of scale map or key (nil when the tuning is unknown)."
  [s]
  (let [sc (if (map? s) s (scale s))]
    (when sc (tuning (:tuning sc)))))

(defn scale-steps-per-octave
  "Pitches per octave of the scale's tuning (12 when unknown)."
  [s]
  (let [t (scale-tuning s)]
    (if t (count (:pitches t)) 12)))

(defn scale-midinotes
  "MIDI note numbers of SCALE with root ROOT (default :c) at OCTAVE
   (default 4). OCTAVE may also be a [lo hi] range vector or :all (0-9).
   Mirrors upstream scale-midinotes: midinote = note + root + octave*12."
  ([] (scale-midinotes :major :c 4))
  ([s] (scale-midinotes s :c 4))
  ([s root octave]
   (let [notes (scale-notes s)
         root-n (conv/note-chromatic-index (or root :c))]
     (cond (= octave :all)
           (vec (mapcat #(scale-midinotes s root [%]) (range 0 10)))

           (and (sequential? octave) (= 2 (count octave)))
           (vec (mapcat #(scale-midinotes s root %) (range (first octave) (inc (second octave)))))

           :else
           (mapv #(+ % root-n (* (long octave) 12)) notes)))))

;;; chords

;; Registry: canonical key -> chord map, plus alias key -> canonical key.
(defonce ^:private chord-reg (atom {}))

(defn define-chord
  "Define a chord NAME with INDEXES (scale-degree indexes; accidentals as
   strings like \"4#\", \"2b\", \"6bb\") over scale SCALE, plus optional
   explicit ALIASES. Returns the chord map."
  ([name indexes scale]
   (define-chord name indexes scale nil))
  ([name indexes scale aliases]
   (let [key (as-key name)
         m {:name (if (string? name) name (str (name name)))
            :key key
            :indexes (vec indexes)
            :scale (as-key scale)}]
     (register! chord-reg m key (map as-key aliases))
     m)))

(defn all-chords
  "All defined chord maps, sorted by name."
  []
  (->> @chord-reg vals (filter map?) (sort-by :name) vec))

(defn chord
  "Look up a chord by keyword/string key or alias; maps pass through.
   Returns nil when unknown."
  [x]
  (cond (map? x) (when (:indexes x) x)
        (or (keyword? x) (string? x)) (resolve-reg chord-reg (as-key x))
        :else nil))

(defn chord-name
  "Full name of chord map or key."
  [c]
  (:name (if (map? c) c (chord c))))

(defn chord-scale
  "Scale key the chord is derived from."
  [c]
  (:scale (if (map? c) c (chord c))))

(defn chord-indexes
  "Scale-degree indexes of chord map or key."
  [c]
  (:indexes (if (map? c) c (chord c))))

(defn- parse-index
  "Split an index (number or \"4#\"/\"2b\"/\"6bb\" designator) into
   [base accidental-offset], mirroring upstream index-and-offset."
  [idx]
  (cond (number? idx) [(long idx) 0]
        (or (keyword? idx) (string? idx) (symbol? idx))
        (let [s (if (keyword? idx) (name idx) (str idx))
              m (re-matches #"^(-?\d+)([#b]*)$" s)]
          (when-not m
            (throw (ex-info "invalid chord index" {:index idx})))
          [(Long/parseLong (nth m 1))
           (+ (count (filter #{\#} (nth m 2)))
              (- (count (filter #{\b} (nth m 2)))))])
        :else (throw (ex-info "invalid chord index" {:index idx}))))

(defn chord-notes
  "Semitone offsets of CHORD's tones within its scale.
   Mirrors upstream chord-notes."
  [c]
  (let [ch (if (map? c) c (chord c))
        notes (scale-notes (:scale ch))]
    (mapv (fn [idx]
            (let [[base off] (parse-index idx)]
              (+ (nth notes base) off)))
          (:indexes ch))))

(defn chord-midinotes
  "MIDI note numbers of CHORD with ROOT (default 0) at OCTAVE (default 5).
   Mirrors upstream chord-midinotes."
  ([c] (chord-midinotes c 0 5))
  ([c root] (chord-midinotes c root 5))
  ([c root octave]
   (let [base (conv/note-midinote (conv/note-chromatic-index (or root 0))
                                  :octave (long octave))]
     (mapv #(+ base %) (chord-notes c)))))

;;; scale-aware pitch entry points

(defn scale-descriptor
  "Resolve S to {:notes [...] :steps-per-octave n :octave-ratio r} for the
   conversions namespace scale-lookup hook. Accepts keys, strings, scale
   maps, and raw note lists; returns nil when unresolvable."
  [s]
  (cond (nil? s) nil
        (sequential? s) {:notes (vec s) :steps-per-octave 12 :octave-ratio 2.0}
        :else
        (let [sc (if (map? s) (when (:notes s) s) (scale s))]
          (when sc
            (let [t (tuning (:tuning sc))]
              {:notes (vec (:notes sc))
               :steps-per-octave (if t (count (:pitches t)) 12)
               :octave-ratio (if t (:octave-ratio t) 2.0)})))))

(defn degree-midinote
  "MIDI note of 0-based DEGREE in SCALE (default :major) with ROOT
   (default 0) at OCTAVE (default 5). Degrees wrap past the scale length
   into neighbouring octaves. Accepts positional
   [degree scale root octave] or keyword args
   [degree :scale s :root r :octave o]."
  [degree & args]
  (let [{:keys [sc rt oc]}
        (if (and (seq args)
                 (keyword? (first args))
                 (even? (count args))
                 (every? keyword? (take-nth 2 args)))
          (let [m (apply hash-map args)]
            {:sc (get m :scale :major)
             :rt (get m :root 0)
             :oc (get m :octave 5)})
          (let [[s r o] args]
            {:sc (if (nil? s) :major s)
             :rt (if (nil? r) 0 r)
             :oc (if (nil? o) 5 o)}))]
    (conv/degree-midinote degree
                          :root rt :octave oc :scale sc
                          :lookup scale-descriptor)))

;;; tuning data (transcribed from upstream; ratio lists are converted with
;;; ratio-midi exactly as upstream's loader does)

(defn- mul-iota
  " pitches (* i MUL) for i in 0..N-1 (upstream mul-iota)."
  [mul n]
  (mapv #(* % mul) (range n)))

(defn- rm
  "Convert frequency RATIOS to MIDI-note offsets (upstream ratio-midi)."
  [ratios]
  (mapv conv/ratio-midi ratios))

(def ^:private tuning-data
  [["Equal Temperament 12" (vec (range 12)) 2 [:et12]]
   ["Pythagorean" (rm [1 256/243 9/8 32/27 81/64 4/3 729/512 3/2 128/81 27/16 16/9 243/128]) 2 [:pyth]]
   ["5-Limit Just Intonation" (rm [1 16/15 9/8 6/5 5/4 4/3 45/32 3/2 8/5 5/3 9/5 15/8]) 2 [:just]]
   ["Septimal Tritone Just Intonation" (rm [1 16/15 9/8 6/5 5/4 4/3 7/5 3/2 8/5 5/3 9/5 15/8]) 2 [:sept1]]
   ["7-Limit Just Intonation" (rm [1 16/15 9/8 6/5 5/4 4/3 7/5 3/2 8/5 5/3 7/4 15/8]) 2 [:sept2]]
   ["Meantone, 1/4 Syntonic Comma" [0 0.755 1.93 3.105 3.86 5.035 5.79 6.965 7.72 8.895 10.07 10.82] 2 [:mean4]]
   ["Meantone, 1/5 Pythagorean Comma" [0 0.804 1.944 3.084 3.888 5.028 5.832 6.972 7.776 8.916 10.056 10.86] 2 [:mean5]]
   ["Meantone, 1/6 Pythagorean Comma" [0 0.86 1.96 3.06 3.92 5.02 5.88 6.98 7.84 8.94 10.04 10.9] 2 [:mean6]]
   ["Kirnberger III" (rm [1 256/243 (/ (Math/sqrt 5.0) 2) 32/27 5/4 4/3 45/32 (Math/pow 5.0 0.25) 128/81 (/ (Math/pow 5.0 0.75) 2) 16/9 15/8]) 2 [:kirnberger]]
   ["Werckmeister III" [0 0.92 1.93 2.94 3.915 4.98 5.9 6.965 7.93 8.895 9.96 10.935] 2 [:werckmeister]]
   ["Vallotti" [0 0.94135 1.9609 2.98045 3.92180 5.01955 5.9218 6.98045 7.9609 8.94135 10 10.90225] 2 nil]
   ["Young" [0 0.9 1.96 2.94 3.92 4.98 5.88 6.98 7.92 8.94 9.96 10.9] 2 nil]
   ["Mayumi Reinhard" (rm [1 14/13 13/12 16/13 13/10 18/13 13/9 20/13 13/8 22/13 13/7 208/105]) 2 [:reinhard]]
   ["Wendy Carlos Harmonic" (rm [1 17/16 9/8 19/16 5/4 21/16 11/8 3/2 13/8 27/16 7/4 15/8]) 2 [:wcharm]]
   ["Wendy Carlos Super Just" (rm [1 17/16 9/8 6/5 5/4 4/3 11/8 3/2 13/8 5/3 7/4 15/8]) 2 [:wcsj]]
   ["Chinese Shi-er-lu scale" (rm [1 2187/2048 9/8 19683/16384 81/64 177147/131072 729/612 3/2 6561/4096 27/16 59049/32768 243/128]) 2 [:lu]]
   ["Equal Temperament 19" (mul-iota (/ 12 19) 19) 2 [:et19]]
   ["Equal Temperament 22" (mul-iota (/ 6 11) 22) 2 [:et22]]
   ["Equal Temperament 24" (mul-iota (/ 1 2) 24) 2 [:et24]]
   ["Equal Temperament 31" (mul-iota (/ 12 31) 31) 2 [:et31]]
   ["Equal Temperament 41" (mul-iota (/ 12 41) 41) 2 [:et41]]
   ["Equal Temperament 53" (mul-iota (/ 12 53) 53) 2 [:et53]]
   ["Ben Johnston" (rm [1 25/24 135/128 16/15 10/9 9/8 75/64 6/5 5/4 81/64 32/25 4/3 27/20 45/32 36/25 3/2 25/16 8/5 5/3 27/16 225/128 16/9 9/5 15/8 48/25]) 2 [:johnston]]
   ["Harry Partch" (rm [1 81/80 33/32 21/20 16/15 12/11 11/10 10/9 9/8 8/7 7/6 32/27 6/5 11/9 5/4 14/11 9/7 21/16 4/3 27/20 11/8 7/5 10/7 16/11 40/27 3/2 32/21 14/9 11/7 8/5 18/11 5/3 27/16 12/7 7/4 16/9 9/5 20/11 11/6 15/8 40/21 64/33 160/81]) 2 [:partch]]
   ["Jon Catler" (rm [1 33/32 16/15 9/8 8/7 7/6 6/5 128/105 16/13 5/4 21/16 4/3 11/8 45/32 16/11 3/2 8/5 13/8 5/3 27/16 7/4 16/9 24/13 15/8]) 2 [:catler]]
   ["John Chalmers" (rm [1 21/20 16/15 9/8 7/6 6/5 5/4 21/16 4/3 7/5 35/24 3/2 63/40 8/5 5/3 7/4 9/5 28/15 63/32]) 2 [:chalmers]]
   ["Lou Harrison" (rm [1 16/15 10/9 8/7 7/6 6/5 5/4 4/3 17/12 3/2 8/5 5/3 12/7 7/4 9/5 15/8]) 2 [:harrison]]
   ["Sruti" (rm [1 256/243 16/15 10/9 9/8 32/27 6/5 5/4 81/64 4/3 27/20 45/32 729/512 3/2 128/81 8/5 5/3 27/16 16/9 9/5 15/8 243/128]) 2 [:sruti]]
   ["Wilfrid Perret" (rm [1 21/20 35/32 9/8 7/6 6/5 5/4 21/16 4/3 7/5 35/24 3/2 63/40 8/5 5/3 7/4 9/5 15/8 63/32]) 2 [:perret]]
   ["Michael Harrison 24 tone 7-limit" (rm [1 28/27 135/128 16/15 243/224 9/8 8/7 7/6 32/27 6/5 135/112 5/4 81/64 9/7 21/16 4/3 112/81 45/32 64/45 81/56 3/2 32/21 14/9 128/81 8/5 224/135 5/3 27/16 12/7 7/4 16/9 15/8 243/128 27/14]) 2 [:michaelharrison]]
   ["Harmonic Series 24" (rm (range 1 25)) 2 [:harmonic]]
   ["Bohlen-Pierce" (mul-iota (/ (conv/ratio-midi 3) 13) 12) 3 [:bp]]
   ["Wendy Carlos Alpha" (mul-iota 0.78 14) (conv/midi-ratio (* 15 0.78)) [:wcalpha]]
   ["Wendy Carlos Beta" (mul-iota 0.638 18) (conv/midi-ratio (* 19 0.638)) [:wcbeta]]
   ["Wendy Carlos Gamma" (mul-iota 0.351 33) (conv/midi-ratio (* 34 0.351)) [:wcgamma]]])

(doseq [[n pitches ratio aliases] tuning-data]
  (define-tuning n pitches ratio aliases))

;;; scale data (transcribed from upstream; default tuning :et12)

(def ^:private scale-data
  [["Minor Pentatonic" [0 3 5 7 10] :et12 [:pentatonic]]
   ["Major Pentatonic" [0 2 4 7 9] :et12 nil]
   ["Ritusen" [0 2 5 7 9] :et12 nil]
   ["Egyptian" [0 2 5 7 10] :et12 nil]
   ["Kumoi" [0 2 3 7 9] :et12 nil]
   ["Hirajoshi" [0 2 3 7 8] :et12 nil]
   ["Iwato" [0 1 5 6 10] :et12 nil]
   ["Chinese" [0 4 6 7 11] :et12 nil]
   ["Indian" [0 4 5 7 10] :et12 nil]
   ["Pelog" [0 1 3 7 8] :et12 nil]
   ["Prometheus" [0 2 4 6 11] :et12 nil]
   ["Scriabin" [0 1 4 7 9] :et12 nil]
   ["Gong" [0 2 4 7 9] :et12 nil]
   ["Shang" [0 2 5 7 10] :et12 nil]
   ["Jiao" [0 3 5 8 10] :et12 nil]
   ["Zhi" [0 2 5 7 9] :et12 nil]
   ["Yu" [0 3 5 7 10] :et12 nil]
   ["Whole Tone" [0 2 4 6 8 10] :et12 [:whole]]
   ["Augmented" [0 3 4 7 8 11] :et12 nil]
   ["Augmented 2" [0 1 4 5 8 9] :et12 nil]
   ["Partch Otonality 1" [0 8 14 20 25 34] :partch [:partcho1]]
   ["Partch Otonality 2" [0 7 13 18 27 35] :partch [:partcho2]]
   ["Partch Otonality 3" [0 6 12 21 29 36] :partch [:partcho3]]
   ["Partch Otonality 4" [0 5 15 23 30 37] :partch [:partcho4]]
   ["Partch Otonality 5" [0 10 18 25 31 38] :partch [:partcho5]]
   ["Partch Otonality 6" [0 9 16 22 28 33] :partch [:partcho6]]
   ["Partch Utonality 1" [0 9 18 23 29 35] :partch [:partchu1]]
   ["Partch Utonality 2" [0 8 16 25 30 36] :partch [:partchu2]]
   ["Partch Utonality 3" [0 7 14 22 31 37] :partch [:partchu3]]
   ["Partch Utonality 4" [0 6 13 20 28 38] :partch [:partchu4]]
   ["Partch Utonality 5" [0 5 12 18 25 33] :partch [:partchu5]]
   ["Partch Utonality 6" [0 10 15 21 27 34] :partch [:partchu6]]
   ["Hex Major 7" [0 2 4 7 9 11] :et12 nil]
   ["Hex Dorian" [0 2 3 5 7 10] :et12 nil]
   ["Hex Phrygian" [0 1 3 5 8 10] :et12 nil]
   ["Hex Sus" [0 2 5 7 9 10] :et12 nil]
   ["Hex Major 6" [0 2 4 5 7 9] :et12 nil]
   ["Hex Aeolian" [0 3 5 7 8 10] :et12 nil]
   ["Major" [0 2 4 5 7 9 11] :et12 nil]
   ["Ionian" [0 2 4 5 7 9 11] :et12 nil]
   ["Dorian" [0 2 3 5 7 9 10] :et12 nil]
   ["Phrygian" [0 1 3 5 7 8 10] :et12 nil]
   ["Lydian" [0 2 4 6 7 9 11] :et12 nil]
   ["Mixolydian" [0 2 4 5 7 9 10] :et12 nil]
   ["Aeolian" [0 2 3 5 7 8 10] :et12 nil]
   ["Natural Minor" [0 2 3 5 7 8 10] :et12 [:minor]]
   ["Locrian" [0 1 3 5 6 8 10] :et12 nil]
   ["Harmonic Minor" [0 2 3 5 7 8 11] :et12 nil]
   ["Harmonic Major" [0 2 4 5 7 8 11] :et12 nil]
   ["Melodic Minor" [0 2 3 5 7 9 11] :et12 nil]
   ["Melodic Minor Descending" [0 2 3 5 7 8 10] :et12 [:melodicminordesc]]
   ["Melodic Major" [0 2 4 5 7 8 10] :et12 nil]
   ["Bartok" [0 2 4 5 7 8 10] :et12 nil]
   ["Hindu" [0 2 4 5 7 8 10] :et12 nil]
   ["Todi" [0 1 3 6 7 8 11] :et12 nil]
   ["Purvi" [0 1 4 6 7 8 11] :et12 nil]
   ["Marva" [0 1 4 6 7 9 11] :et12 nil]
   ["Bhairav" [0 1 4 5 7 8 11] :et12 nil]
   ["Ahirbhairav" [0 1 4 5 7 9 10] :et12 nil]
   ["Super Locrian" [0 1 3 4 6 8 10] :et12 nil]
   ["Romanian Minor" [0 2 3 6 7 9 10] :et12 nil]
   ["Hungarian Minor" [0 2 3 6 7 8 11] :et12 nil]
   ["Neapolitan Minor" [0 1 3 5 7 8 11] :et12 nil]
   ["Enigmatic" [0 1 4 6 8 10 11] :et12 nil]
   ["Spanish" [0 1 4 5 7 8 10] :et12 nil]
   ["Leading Whole Tone" [0 2 4 6 8 10 11] :et12 [:leadingwhole]]
   ["Lydian Minor" [0 2 4 6 7 8 10] :et12 nil]
   ["Neapolitan Major" [0 1 3 5 7 9 11] :et12 nil]
   ["Locrian Major" [0 2 4 5 6 8 10] :et12 nil]
   ["Diminished" [0 1 3 4 6 7 9 10] :et12 nil]
   ["Diminished 2" [0 2 3 5 6 8 9 11] :et12 nil]
   ["Chromatic" [0 1 2 3 4 5 6 7 8 9 10 11] :et12 nil]
   ["Chromatic 24" (vec (range 24)) :et24 nil]
   ["Ajam" [0 4 8 10 14 18 22] :et24 nil]
   ["Jiharkah" [0 4 8 10 14 18 21] :et24 nil]
   ["Shawq Afza" [0 4 8 10 14 16 22] :et24 nil]
   ["Sikah" [0 3 7 11 14 17 21] :et24 nil]
   ["Sikah Descending" [0 3 7 11 13 17 21] :et24 [:sikahdesc]]
   ["Huzam" [0 3 7 9 15 17 21] :et24 nil]
   ["Iraq" [0 3 7 10 13 17 21] :et24 nil]
   ["Bastanikar" [0 3 7 10 13 15 21] :et24 nil]
   ["Mustar" [0 5 7 11 13 17 21] :et24 nil]
   ["Bayati" [0 3 6 10 14 16 20] :et24 nil]
   ["Karjighar" [0 3 6 10 12 18 20] :et24 nil]
   ["Husseini" [0 3 6 10 14 17 21] :et24 nil]
   ["Nahawand" [0 4 6 10 14 16 22] :et24 nil]
   ["Nahawand Descending" [0 4 6 10 14 16 20] :et24 [:nahawanddesc]]
   ["Farahfaza" [0 4 6 10 14 16 20] :et24 nil]
   ["Murassah" [0 4 6 10 12 18 20] :et24 nil]
   ["Ushaq Mashri" [0 4 6 10 14 17 21] :et24 nil]
   ["Rast" [0 4 7 10 14 18 21] :et24 nil]
   ["Rast Descending" [0 4 7 10 14 18 20] :et24 [:rastdesc]]
   ["Suznak" [0 4 7 10 14 16 22] :et24 nil]
   ["Nairuz" [0 4 7 10 14 17 20] :et24 nil]
   ["Yakah" [0 4 7 10 14 18 21] :et24 nil]
   ["Yakah Descending" [0 4 7 10 14 18 20] :et24 [:yakahdesc]]
   ["Mahur" [0 4 7 10 14 18 22] :et24 nil]
   ["Hijaz" [0 2 8 10 14 17 20] :et24 nil]
   ["Hijaz Descending" [0 2 8 10 14 16 20] :et24 [:hijazdesc]]
   ["Zanjaran" [0 2 8 10 14 18 20] :et24 nil]
   ["hijazKar" [0 2 8 10 14 16 22] :et24 nil]
   ["Saba" [0 3 6 8 12 16 20] :et24 nil]
   ["Zamzam" [0 2 6 8 14 16 20] :et24 nil]
   ["Kurd" [0 2 6 10 14 16 20] :et24 nil]
   ["Kijaz Kar Kurd" [0 2 8 10 14 16 22] :et24 nil]
   ["Nawa Athar" [0 4 6 12 14 16 22] :et24 nil]
   ["Nikriz" [0 4 6 12 14 18 20] :et24 nil]
   ["Athar Kurd" [0 2 6 12 14 16 22] :et24 nil]])

(doseq [[n notes tun aliases] scale-data]
  (define-scale n notes tun aliases))

;;; chord data (transcribed from upstream; accidental indexes are strings)

(def ^:private chord-data
  [["Augmented Triad" [0 2 "4#"] :major [:augtri]]
   ["Diminished Triad" [0 "2b" "4b"] :major [:dimtri]]
   ["Major Triad" [0 2 4] :major [:major :maj :majtri]]
   ["Minor Triad" [0 "2b" 4] :major [:minor :min :mintri]]
   ["Suspended 2nd Triad" [0 1 4] :major [:sus2tri]]
   ["Suspended 4th Triad" [0 3 4] :major [:sus4tri]]
   ["Major 6th" [0 2 4 5] :major [:maj6]]
   ["Minor 6th" [0 "2b" 4 5] :major [:min6]]
   ["Augmented 7th" [0 2 "4#" "6b"] :major [:aug7]]
   ["Augmented Major 7th" [0 2 "4#" 6] :major [:augmaj7]]
   ["Half Diminished 7th" [0 "2b" "4b" "6b"] :major [:halfdim7]]
   ["Diminished 7th" [0 "2b" "4b" "6bb"] :major [:dim7]]
   ["Dominant 7th" [0 2 4 "6b"] :major [:dom7]]
   ["Dominant 7th Suspended 4th" [0 3 4 "6b"] :major [:dom7sus4]]
   ["Major 7th" [0 2 4 6] :major [:maj7]]
   ["Major 7th Suspended 2nd" [0 1 4 6] :major [:maj7sus2]]
   ["Major 7th Suspended 4th" [0 3 4 6] :major [:maj7sus4]]
   ["Minor 7th" [0 "2b" 4 "6b"] :major [:min7]]
   ["Minor Major 7th" [0 "2b" 4 6] :major [:minmaj7]]])

(doseq [[n indexes sc aliases] chord-data]
  (define-chord n indexes sc aliases))

;;; Wire the conversions scale-lookup hook (one-directional: scales
;;; requires conversions, never the reverse, so no require cycle).
(conv/set-scale-lookup! scale-descriptor)
