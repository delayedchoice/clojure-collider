(ns clojure-collider.synth.synthdef
  (:require [clojure-collider.synth.ugen :as ugen])
  (:import [java.io ByteArrayOutputStream DataOutputStream]))

(def ^:dynamic *synthdef-version* 2)
(def type-id (.getBytes "SCgf" "UTF-8"))

(defrecord SynthDef [name controls control-names control-ugen-count children constants available width-first-ugens rewrite-in-progress named-controls])

(defn make-synthdef [name]
  (atom (map->SynthDef {:name (str name) :controls [] :control-names [] :control-ugen-count 0 :children [] :constants [] :available [] :width-first-ugens [] :rewrite-in-progress false :named-controls []})))

(defn- rewrite-inputs
  "Substitute stale input copies with current sorted records.
   Upstream mutates ugen objects in place so inputs see reindexing for free;
   our records are values, so inputs still carry creation-order copies.
   `pos` maps creation index -> schedule position, `sorted` holds the
   reindexed records."
  [ugen pos sorted]
  (let [fresh (fn [inp]
                (if-let [p (pos (:synth-index inp))]
                  (nth sorted p)
                  (throw (ex-info "stale ugen input survives optimize (fusion/DCE bug)"
                                  {:input (select-keys inp [:name :synth-index])}))))]
    (assoc ugen :inputs
           (mapv (fn [inp]
                   (cond (ugen/ugen-like? inp)
                         (fresh inp)
                         (ugen/proxy-output? inp)
                         (assoc inp :source (fresh (:source inp)))
                         :else inp))
                 (:inputs ugen)))))

(defn- dead-code-eliminate
  "Fixpoint removal of unconsumed DCE-flavor nodes (ugen.lisp
   perform-dead-code-elimination)."
  [children]
  (loop [kids (vec children)]
    (let [[_deps dependents] (ugen/graph-edges kids)
          dead (set (keep-indexed
                     (fn [j u]
                       (when (and (contains? ugen/dce-flavors (:ugen-class u))
                                  (empty? (get dependents j #{})))
                         j))
                     kids))]
      (if (empty? dead)
        kids
        (recur (vec (keep-indexed (fn [j u] (when-not (dead j) u)) kids)))))))

(defn- add-unit? [u]
  (and (= "BinaryOpUGen" (:name u))
       (= 0 (:special-index u 0))
       (= 2 (count (:inputs u)))))

(defn- canon-index
  "Position-independent identity for an input: proxies resolve to source."
  [inp]
  (cond (ugen/proxy-output? inp) (:synth-index (:source inp))
        (ugen/ugen-like? inp) (:synth-index inp)))

(defn- fuse-sums
  "Fixpoint (+ (+ A B) C) -> Sum3 / (+ Sum3 C) -> Sum4 (operators.lisp
   optimize-to-sum3/sum4). Records are values so the pass rebuilds the
   vector: the absorbed unit is dropped, refs to the outer add point at
   the replacement. Refs match by :synth-index, NOT object identity: each
   iteration's mapv copies consumers, so identities held across iterations
   go stale (a 4-voice sum fused twice left the final mult pointing at a
   dropped add -> 'buffer coloring error' from scsynth).
   Zero-valued number inputs skip fusion (upstream folds those through add
   instead; construction usually simplifies them away)."
  [children]
  (loop [kids (vec children)]
    (let [pos-of (into {} (keep-indexed (fn [p u]
                                          (when (>= (:synth-index u -1) 0)
                                            [(:synth-index u) p]))
                                        kids))
          at-pos (fn [inp]
                   (when-some [idx (canon-index inp)]
                     (when-some [p (pos-of idx)]
                       (when (identical? (nth kids p) inp) p))))
          uses (reduce (fn [m u]
                         (reduce (fn [m inp]
                                   (if-some [p (at-pos inp)]
                                     (update m p (fnil inc 0))
                                     m))
                                 m (:inputs u)))
                       {} kids)
          demand? (fn [u] (some #(= :demand (ugen/rate %)) (:inputs u)))
          zero-num? (fn [x] (and (number? x) (zero? x)))
          ;; find outer add u with absorbable inner add/sum3 x
          triple
          (some (fn [[j u]]
                  (when (and (add-unit? u) (not (demand? u)))
                    (let [[a b] (:inputs u)
                          comp-add? (fn [v]
                                      (when-some [k (when (ugen/ugen-like? v)
                                                      (at-pos v))]
                                        (let [x (nth kids k)]
                                          (when (and (add-unit? x)
                                                     (= 1 (get uses k 0)))
                                            [x k]))))
                          comp-sum? (fn [v]
                                      (when-some [k (when (ugen/ugen-like? v)
                                                      (at-pos v))]
                                        (let [x (nth kids k)]
                                          (when (and (= "Sum3" (:name x))
                                                     (= 1 (get uses k 0)))
                                            [x k]))))]
                      (or (when-some [[x k] (or (comp-add? a) (comp-add? b))]
                            (let [y (if (identical? a x) b a)
                                  [x0 x1] (:inputs x)]
                              (if (identical? x y)
                                ;; (+ X X) -> Sum4 doubling X's inputs
                                {:j j :u u :k k :x x :kind :sum4
                                 :ins [x0 x0 x1 x1]}
                                (let [ins (into [] (concat (:inputs x) [y]))]
                                  (when (not-any? zero-num? ins)
                                    {:j j :u u :k k :x x :kind :sum3 :ins ins})))))
                          (when-some [[x k] (or (comp-sum? a) (comp-sum? b))]
                            (let [y (if (identical? a x) b a)
                                  ins (into [] (concat (:inputs x) [y]))]
                              (when (and (not (identical? x y))
                                         (not-any? zero-num? ins))
                                {:j j :u u :k k :x x :kind :sum4 :ins ins})))))))
                (map-indexed vector kids))]
      (if-not triple
        kids
        (let [{:keys [u k kind ins]} triple
              tag (if (= kind :sum3) "Sum3" "Sum4")
              cls (if (= kind :sum3) 'sum3 'sum4)
              args (ugen/sort-by-rate-desc ins)
              fresh-idx (inc (apply max -1 (map #(:synth-index % -1) kids)))
              r (assoc (ugen/construct tag (ugen/rate (vec ins)) cls
                                       identity :bipolar 0 (vec args))
                       :synth-index fresh-idx)
              kids2 (into [] (keep-indexed (fn [p u2] (when (not= p k) u2)) kids))
              ui (:synth-index u)]
          (recur (mapv (fn [u2]
                         (cond (= (:synth-index u2) ui) r
                               :else (update u2 :inputs
                                             (fn [inps]
                                               (mapv #(if (= (canon-index %) ui) r %) inps)))))
                       kids2)))))))

(defn build-synthdef [synthdef-atom]
  ;; mirrors synthdef.lisp build-synthdef: check-inputs, optimize-graph,
  ;; collect-constants, topo-logical-sort, index-ugens.
  (let [s @synthdef-atom]
    ;; check-inputs
    (doseq [u (:children s)]
      (when-let [cf (:check-fn u)] (cf u)))
    ;; optimize-graph (DCE + sum fusion)
    (swap! synthdef-atom assoc :children
           (fuse-sums (dead-code-eliminate (:children @synthdef-atom))))
    ;; collect-constants
    (let [s @synthdef-atom]
      (doseq [u (:children s)]
        (doseq [inp (:inputs u)]
          (when (not (or (ugen/ugen-like? inp)
                         (ugen/proxy-output? inp)))
            (ugen/add-constant-to-synthdef synthdef-atom (ugen/floatfy inp))))))
    ;; topo-logical-sort + index-ugens + input rewrite.
    ;; order holds vector positions; creation indices translate via pos-of.
    (let [s @synthdef-atom
          children (:children s)
          pos-of (into {} (map-indexed (fn [pos u] [(:synth-index u) pos]) children))
          order (ugen/topo-sort children)
          new-of-old-pos (into {} (map-indexed (fn [new-i old-pos] [old-pos new-i]) order))
          pos (into {} (map (fn [[c p]] [c (new-of-old-pos p)]) pos-of))
          sorted (mapv (fn [new-i old-pos]
                         (assoc (nth children old-pos) :synth-index new-i))
                       (range (count order)) order)
          sorted (mapv #(rewrite-inputs % pos sorted) sorted)]
      (swap! synthdef-atom assoc :children sorted)
      @synthdef-atom)))

(defn encode-synthdef-v2 [synthdef-atom]
  (let [s @synthdef-atom
        baos (ByteArrayOutputStream.)
        out (DataOutputStream. baos)]
    (.write out type-id)
    (.writeInt out 2)
    (.writeShort out (short 1))
    ;; pstring name
    (let [name-bytes (.getBytes ^String (:name s) "UTF-8")]
      (.writeByte out (int (alength name-bytes)))
      (.write out name-bytes))
    (.writeInt out (count (:constants s)))
    (doseq [c (:constants s)] (.writeFloat out (float c)))
    (.writeInt out (count (:controls s)))
    (doseq [c (:controls s)] (.writeFloat out (float c)))
    (.writeInt out (count (:control-names s)))
    (doseq [[n idx] (:control-names s)]
      (let [nb (.getBytes ^String n "UTF-8")]
        (.writeByte out (int (alength nb)))
        (.write out nb)
        (.writeInt out idx)))
    (.writeInt out (count (:children s)))
    (doseq [u (:children s)]
      (ugen/write-def-ugen-v2 u s out))
    (.writeShort out (short 0)) ; variants
    (.toByteArray baos)))

(defn encode-synthdef [synthdef-atom]
  (case *synthdef-version*
    2 (encode-synthdef-v2 synthdef-atom)
    (throw (ex-info "unsupported version" {:v *synthdef-version*}))))

(defn write-synthdef-file [synthdef-atom path]
  (let [bytes (encode-synthdef synthdef-atom)]
    (with-open [out (java.io.FileOutputStream. ^String path)]
      (.write out bytes))
    path))
