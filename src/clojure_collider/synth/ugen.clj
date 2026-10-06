(ns clojure-collider.synth.ugen
  (:require [clojure.string :as str])
  (:import [java.io DataOutputStream]))

(def ^:dynamic *synthdef* nil)
(def inf Float/POSITIVE_INFINITY)

;; rates
(defn rate-number [r]
  (case r
    :audio 2 :control 1 :scalar 0 :demand 3
    (throw (ex-info "unknown rate" {:r r}))))
(defn number-rate [n]
  (case n
    2 :audio 1 :control 0 :scalar 3 :demand
    (throw (ex-info "unknown rate number" {:n n}))))



(defmulti rate class)
(defmethod rate :default [x] (throw (ex-info "can't get rate" {:x x})))
(defmethod rate java.lang.Number [_] :scalar)
;; CL ugen.lisp:95-98 sorts rate-name strings ascending and takes first,
;; so :audio wins over :control > :demand > :scalar. Preserve that priority
;; (previous {:scalar 0 ... :demand 3}+last gave :demand wins — wrong).
(def ^:private rate-priority {:audio 0 :control 1 :demand 2 :scalar 3})
(defmethod rate clojure.lang.IPersistentVector [v]
  (if (empty? v) :scalar
      (apply min-key rate-priority (map rate v))))
(defmethod rate clojure.lang.Sequential [s]
  (rate (vec s)))
(defn sort-by-rate-desc
  "Rate-descending input order with REVERSED ties. Upstream sum3/sum4 new1
   sort by rate-number via CL `sort`, which is not stable: SBCL reverses
   equal keys (verified n=2..5, full + partial ties). Byte parity of Sum3/4
   inputs depends on it; pinned to observed SBCL behavior."
  [inputs]
  (->> inputs
       (map-indexed vector)
       (sort-by (fn [[i x]] [(rate-number (rate x)) i])
                (fn [a b] (compare b a)))
       (mapv second)))

;; floatfy
(defn floatfy [x]
  (cond
    (number? x) (float x)
    (string? x) x
    (keyword? x) x
    (sequential? x) (map floatfy x)
    :else (float x)))

(defn act [k]
  (case k
    :no-action 0 :pause 1 :free 2 :free-and-before 3 :free-and-after 4
    :free-and-group-before 5 :free-and-group-after 6 :free-upto-this 7 :free-from-this-on 8
    :free-pause-before 9 :free-pause-after 10 :free-and-group-before-deep 11 :free-and-group-after-deep 12
    :free-children 13 :free-group 14
    (throw (ex-info "unknown act" {:act k}))))

;; UGen records. :ugen-class is the upstream CL class (ugen/pure-ugen/
;; unary-operator/binary-operator/muladd/sum3/sum4/control/abstract-out/
;; multiout) driving optimize-graph dispatch (notably dead-code elimination).
(defrecord UGen [name rate inputs synth-index special-index signal-range synthdef antecedents descendants width-first-antecedents check-fn ugen-class])
(defrecord NoOutputUGen [name rate inputs synth-index special-index signal-range synthdef antecedents descendants width-first-antecedents check-fn ugen-class])
(defrecord ProxyOutput [source rate signal-range output-index synth-index antecedents descendants width-first-antecedents])
(defrecord MultiOutUGen [name rate inputs synth-index special-index signal-range synthdef antecedents descendants width-first-antecedents channels ugen-class])
(defrecord ControlUGen [name rate inputs synth-index special-index signal-range synthdef antecedents descendants width-first-antecedents channels ugen-class])
(defmethod rate UGen [u] (:rate u))
(defmethod rate NoOutputUGen [u] (:rate u))
(defmethod rate MultiOutUGen [u] (:rate u))
(defmethod rate ControlUGen [u] (:rate u))
(defmethod rate ProxyOutput [u] (:rate u))

(defn ugen-like?
  "Any UGen flavor (UGen/NoOutputUGen/MultiOutUGen/ControlUGen).
   Upstream (typep x 'ugen) covers subclasses; bare (instance? UGen) does not."
  [x]
  (or (instance? UGen x) (instance? NoOutputUGen x)
      (instance? MultiOutUGen x) (instance? ControlUGen x)))

(defn ugen? [x] (ugen-like? x))
(defn proxy-output? [x] (instance? ProxyOutput x))

(defn source [u]
  (if (instance? ProxyOutput u) (:source u) u))
(defn num-outputs [u]
  (cond (instance? MultiOutUGen u) (:channels u)
        (instance? ControlUGen u) (:channels u)
        (instance? NoOutputUGen u) 0
        :else 1))
(defn output-index [u]
  (if (instance? ProxyOutput u) (:output-index u) 0))

;; helpers for synthdef interaction (forward decl)
(declare add-ugen-to-synthdef add-constant-to-synthdef)

(defn add-to-synth [ugen]
  (if *synthdef*
    (add-ugen-to-synthdef *synthdef* ugen)
    ugen))

;; multi-channel expansion (mirrors CL lst-operation/multinew)
(defn- list-ref [lst n] (nth lst (mod n (count lst))))

(defn- lst-operation [lst ctor]
  (letfn [(mulnew [args]
            (let [size (reduce max 0 (map #(if (sequential? %) (count %) 0) args))]
              (if (zero? size)
                (if ctor (apply ctor args) args)
                (let [results (for [i (range size)]
                                (let [newargs (map #(if (sequential? %) (list-ref % i) %) args)]
                                  (mulnew (vec newargs))))]
                  (vec results)))))]
    (mulnew lst)))

(defn multinew-list [ctor cls inputs]
  (lst-operation inputs (fn [& args] (apply ctor cls args))))

(defn multinew [ctor cls & inputs]
  (multinew-list ctor cls (vec inputs)))

(defn construct
  "Build (not add) a UGen record. `cls` is the upstream CL class symbol
   (ugen/pure-ugen/unary-operator/binary-operator/muladd/sum3/sum4/control/
   abstract-out/multiout); it selects the record and is kept as :ugen-class
   for optimize-graph dispatch. Operator ctors (new1) set opcode + computed
   rate per multichannel combo, mirroring ugen.lisp new1 methods."
  [name rate cls check-fn signal-range special-index inputs]
  (let [syn *synthdef*
        flavor (keyword (str/lower-case (clojure.core/name cls)))
        base {:name name :rate rate :synthdef syn :check-fn check-fn :signal-range signal-range
              :synth-index -1 :special-index special-index :antecedents [] :descendants [] :width-first-antecedents []
              :inputs (vec inputs) :ugen-class flavor}]
    (cond (= flavor :abstract-out) (map->NoOutputUGen base)
          (= flavor :nooutput-ugen) (map->NoOutputUGen base)
          (or (= flavor :multiout) (= flavor :multiout-ugen))
          (map->MultiOutUGen base)
          (= flavor :control) (map->ControlUGen base)
          :else (map->UGen base))))

(defn ugen-new [name rate cls check-fn signal-range & inputs]
  (add-to-synth (construct name rate cls check-fn signal-range 0 inputs)))


(def dce-flavors
  "CL classes subject to dead-code elimination (ugen.lisp
   perform-dead-code-elimination via pure-ugen/unary-operator/binary-operator
   optimize-graph methods). Plain ugen/control/output nodes are always kept."
  #{:pure-ugen :unary-operator :binary-operator})

;; topo sort (mirrors synthdef.lisp topo-logical-sort + ugen.lisp init-topo-sort/schedule).
;; UGens are immutable records, so the sort runs on creation indices rather
;; than mutating antecedent/descendant slots. Pop/push discipline is a faithful
;; port: `available` is a stack-front list — newly-ready nodes jump ahead of
;; older ones — so output can differ from creation order (e.g. [A B C(dep A)]
;; schedules as [A C B]). Any topo order would satisfy scsynth, but this keeps
;; byte parity with CL.
(defn- ugen-deps
  "Creation indices `ugen` depends on (via UGen / ProxyOutput inputs)."
  [ugen]
  (into #{}
        (keep (fn [inp]
                (let [i (cond (ugen-like? inp) (:synth-index inp)
                              (instance? ProxyOutput inp) (:synth-index (:source inp)))]
                  (when (integer? i) i)))
              (:inputs ugen))))

(defn graph-edges
  "Dependency maps over vector POSITIONS (not creation indices): [deps
   dependents]. Creation indices translate through current positions, so
   this stays correct after dead-code removal shifts the vector."
  [children]
  (let [pos-of (into {} (map-indexed (fn [pos u] [(:synth-index u) pos]) children))
        deps (mapv (fn [u] (into #{} (keep pos-of (ugen-deps u)))) children)
        dependents (reduce (fn [m [j ds]]
                             (reduce (fn [m d] (update m d (fnil conj #{}) j)) m ds))
                           {} (map-indexed vector deps))]
    [deps dependents]))

(defn topo-sort
  "CL-compatible schedule order for `children` vector. Returns position
   indices in schedule order, producers before consumers."
  [children]
  (let [n (count children)
        [deps dependents] (graph-edges children)
        ready? #(empty? (deps %))]
    (loop [avail (into '() (filter ready? (reverse (range n))))
           rem deps
           out []]
      (if (empty? avail)
        out
        (let [u (first avail)
              [rem avail]
              (reduce (fn [[rem avail] d]
                        (let [s (disj (rem d) u)]
                          [(assoc rem d s)
                           (if (empty? s) (conj avail d) avail)]))
                      [rem (rest avail)]
                      (reverse (sort (get dependents u #{}))))]
          (recur avail rem (conj out u)))))))

(defn make-pstring [s]
  (let [bytes (.getBytes ^String s "UTF-8")
        len (alength bytes)
        out (byte-array (inc len))]
    (aset out 0 (unchecked-byte len))
    (System/arraycopy bytes 0 out 1 len)
    out))

(defn input-spec [ugen synthdef]
  ;; synthdef is the deref'd SynthDef map (callers pass @atom).
  (let [consts (:constants synthdef)]
    (map (fn [inp]
           (if (or (ugen-like? inp) (instance? ProxyOutput inp))
             [(int (:synth-index (source inp))) (int (output-index inp))]
             [-1 (int (.indexOf ^java.util.List consts (floatfy inp)))]))
         (:inputs ugen))))

(defn write-def-ugen-v2 [^UGen ugen synthdef ^DataOutputStream out]
  (.write out (make-pstring (:name ugen)))
  (.writeByte out (rate-number (:rate ugen)))
  (.writeInt out (count (:inputs ugen)))
  (.writeInt out (num-outputs ugen))
  (.writeShort out (short (:special-index ugen 0)))
  (doseq [[a b] (input-spec ugen synthdef)]
    (.writeInt out a)
    (.writeInt out b))
  (dotimes [_ (num-outputs ugen)]
    (.writeByte out (rate-number (:rate ugen)))))

;; rate helpers for inputs
(defn check-same-rate-as-first-input [ugen]
  (when (not= (:rate ugen) (rate (first (:inputs ugen))))
    (throw (ex-info "rate mismatch" {:ugen ugen}))))

(defn check-n-inputs
  "Port of ugen.lisp check-n-inputs: when audio rate, the first N inputs
   must be audio rate (used by panners, generated per-N closures)."
  [n]
  (fn [ugen]
    (when (= :audio (:rate ugen))
      (doseq [i (range n)]
        (when-not (= :audio (rate (nth (:inputs ugen) i)))
          (throw (ex-info (str (:name ugen) "'s input " i " is not audio rate")
                          {:ugen (:name ugen) :index i})))))))

(defn check-when-audio
  "Port of ugen.lisp check-when-audio: when audio rate, input 1 must be audio."
  [ugen]
  (when (and (= :audio (:rate ugen))
             (not= :audio (rate (nth (:inputs ugen) 1))))
    (throw (ex-info (str (:name ugen) "'s input is not audio rate") {:ugen (:name ugen)}))))

(defn when-audio-check-first-input
  "Port of Line.lisp: same-rate check, but only for audio-rate units."
  [ugen]
  (when (= :audio (:rate ugen))
    (check-same-rate-as-first-input ugen)))

(defn peak-check-fn
  "Port of Trig.lisp peak-check-fn: a control-rate Peak over an audio
   input is illegal; otherwise same-rate-as-first-input."
  [ugen]
  (if (and (= :control (:rate ugen)) (= :audio (rate (first (:inputs ugen)))))
    (throw (ex-info "valid inputs" {:ugen (:name ugen)}))
    (check-same-rate-as-first-input ugen)))

(defn check-duty
  "Port of Demand.lisp check-duty: a demand-rate first input constrains
   the reset input to demand/scalar/unit rate."
  [ugen]
  (when (= :demand (rate (first (:inputs ugen))))
    (let [r1 (rate (second (:inputs ugen)))]
      (when-not (contains? #{:demand :scalar (:rate ugen)} r1)
        (throw (ex-info (str "Reset input cannot be " (:rate ugen) " rate")
                        {:ugen (:name ugen) :reset-rate r1}))))))

;; unbubble (ugen.lisp): [] -> nil, [x] -> x, else as-is.
(defn unbubble [x]
  (cond (not (sequential? x)) x
        (zero? (count x)) nil
        (= 1 (count x)) (first x)
        :else x))
(defn multiout-new
  "multiout-ugen new1: channel count held off-inputs in :channels, unit
   added once, per-channel ProxyOutputs returned (unbubbled)."
  [name rate signal-range channels & inputs]
  (when-not (number? channels)
    (throw (ex-info "multiout channels must be a number" {:channels channels})))
  (let [stored (add-to-synth
                (assoc (construct name rate 'multiout-ugen nil signal-range
                                  0 (vec inputs))
                       :channels (int channels)))]
    (unbubble
     (mapv (fn [i]
             (map->ProxyOutput
              {:source stored :rate rate :signal-range signal-range
               :output-index i :synth-index -1
               :antecedents [] :descendants [] :width-first-antecedents []}))
           (range (int channels))))))

;; declare synthdef helpers to avoid circular dep
(defn add-ugen-to-synthdef [synthdef ugen]
  (when (not (:rewrite-in-progress @synthdef))
    (let [s @synthdef
          idx (count (:children s))]
      (swap! synthdef assoc :children (conj (:children s) (assoc ugen :synth-index idx :width-first-antecedents (vec (:width-first-ugens s)))))
      (let [added (last (:children @synthdef))]
        added)))
  ;; return ugen with index
  (let [s @synthdef] (last (:children s))))

(defn add-constant-to-synthdef [synthdef const]
  (let [c (float const)]
    (swap! synthdef update :constants (fn [cs] (if (some #(= % c) cs) cs (conj cs c))))))
