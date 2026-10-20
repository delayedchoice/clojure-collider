(ns patterns.util
  "Shared pattern utilities: end-of-pattern marker, seeded RNG, and
   trivial numeric helpers (transpose, rerange).")

(def eop
  "End-of-pattern marker. Never yielded inside next-n results."
  :patterns.util/eop)

(defn eop?
  "True when `x` is the end-of-pattern marker."
  [x]
  (= eop x))

(def ^:dynamic *rng*
  "Dynamic RNG used by all random pattern helpers. Rebind or seed for
   deterministic output."
  (java.util.Random.))

(defn seed-rng!
  "Seed the dynamic RNG in place; returns the RNG."
  [n]
  (.setSeed ^java.util.Random *rng* (long n))
  *rng*)

(defn rand-range
  "Uniform random in [lo, hi)."
  [lo hi]
  (let [lo (double lo) hi (double hi)]
    (+ lo (* (.nextDouble ^java.util.Random *rng*) (- hi lo)))))

(defn rand-exp
  "Exponential random in [lo, hi]. Asserts nonzero, same-sign bounds."
  [lo hi]
  (let [lo (double lo) hi (double hi)]
    (assert (and (not (zero? lo)) (not (zero? hi))
                 (or (and (pos? lo) (pos? hi))
                     (and (neg? lo) (neg? hi))))
            (str "rand-exp bounds must be nonzero and share a sign, got " lo " " hi))
    (* lo (Math/pow (/ hi lo) (.nextDouble ^java.util.Random *rng*)))))

(defn rand-gauss
  "Gaussian random with MEAN and standard deviation DEV."
  [mean dev]
  (+ (double mean) (* (double dev) (.nextGaussian ^java.util.Random *rng*))))

(defn rand-int-range
  "Uniform random integer in [lo, hi] inclusive."
  [lo hi]
  (let [lo (long lo) hi (long hi)]
    (assert (<= lo hi) (str "rand-int-range requires lo <= hi, got " lo " " hi))
    (+ lo (long (.nextInt ^java.util.Random *rng* (inc (- hi lo)))))))

(defn transpose
  "Transpose FREQ by SEMITONES and OCTAVES."
  ([freq] (transpose freq 0 0))
  ([freq semitones] (transpose freq semitones 0))
  ([freq semitones octaves]
   (let [semi (+ (double semitones) (* (double octaves) 12.0))]
     (* (double freq) (Math/pow 2.0 (/ semi 12.0))))))

(defn- from-range
  "Unmap INPUT from [lo, hi] (warp :linear/:exp) to [0, 1]."
  [input [lo hi warp] ]
  (let [input (double input) lo (double lo) hi (double hi)]
    (case (or warp :linear)
      (:lin :linear 0) (/ (- input lo) (- hi lo))
      (:exp :exponential 1) (/ (Math/log (/ input lo)) (Math/log (/ hi lo)))
      (throw (ex-info "unsupported warp" {:warp warp})))))

(defn- to-range
  "Map INPUT in [0, 1] to [lo, hi] (warp :linear/:exp)."
  [input [lo hi warp]]
  (let [input (double input) lo (double lo) hi (double hi)]
    (case (or warp :linear)
      (:lin :linear 0) (+ lo (* input (- hi lo)))
      (:exp :exponential 1) (* lo (Math/pow (/ hi lo) input))
      (throw (ex-info "unsupported warp" {:warp warp})))))

(defn rerange
  "Remap INPUT from one [lo hi warp] range to another."
  [input from to]
  (to-range (from-range input from) to))
