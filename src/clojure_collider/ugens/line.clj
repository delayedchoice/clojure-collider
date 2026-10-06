(ns clojure-collider.ugens.line
  "Line UGens. Mirrors the matching ugens/Line.lisp defugens: dc, silent,
   line, x-line (madd units with done-action inputs)."
  (:require [clojure-collider.synth.ugen :as ugen]
            [clojure-collider.synth.operators :as op]))

(def ^:private no-check (fn [_] nil))

(defn dc:ar
  ([] (dc:ar 0.0))
  ([in]
   (ugen/unbubble
    (ugen/multinew (fn [_cls i] (ugen/multiout-new "DC" :audio :bipolar 1 i))
                   'multiout-ugen in))))

(defn dc:kr
  ([] (dc:kr 0.0))
  ([in]
   (ugen/unbubble
    (ugen/multinew (fn [_cls i] (ugen/multiout-new "DC" :control :bipolar 1 i))
                   'multiout-ugen in))))

(defn silent:ar
  ([] (silent:ar 1))
  ([chanls]
   (let [sig (dc:ar 0)]
     (if (= chanls 1) sig (vec (repeat chanls sig))))))

(defn line:ar
  "Upstream shape: positional start/end/dur + :mul/:add/:act kwargs."
  ([] (line:ar 0.0 1.0 1.0))
  ([start] (line:ar start 1.0 1.0))
  ([start end] (line:ar start end 1.0))
  ([start end dur & {:keys [mul add act]
                      :or {mul 1.0 add 0.0 act :no-action}}]
   (op/madd (ugen/multinew (fn [cls s e d a]
                             (ugen/ugen-new "Line" :audio cls no-check :bipolar
                                            s e d (ugen/act a)))
                           'pure-ugen start end dur act)
            mul add)))

(defn line:kr
  "Upstream shape: positional start/end/dur + :mul/:add/:act kwargs."
  ([] (line:kr 0.0 1.0 1.0))
  ([start] (line:kr start 1.0 1.0))
  ([start end] (line:kr start end 1.0))
  ([start end dur & {:keys [mul add act]
                      :or {mul 1.0 add 0.0 act :no-action}}]
   (op/madd (ugen/multinew (fn [cls s e d a]
                             (ugen/ugen-new "Line" :control cls no-check :bipolar
                                            s e d (ugen/act a)))
                           'pure-ugen start end dur act)
            mul add)))

(defn x-line:ar
  "Upstream shape: positional start/end/dur + :mul/:add/:act kwargs."
  ([] (x-line:ar 1.0 2.0 1.0))
  ([start] (x-line:ar start 2.0 1.0))
  ([start end] (x-line:ar start end 1.0))
  ([start end dur & {:keys [mul add act]
                      :or {mul 1.0 add 0.0 act :no-action}}]
   (op/madd (ugen/multinew (fn [cls s e d a]
                             (ugen/ugen-new "XLine" :audio cls no-check :bipolar
                                            s e d (ugen/act a)))
                           'pure-ugen start end dur act)
            mul add)))

(defn x-line:kr
  "Upstream shape: positional start/end/dur + :mul/:add/:act kwargs."
  ([] (x-line:kr 1.0 2.0 1.0))
  ([start] (x-line:kr start 2.0 1.0))
  ([start end] (x-line:kr start end 1.0))
  ([start end dur & {:keys [mul add act]
                      :or {mul 1.0 add 0.0 act :no-action}}]
   (op/madd (ugen/multinew (fn [cls s e d a]
                             (ugen/ugen-new "XLine" :control cls no-check :bipolar
                                            s e d (ugen/act a)))
                           'pure-ugen start end dur act)
            mul add)))


;; ---- generated mechanical forms (scripts/emit_ugens.clj) ----

(defn lin-exp:ar
  "Port of Line.lisp (lin-exp \"LinExp\")."
  ([] (lin-exp:ar 0.0 0.0 1.0 1.0 2.0))
  ([in] (lin-exp:ar in 0.0 1.0 1.0 2.0))
  ([in srclo] (lin-exp:ar in srclo 1.0 1.0 2.0))
  ([in srclo srchi] (lin-exp:ar in srclo srchi 1.0 2.0))
  ([in srclo srchi dstlo] (lin-exp:ar in srclo srchi dstlo 2.0))
  ([in srclo srchi dstlo dsthi]
   (ugen/multinew (fn [cls in srclo srchi dstlo dsthi] (ugen/ugen-new "LinExp" :audio cls ugen/check-same-rate-as-first-input :bipolar in srclo srchi dstlo dsthi))
                           'pure-ugen in srclo srchi dstlo dsthi)))

(defn lin-exp:kr
  "Port of Line.lisp (lin-exp \"LinExp\")."
  ([] (lin-exp:kr 0.0 0.0 1.0 1.0 2.0))
  ([in] (lin-exp:kr in 0.0 1.0 1.0 2.0))
  ([in srclo] (lin-exp:kr in srclo 1.0 1.0 2.0))
  ([in srclo srchi] (lin-exp:kr in srclo srchi 1.0 2.0))
  ([in srclo srchi dstlo] (lin-exp:kr in srclo srchi dstlo 2.0))
  ([in srclo srchi dstlo dsthi]
   (ugen/multinew (fn [cls in srclo srchi dstlo dsthi] (ugen/ugen-new "LinExp" :control cls ugen/check-same-rate-as-first-input :bipolar in srclo srchi dstlo dsthi))
                           'pure-ugen in srclo srchi dstlo dsthi)))

(defn amp-comp:ir
  "Port of Line.lisp (amp-comp \"AmpComp\")."
  ([] (amp-comp:ir (op/midicps 60.0) (op/midicps 60.0) 0.3333))
  ([freq] (amp-comp:ir freq (op/midicps 60.0) 0.3333))
  ([freq root] (amp-comp:ir freq root 0.3333))
  ([freq root exp]
   (ugen/multinew (fn [cls freq root exp] (ugen/ugen-new "AmpComp" :scalar cls ugen/when-audio-check-first-input :bipolar freq root exp))
                           'pure-ugen freq root exp)))

(defn amp-comp:ar
  "Port of Line.lisp (amp-comp \"AmpComp\")."
  ([] (amp-comp:ar (op/midicps 60.0) (op/midicps 60.0) 0.3333))
  ([freq] (amp-comp:ar freq (op/midicps 60.0) 0.3333))
  ([freq root] (amp-comp:ar freq root 0.3333))
  ([freq root exp]
   (ugen/multinew (fn [cls freq root exp] (ugen/ugen-new "AmpComp" :audio cls ugen/when-audio-check-first-input :bipolar freq root exp))
                           'pure-ugen freq root exp)))

(defn amp-comp:kr
  "Port of Line.lisp (amp-comp \"AmpComp\")."
  ([] (amp-comp:kr (op/midicps 60.0) (op/midicps 60.0) 0.3333))
  ([freq] (amp-comp:kr freq (op/midicps 60.0) 0.3333))
  ([freq root] (amp-comp:kr freq root 0.3333))
  ([freq root exp]
   (ugen/multinew (fn [cls freq root exp] (ugen/ugen-new "AmpComp" :control cls ugen/when-audio-check-first-input :bipolar freq root exp))
                           'pure-ugen freq root exp)))

(defn amp-comp-a:ir
  "Port of Line.lisp (amp-comp-a \"AmpCompA\")."
  ([] (amp-comp-a:ir 1000 0.0 0.32 1.0))
  ([freq] (amp-comp-a:ir freq 0.0 0.32 1.0))
  ([freq root] (amp-comp-a:ir freq root 0.32 1.0))
  ([freq root min-amp] (amp-comp-a:ir freq root min-amp 1.0))
  ([freq root min-amp root-amp]
   (ugen/multinew (fn [cls freq root min-amp root-amp] (ugen/ugen-new "AmpCompA" :scalar cls ugen/when-audio-check-first-input :bipolar freq root min-amp root-amp))
                           'pure-ugen freq root min-amp root-amp)))

(defn amp-comp-a:ar
  "Port of Line.lisp (amp-comp-a \"AmpCompA\")."
  ([] (amp-comp-a:ar 1000 0.0 0.32 1.0))
  ([freq] (amp-comp-a:ar freq 0.0 0.32 1.0))
  ([freq root] (amp-comp-a:ar freq root 0.32 1.0))
  ([freq root min-amp] (amp-comp-a:ar freq root min-amp 1.0))
  ([freq root min-amp root-amp]
   (ugen/multinew (fn [cls freq root min-amp root-amp] (ugen/ugen-new "AmpCompA" :audio cls ugen/when-audio-check-first-input :bipolar freq root min-amp root-amp))
                           'pure-ugen freq root min-amp root-amp)))

(defn amp-comp-a:kr
  "Port of Line.lisp (amp-comp-a \"AmpCompA\")."
  ([] (amp-comp-a:kr 1000 0.0 0.32 1.0))
  ([freq] (amp-comp-a:kr freq 0.0 0.32 1.0))
  ([freq root] (amp-comp-a:kr freq root 0.32 1.0))
  ([freq root min-amp] (amp-comp-a:kr freq root min-amp 1.0))
  ([freq root min-amp root-amp]
   (ugen/multinew (fn [cls freq root min-amp root-amp] (ugen/ugen-new "AmpCompA" :control cls ugen/when-audio-check-first-input :bipolar freq root min-amp root-amp))
                           'pure-ugen freq root min-amp root-amp)))

(defn k2a:ar
  "Port of Line.lisp (k2a \"K2A\")."
  ([] (k2a:ar 0.0))
  ([in]
   (ugen/multinew (fn [cls in] (ugen/ugen-new "K2A" :audio cls no-check :bipolar in))
                           'pure-ugen in)))

(defn a2k:kr
  "Port of Line.lisp (a2k \"A2K\")."
  ([] (a2k:kr 0.0))
  ([in]
   (ugen/multinew (fn [cls in] (ugen/ugen-new "A2K" :control cls no-check :bipolar in))
                           'pure-ugen in)))

(defn t2k:kr
  "Port of Line.lisp (t2k \"T2K\")."
  ([] (t2k:kr 0.0))
  ([in]
   (ugen/multinew (fn [cls in] (ugen/ugen-new "T2K" :control cls ugen/when-audio-check-first-input :bipolar in))
                           'pure-ugen in)))

(defn t2a:ar
  "Port of Line.lisp (t2a \"T2A\")."
  ([] (t2a:ar 0.0 0.0))
  ([in] (t2a:ar in 0.0))
  ([in offset]
   (ugen/multinew (fn [cls in offset] (ugen/ugen-new "T2A" :audio cls no-check :bipolar in offset))
                           'pure-ugen in offset)))

(defn lin-lin:ar
  "Port of Line.lisp lin-lin.ar: linear map of in from [srclo srchi] to [dstlo dsthi]."
  ([in] (lin-lin:ar in 0.0 1.0 1.0 2.0))
  ([in srclo] (lin-lin:ar in srclo 1.0 1.0 2.0))
  ([in srclo srchi] (lin-lin:ar in srclo srchi 1.0 2.0))
  ([in srclo srchi dstlo] (lin-lin:ar in srclo srchi dstlo 2.0))
  ([in srclo srchi dstlo dsthi]
   (let [scale (op// (op/- dsthi dstlo) (op/- srchi srclo))
         offset (op/- dstlo (op/* scale srclo))]
     (op/madd in scale offset))))

(defn lin-lin:kr
  "Port of Line.lisp lin-lin.kr: linear map of in from [srclo srchi] to [dstlo dsthi]."
  ([in] (lin-lin:kr in 0.0 1.0 1.0 2.0))
  ([in srclo] (lin-lin:kr in srclo 1.0 1.0 2.0))
  ([in srclo srchi] (lin-lin:kr in srclo srchi 1.0 2.0))
  ([in srclo srchi dstlo] (lin-lin:kr in srclo srchi dstlo 2.0))
  ([in srclo srchi dstlo dsthi]
   (let [scale (op// (op/- dsthi dstlo) (op/- srchi srclo))
         offset (op/- dstlo (op/* scale srclo))]
     (op/+ (op/* in scale) offset))))

(defn- prune
  "Port of operators.lisp prune: bound in per clip type (:minmax clips to
  [lo hi]; :min floors at lo; :max caps at hi). The Clip unit lives here
  until synth.operators lands prune/Clip in Phase-6."
  [in lo hi clip-type]
  (case clip-type
    :minmax (case (ugen/rate in)
               :audio (ugen/multinew (fn [cls i l h] (ugen/ugen-new "Clip" :audio cls no-check :bipolar i l h))
                                     'ugen in lo hi)
               :control (ugen/multinew (fn [cls i l h] (ugen/ugen-new "Clip" :control cls no-check :bipolar i l h))
                                       'ugen in lo hi))
    :min (op/max in lo)
    :max (op/min in hi)))

(defn lin-lin
  "Port of Line.lisp lin-lin: scalar linear map for numbers; for
  audio/control-rate ugens, lin-lin:ar/kr over the prune-clipped input."
  ([in in-min in-max out-min out-max] (lin-lin in in-min in-max out-min out-max :minmax))
  ([in in-min in-max out-min out-max clip]
   (if (number? in)
     (let [result (case clip
                    :minmax (cond (<= in in-min) out-min
                                  (>= in in-max) out-max)
                    :min (when (<= in in-min) out-min)
                    :max (when (>= in in-max) out-max)
                    nil)]
       (if (some? result)
         result
         (+ (* (/ (- in in-min) (- in-max in-min))
               (- out-max out-min))
            out-min)))
     (case (ugen/rate in)
       :audio (lin-lin:ar (prune in in-min in-max clip) in-min in-max out-min out-max)
       :control (lin-lin:kr (prune in in-min in-max clip) in-min in-max out-min out-max)))))
