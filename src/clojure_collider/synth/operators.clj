(ns clojure-collider.synth.operators
  "Unary/binary operators -> UnaryOpUGen/BinaryOpUGen, MulAdd/Sum3/Sum4,
   madd/mix/range helpers. Mirrors operators.lisp.
   Naming (DECISIONS.md): Clojure's reader reserves `~` (unquote), so the CL
   `+~` family cannot be spelled. `+ - * / < > <= >= min max abs mod inc dec`
   are overloaded dispatchers (all-numbers -> clojure.core, any-ugen ->
   UGen); free names drop the tilde (sin, floor, expt, ash, round, trunc);
   `== << >> <!` were already legal; `if~` becomes `if*` (`if` is special).
   Deferred: optimize-* graph-surgery passes (need mutable descendants) and
   clip~/prune/exp-range/lin-lin/lin-exp (need Phase-6 Clip/LinExp/Select)."
  (:refer-clojure :exclude [== range + - * / < > <= >= min max abs mod inc dec])
  (:require [clojure-collider.synth.ugen :as ugen]))

;; -- helpers ---------------------------------------------------------------

(defn- num-eq?
  "Numeric = that returns false (not throw) for UGen inputs.
   Upstream equalp never errors on non-numbers; bare == would."
  [x y]
  (and (number? x) (number? y) (clojure.core/== x y)))

(defn- binary-rate
  "ugen.lisp binary-operator new1: first rate iff strictly decreasing numbers."
  [a b]
  (let [na (ugen/rate-number (ugen/rate a))
        nb (ugen/rate-number (ugen/rate b))]
    (if (clojure.core/> na nb) (ugen/rate a) (ugen/rate b))))

(defmacro def-unary-op
  "Define op fn: numbers take the basic-fn path, ugens build UnaryOpUGen
   (special-index opcode, input rate), with multichannel expansion."
  [name basic-fn special-index]
  `(defn ~name [in#]
     (let [ctor# (fn [cls# sp# x#]
                   (if (number? x#)
                     (~basic-fn x#)
                     (ugen/add-to-synth
                      (ugen/construct "UnaryOpUGen" (ugen/rate x#)
                                      'unary-operator
                                      identity :bipolar sp# [x#]))))]
       (ugen/multinew ctor# 'unary-operator ~special-index in#))))

(defmacro def-binary-op
  "Define 2-arg op fn: all-number combos take basic-fn, constant-fold
   clauses (written with a/b) fire next, else BinaryOpUGen per combo."
  [name basic-fn special-index & clauses]
  ;; Explicit gensyms: nested syntax-quotes would not share auto-gensyms.
  (let [in1 (gensym "in1") in2 (gensym "in2")
        ctor (gensym "ctor") cls (gensym "cls") sp (gensym "sp")
        a (gensym "a") b (gensym "b")
        arms (mapcat (fn [[t r]]
                       [(list 'let ['a a 'b b] t)
                        (list 'let ['a a 'b b] r)])
                     clauses)]
    `(defn ~name [~in1 ~in2]
       (let [~ctor (fn [~cls ~sp ~a ~b]
                     (cond (and (number? ~a) (number? ~b)) (~basic-fn ~a ~b)
                           ~@arms
                           :else (ugen/add-to-synth
                                  (ugen/construct "BinaryOpUGen" (binary-rate ~a ~b)
                                                  'binary-operator
                                                  identity :bipolar ~sp [~a ~b]))))]
         (ugen/multinew ~ctor 'binary-operator ~special-index ~in1 ~in2)))))

;; -- unary table (name, numeric fn, SC special-index) ------------------------

(def-unary-op neg #(clojure.core/* -1 %) 0)
(def-unary-op reciprocal #(clojure.core// 1.0 %) 16)
(def-unary-op abs* (fn [a] (if (neg? a) (clojure.core/- a) a)) 5)
(def-unary-op floor (fn [a] (Math/floor (double a))) 9)
(def-unary-op ceil (fn [a] (Math/ceil (double a))) 8)
(def-unary-op frac (fn [a] (clojure.core/- a (Math/rint (double a)))) 10)
(def-unary-op sign (fn [a] (cond (pos? a) 1.0 (neg? a) -1.0 :else 0.0)) 11)
(def-unary-op squared #(clojure.core/* % %) 12)
(def-unary-op cubed #(clojure.core/* % % %) 13)
(def-unary-op sqrt (fn [a] (Math/sqrt (double a))) 14)
(def-unary-op exp (fn [a] (Math/exp (double a))) 15)
(def-unary-op midicps (fn [n] (clojure.core/* 440 (Math/pow 2 (clojure.core// (clojure.core/- n 69) 12.0)))) 17)
(def-unary-op cpsmidi (fn [f] (clojure.core/+ 69 (clojure.core/* 12.0 (clojure.core// (Math/log (clojure.core// f 440)) (Math/log 2))))) 18)
(def-unary-op midiratio (fn [n] (Math/pow 2 (clojure.core/* n 0.083333333333))) 19)
(def-unary-op dbamp (fn [db] (Math/pow 10.0 (clojure.core/* db 0.05))) 21)
(def-unary-op ampdb (fn [amp] (clojure.core/* (clojure.core// (Math/log (double amp)) (Math/log 10.0)) 20)) 22)
(def-unary-op log (fn [n] (Math/log (double n))) 25)
(def-unary-op log2 (fn [n] (clojure.core// (Math/log (double n)) (Math/log 2.0))) 26)
(def-unary-op log10 (fn [n] (clojure.core// (Math/log (double n)) (Math/log 10.0))) 27)
(def-unary-op sin (fn [a] (Math/sin (double a))) 28)
(def-unary-op cos (fn [a] (Math/cos (double a))) 29)
(def-unary-op tan (fn [a] (Math/tan (double a))) 30)
(def-unary-op atan (fn [a] (Math/atan (double a))) 33)
(def-unary-op tanh (fn [a] (Math/tanh (double a))) 36)
(def-unary-op distort (fn [x] (clojure.core// x (clojure.core/+ 1.0 (abs* x)))) 42)
(def-unary-op softclip (fn [x] (let [ax (abs* x)]
                                 (if (clojure.core/<= ax 0.5) x (clojure.core// (clojure.core/- ax 0.25) x)))) 43)

(defn abs [x]
  (if (number? x) (clojure.core/abs x) (abs* x)))

;; -- binary table ------------------------------------------------------------

(def-binary-op add clojure.core/+ 0
  ((num-eq? a 0) b)
  ((num-eq? b 0) a))

(def-binary-op minus clojure.core/- 1
  ((num-eq? a 0) (neg b))
  ((num-eq? b 0) a))

(def-binary-op mul clojure.core/* 2
  ((num-eq? a 0) 0.0)
  ((num-eq? b 0) 0.0)
  ((num-eq? a 1) b)
  ((num-eq? a -1) (neg b))
  ((num-eq? b 1) a)
  ((num-eq? b -1) (neg a)))

(def-binary-op divide (fn [a b] (clojure.core// a b 1.0)) 4
  ((num-eq? b 1.0) a)
  ((num-eq? b -1.0) (neg a)))

(def-binary-op mod* clojure.core/mod 5)
(def-binary-op %round
  (fn [a b]
    (let [q (Math/rint (clojure.core// (double a) (double b)))
          lo (clojure.core/* q b)
          hi (clojure.core/* (clojure.core/inc q) b)]
      (if (clojure.core/< (Math/abs (clojure.core/- (double lo) (double a)))
                          (Math/abs (clojure.core/- (double hi) (double a))))
        lo hi)))
  19)

(defn round
  ([in1] (%round in1 1))
  ([in1 in2] (%round in1 in2)))

(defn mod [a b]
  (if (and (number? a) (number? b)) (clojure.core/mod a b) (mod* a b)))

(def-binary-op trunc (fn [a b] (clojure.core/* b (long (clojure.core// a b)))) 21)
(def-binary-op == clojure.core/== 6)
(def-binary-op lt* clojure.core/< 8)
(def-binary-op gt* clojure.core/> 9)
(def-binary-op lte* clojure.core/<= 10)
(def-binary-op gte* clojure.core/>= 11)

(defn- num-clip [a lo hi]
  (clojure.core/min (clojure.core/max a lo) hi))

(def-binary-op clip2 (fn [a b] (num-clip a (clojure.core/- b) b)) 42)

(defn- num-fold
  "Reflect a into [lo hi]. Triangle period 2r peaking at hi."
  [a lo hi]
  (let [r (clojure.core/- hi lo)]
    (if (zero? r)
      lo
      (clojure.core/+ lo (clojure.core/- r (Math/abs (clojure.core/- (clojure.core/mod (clojure.core/- a lo) (clojure.core/* 2 r)) r)))))))

(def-binary-op fold2 (fn [a b] (num-fold a (clojure.core/- b) b)) 44)

(def-binary-op min* clojure.core/min 12)
(def-binary-op max* clojure.core/max 13)
(def-binary-op expt (fn [a b] (Math/pow (double a) (double b))) 25)
(def-binary-op <! (fn [a _b] a) 46)
(def-binary-op logand (fn [a b] (bit-and (long a) (long b))) 14)
(def-binary-op logior (fn [a b] (bit-or (long a) (long b))) 15)
(def-binary-op << (fn [a b] (bit-shift-left (long a) (long b))) 26)
(def-binary-op >> (fn [a b] (bit-shift-right (long a) (long b))) 27)

(defn ash [in1 in2]
  (if (pos? in2) (<< in1 in2)
      (>> in1 (clojure.core/- in2))))

;; -- overloaded arithmetic (sclang surface) ------------------------------------
;; All-numbers take the clojure.core path (same values/arities); any ugen
;; builds the BinaryOpUGen above, so (+ a b) reads like sclang.

(defn +
  ([] 0)
  ([a] a)
  ([a b] (add a b))
  ([a b & r] (reduce add (list* a b r))))

(defn -
  ([a] (neg a))
  ([a b] (minus a b))
  ([a b & r] (reduce minus (list* a b r))))

(defn *
  ([] 1)
  ([a] a)
  ([a b] (mul a b))
  ([a b & r] (reduce mul (list* a b r))))

(defn /
  ([a] (divide 1 a))
  ([a b] (divide a b))
  ([a b & r] (reduce divide (list* a b r))))

(defn- compare-dispatch [core-f binary-f args]
  (if (every? number? args)
    (apply core-f args)
    (if (clojure.core/== 2 (count args))
      (apply binary-f args)
      (throw (ex-info "ugen comparison is binary" {:args args})))))

(defn < [& args] (compare-dispatch clojure.core/< lt* args))
(defn > [& args] (compare-dispatch clojure.core/> gt* args))
(defn <= [& args] (compare-dispatch clojure.core/<= lte* args))
(defn >= [& args] (compare-dispatch clojure.core/>= gte* args))

(defn min [& args]
  (if (every? number? args)
    (apply clojure.core/min args)
    (if (clojure.core/== 2 (count args))
      (apply min* args)
      (throw (ex-info "ugen min is binary" {:args args})))))

(defn max [& args]
  (if (every? number? args)
    (apply clojure.core/max args)
    (if (clojure.core/== 2 (count args))
      (apply max* args)
      (throw (ex-info "ugen max is binary" {:args args})))))

;; 1+~/1-~ are not spellable (digit-leading symbols don't read); inc/dec
;; dispatch instead (upstream CL + would have thrown on ugens).
(defn inc [x]
  (if (number? x) (clojure.core/inc x) (add 1 x)))

(defn dec [x]
  (if (number? x) (clojure.core/dec x) (minus x 1)))

;; -- MulAdd (new1 simplifications inline; no graph surgery) ---------------------

(defn- can-be-mul-add [in mul add]
  (if (= :audio (ugen/rate in))
    true
    (boolean
     (and (= :control (ugen/rate in))
          (or (= :control (ugen/rate mul)) (= :scalar (ugen/rate mul)))
          (or (= :control (ugen/rate add)) (= :scalar (ugen/rate add)))))))

(defn mul-add
  ([in] (mul-add in 1.0 0.0))
  ([in mulv] (mul-add in mulv 0.0))
  ([in mulv addv]
   (ugen/multinew
    (fn [cls i m a]
      (let [rate (ugen/number-rate
                  (apply max (map (comp ugen/rate-number ugen/rate) [i m a])))
            minus? (num-eq? m -1.0)
            nomul? (num-eq? m 1.0)
            noadd? (num-eq? a 0.0)
            bogus? (num-eq? m 0.0)]
        (cond bogus? a
              (and nomul? noadd?) i
              (and minus? noadd?) (neg i)
              noadd? (mul i m)
              minus? (minus a i)
              nomul? (add i a)
              :else
              (let [[x y z] (if (can-be-mul-add i m a) [i m a] [m i a])]
                (if (can-be-mul-add x y z)
                  (ugen/add-to-synth
                   (ugen/construct "MulAdd" rate cls identity :bipolar 0 [x y z]))
                  (add (mul i m) a))))))
    'muladd in mulv addv)))

;; -- Sum3/Sum4 (zero elimination + rate-desc sort; no surgery) -------------------

(defn sum3 [in0 in1 in2]
  (ugen/multinew
   (fn [cls a b c]
     (cond (num-eq? c 0.0) (add a b)
           (num-eq? b 0.0) (add a c)
           (num-eq? a 0.0) (add b c)
           :else (let [args (ugen/sort-by-rate-desc [a b c])]
                   (ugen/add-to-synth
                    (ugen/construct "Sum3" (ugen/rate [a b c]) cls identity :bipolar 0 args)))))
   'sum3 in0 in1 in2))

(defn sum4 [in0 in1 in2 in3]
  (ugen/multinew
   (fn [cls a b c d]
     (cond (num-eq? a 0.0) (sum3 b c d)
           (num-eq? b 0.0) (sum3 a c d)
           (num-eq? c 0.0) (sum3 a b d)
           (num-eq? d 0.0) (sum3 a b c)
           :else (let [args (ugen/sort-by-rate-desc [a b c d])]
                   (ugen/add-to-synth
                    (ugen/construct "Sum4" (ugen/rate [a b c d]) cls identity :bipolar 0 args)))))
   'sum4 in0 in1 in2 in3))

;; -- composition helpers ----------------------------------------------------------

(defn if* [c t f]
  ;; Number comparisons yield booleans (compare-dispatch); picking eagerly
  ;; lets scalar operator graphs (fold:ir) fold to numbers. UGen
  ;; conditions still build the mul-add graph. Booleans previously threw
  ;; in multinew (no rate), so no working graph changes shape.
  (if (boolean? c)
    (if c t f)
    (add (mul c (minus t f)) f)))

(defn madd
  ([ug] (mul-add ug 1.0 0.0))
  ([ug mul] (mul-add ug mul 0.0))
  ([ug mul add] (mul-add ug mul add)))

(defn range
  ([ug] (range ug 0.0 1.0))
  ([ug lo hi]
   (ugen/multinew
    (fn [_cls u l h]
      (if (not (ugen/ugen-like? u))
        (throw (ex-info "range needs a ugen input" {:got u}))
        (let [[m a] (if (= :bipolar (:signal-range u))
                      (let [mm (mul (minus h l) 0.5)] [mm (add mm l)])
                      [(minus h l) l])]
          (madd u m a))))
    nil ug lo hi)))
(defn unipolar
  ([ug] (range ug 0 1))
  ([ug mul] (range ug 0 mul)))

(defn bipolar
  ([ug] (bipolar ug 1))
  ([ug mul] (range ug (neg mul) mul)))

;; -- multichannel plumbing -----------------------------------------------------------

(defn sum [array]
  (let [arr (if (sequential? array) (vec array) [array])]
    (when (seq arr) (reduce + arr))))

(defn bubble [x] [x])

(defn flop [lists]
  (let [lists (mapv #(if (sequential? %) (vec %) [%]) lists)
        len (apply max (mapv count lists))]
    (mapv (fn [i] (mapv #(nth % (mod i (count %))) lists))
          (clojure.core/range len))))

(defn clump [lists n]
  (mapv vec (partition-all n lists)))

(defn mix [array]
  (let [arr (if (sequential? array) (vec array) [array])
        cols (mapv #(cond (= 4 (count %)) (apply sum4 %)
                          (= 3 (count %)) (apply sum3 %)
                          :else (sum %))
                   (clump arr 4))]
    (cond (< (count cols) 3) (sum cols)
          (= 3 (count cols)) (apply sum3 cols)
          :else (mix cols))))

(defn mean [array]
  (let [arr (if (sequential? array) (vec array) [array])]
    (if (every? number? arr)
      (/ (reduce clojure.core/+ arr) (double (count arr)))
      (/ (sum arr) (count arr)))))

(defn product [lst & [f]]
  (let [items (if f (mapv f lst (clojure.core/range (count lst))) lst)]
    (reduce * 1 items)))

(defn dup
  ([obj] (dup obj 2))
  ([obj n]
   (cond (and (fn? obj) (integer? n)) (mapv obj (clojure.core/range n))
         (fn? obj) (mapv obj n)
         (sequential? obj) (vec (repeat n (vec obj)))
         :else (vec (repeat n obj)))))
