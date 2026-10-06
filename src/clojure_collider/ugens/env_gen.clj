(ns clojure-collider.ugens.env-gen
  "Envelope UGens + the Lisp-side Env builder. Mirrors ugens/EnvGen.lisp:
   shape table, Env record, env + variants, make-env-array-from-env,
   process-env, env-gen:ar/kr, linen:kr, done/free helpers."
  (:require [clojure-collider.synth.ugen :as ugen]
            [clojure-collider.synth.operators :as op]))

(def ^:private no-check (fn [_] nil))

;; -- shape table (EnvGen.lisp +env-shape-table+, keywords for CL symbols) ------

(def shape-table
  {:step 0 :lin 1 :linear 1 :exp 2 :exponential 2
   :sin 3 :sine 3 :wel 4 :welch 4 :sqr 6 :cub 7 :cubed 7 :hold 8})

(defn env-shape-number
  "Keyword -> table entry (error on unknown); UGen/number -> 5 (custom curve)."
  [c]
  (cond (keyword? c) (or (get shape-table c)
                         (throw (ex-info "not a valid envelope curve" {:curve c})))
        (ugen/ugen-like? c) 5
        (number? c) 5
        :else (throw (ex-info "not a valid envelope curve" {:curve c}))))

;; -- Env record + builders -----------------------------------------------------

(defrecord Env [levels times curve-number curve-value release-node loop-node])

(defn- ensure-vec [x] (if (sequential? x) (vec x) [x]))

(defn env
  "Lisp-side envelope: levels + times lists, curve keyword/number/UGen
   (default :lin), release/loop nodes (default -99)."
  [levels times & [curve release-node loop-node]]
  (let [curves (ensure-vec (or curve :lin))]
    (map->Env
     {:levels levels :times times
      :curve-number (mapv env-shape-number curves)
      :curve-value (mapv (fn [c] (cond (keyword? c) 0 (number? c) c :else c))
                         curves)
      :release-node (or release-node -99)
      :loop-node (or loop-node -99)})))

(defn env-triangle [& [dur level]]
  (let [dur (op/* (or dur 1.0) 0.5)]
    (env [0 (or level 1.0) 0] [dur dur])))

(defn env-sine [& [dur level]]
  (let [dur (op/* (or dur 1.0) 0.5)]
    (env [0 (or level 1.0) 0] [dur dur] :sine)))

(defn env-perc [& [attack release level curve]]
  (env [0 (or level 1.0) 0] [(or attack 0.01) (or release 1.0)]
       (or curve -4.0)))

(defn env-linen [& [attack sus-time release level curve]]
  (env [0 (or level 1.0) (or level 1.0) 0]
       [(or attack 0.01) (or sus-time 1.0) (or release 1.0)]
       (or curve :lin)))

(defn env-adsr [& [attack-time decay-time sustain-level release-time peak-level curve bias]]
  (let [a (or attack-time 0.01) d (or decay-time 0.3) s (or sustain-level 0.5)
        r (or release-time 1.0) p (or peak-level 1.0)
        c (or curve -4.0) b (or bias 0.0)]
    (env (op/+ [0 p (op/* p s) 0] b) [a d r] c)))

(defn env-asr [& [attack-time sustain-level release-time curve]]
  (env [0 (or sustain-level 1.0) 0]
       [(or attack-time 0.01) (or release-time 1.0)]
       (or curve -4.0)))

(defn env-dadsr [& [delay-time attack-time decay-time sustain-level release-time peak-level curve bias]]
  (let [dl (or delay-time 0.1) a (or attack-time 0.01) d (or decay-time 0.3)
        s (or sustain-level 0.5) r (or release-time 1.0) p (or peak-level 1.0)
        c (or curve -4.0) b (or bias 0.0)]
    (env (op/+ [0 0 p (op/* p s) 0] b) [dl a d r] c)))

;; Opaque array (upstream Lisp arrays are atoms to lst-operation; Clojure
;; vectors are sequential and would wrongly expand as multichannel input).
(defrecord EnvArray [values])

(defn make-env-array-from-env
  "Env record -> vector of flat arrays
   [init-level num-segs release-node loop-node seg...] (one per multichannel
   expansion). Sequential input passes through (single vector if all UGens)."
  [e]
  (cond
    (instance? Env e)
    (let [{:keys [levels times curve-number curve-value release-node loop-node]} e
          plain? (fn [x] (every? (complement sequential?) x))
          deep (mapv (fn [x] (let [v (ensure-vec x)] (if (plain? v) [v] v)))
                     [levels times curve-number curve-value])
          n (apply max (map count deep))
          wrap (fn [i lst] (nth lst (mod i (count lst))))
          arrays
          (mapv (fn [i]
                  (let [lvs (wrap i (nth deep 0))
                        tms (wrap i (nth deep 1))
                        cns (wrap i (nth deep 2))
                        cvs (wrap i (nth deep 3))
                        seg (vec (mapcat identity
                                          (ugen/multinew-list
                                           (fn [_ & args] (vec args)) nil
                                           [(vec (rest lvs)) (vec tms)
                                            (vec cns) (vec cvs)])))]
                    (->EnvArray
                     (vec (concat [(first lvs) (count tms)
                                   release-node loop-node]
                                  seg)))))
                (range n))]
      ;; outer expansion over release/loop nodes (scalar in the common case)
      (mapv (fn [[r l arr]] (update arr :values assoc 2 r 3 l))
            (ugen/multinew-list (fn [_ & args] (vec args)) nil
                                [(ensure-vec release-node)
                                 (ensure-vec loop-node) arrays])))

    (sequential? e)
    (if (every? ugen/ugen-like? e)
      [(->EnvArray (vec e))]
      (mapv #(if (instance? EnvArray %) % (->EnvArray %)) e))

    :else (throw (ex-info "not an envelope" {:env e}))))

(defn free-self:kr [in]
  ;; upstream (progn (multinew ...) in): unit is built for the graph,
  ;; the input passes through as the value.
  (ugen/multinew (fn [cls i] (ugen/ugen-new "FreeSelf" :control cls no-check :scalar i))
                 'ugen in)
  in)
(defn process-env
  "Splice the trailing env array into the input list (ugen.lisp process-env)."
  [u]
  (let [ins (:inputs u)
        arr (last ins)]
    (assoc u :inputs
           (vec (concat (butlast ins)
                        (if (instance? EnvArray arr) (:values arr) [arr]))))))

;; -- EnvGen ---------------------------------------------------------------------

(defn- env-gen*
  [rate envelope {:keys [gate level-scale level-bias time-scale act]
                  :or {gate 1 level-scale 1 level-bias 0 time-scale 1
                       act :no-action}}]
  (let [act-i (ugen/act act)
        arrays (make-env-array-from-env envelope)]
    (ugen/unbubble
     (ugen/multinew
      ;; splice BEFORE add: records are values, so the graph must store
      ;; the processed copy (upstream setf-mutates in place).
      (fn [_ g ls lb ts a arr]
        (ugen/add-to-synth
         (process-env
          (ugen/construct "EnvGen" rate 'ugen nil :bipolar 0
                          [g ls lb ts a arr]))))
      nil gate level-scale level-bias time-scale act-i arrays))))

(defn env-gen:kr
  [envelope & {:keys [gate level-scale level-bias time-scale act]
               :or {gate 1 level-scale 1 level-bias 0 time-scale 1
                    act :no-action}}]
  (env-gen* :control envelope
            {:gate gate :level-scale level-scale :level-bias level-bias
             :time-scale time-scale :act act}))

(defn env-gen:ar
  [envelope & {:keys [gate level-scale level-bias time-scale act]
               :or {gate 1 level-scale 1 level-bias 0 time-scale 1
                    act :no-action}}]
  (env-gen* :audio envelope
            {:gate gate :level-scale level-scale :level-bias level-bias
             :time-scale time-scale :act act}))

(defn linen:kr
  "Upstream shape: positional gate/attack/sus/release + :act kwarg."
  ([gate] (linen:kr gate 0.01 1.0 1.0 :act :no-action))
  ([gate attack-time] (linen:kr gate attack-time 1.0 1.0 :act :no-action))
  ([gate attack-time sus-level]
   (linen:kr gate attack-time sus-level 1.0 :act :no-action))
  ([gate attack-time sus-level release-time & {:keys [act]
                                                :or {act :no-action}}]
   (ugen/unbubble
    (ugen/multinew
     (fn [cls g a s r ai]
       (ugen/ugen-new "Linen" :control cls no-check :bipolar g a s r ai))
     'pure-ugen gate attack-time sus-level release-time (ugen/act act)))))

;; -- done/free helpers ------------------------------------------------------------

(defn done:kr [src]
  (ugen/unbubble
   (ugen/multinew (fn [cls s] (ugen/ugen-new "Done" :control cls no-check :scalar s))
                  'ugen src)))

(defn pause-self:kr [in]
  (ugen/multinew (fn [cls i] (ugen/ugen-new "PauseSelf" :control cls no-check :scalar i))
                 'ugen in)
  in)

(defn free-self-when-done:kr [src]
  (ugen/unbubble
   (ugen/multinew (fn [cls s] (ugen/ugen-new "FreeSelfWhenDone" :control cls no-check :scalar s))
                  'ugen src)))

(defn pause-self-when-done:kr [src]
  (ugen/unbubble
   (ugen/multinew (fn [cls s] (ugen/ugen-new "PauseSelfWhenDone" :control cls no-check :scalar s))
                  'ugen src)))

(defn pause:kr [gate id]
  (ugen/unbubble
   (ugen/multinew (fn [cls g i] (ugen/ugen-new "Pause" :control cls no-check :scalar g i))
                  'ugen gate id)))

(defn free:kr [trig id]
  (ugen/unbubble
   (ugen/multinew (fn [cls t i] (ugen/ugen-new "Free" :control cls no-check :scalar t i))
                  'ugen trig id)))


;; ---- generated mechanical forms (scripts/emit_ugens.clj) ----

(defn puase:kr
  "Port of EnvGen.lisp (puase \"Pause\")."
  ([gate id]
   (ugen/multinew (fn [cls gate id] (ugen/ugen-new "Pause" :control cls no-check :bipolar gate id))
                           'ugen gate id)))
