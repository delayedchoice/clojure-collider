(ns patterns.patterns
  "Value patterns ported from cl-patterns v0.7 (patterns.lisp).

   Patterns are plain data maps {:ptype ::<name> ...}; streams are ONE
   generic record (Stream {:state (atom state-map) :step step-fn}).
   step-fn is (fn [state-map] -> [value new-state-map] | eop) and MUST
   preserve unknown keys in the returned map (assoc, never replace),
   because pnext!/ppeek manage a ::pending lookahead key inside it.

   Length/repeats bounds are numbers or :inf ONLY (no pattern-valued
   bounds in this MVP). Plain values anywhere auto-wrap to infinite
   const streams via as-pstream; sequentials auto-wrap to infinite pseq.

   Pattern-valued slots (list/offset/weights/lo/hi/...) resolve per
   output: sequential -> fixed vector, fn -> called per output, pattern
   map -> child stream pulled per output."
  (:require [patterns.util :as u]))

;; ------------------------------------------------------------------
;; Stream core

(defrecord Stream [state step])

(defn eop?
  "True if v is the end-of-pattern marker."
  [v]
  (u/eop? v))

(defn pstream?
  "True if x is a Stream."
  [x]
  (instance? Stream x))

(defn pattern?
  "True if x is a pattern data map."
  [x]
  (and (map? x) (contains? x :ptype)))

(defmulti build-stream
  "Build a fresh Stream for a pattern map. Dispatches on (:ptype m).
   Extend from other namespaces via (defmethod build-stream ::my-ptype [m] ...)."
  :ptype)

(defn- run-step
  "Run one step, honouring/stashing the ::pending lookahead.
   advance? true (pnext!) consumes; false (ppeek) stashes."
  [^Stream s advance?]
  (let [a (.state s)]
    (loop []
      (let [m @a]
        (if (contains? m ::pending)
          (let [v (::pending m)]
            (if advance?
              (if (compare-and-set! a m (dissoc m ::pending))
                v
                (recur))
              v))
          (let [res ((.step s) m)]
            (if (u/eop? res)
              (do (when-not advance?
                    (swap! a assoc ::pending res))
                  res)
              (let [[v m2] res]
                (if advance?
                  (if (compare-and-set! a m m2)
                    v
                    (recur))
                  (if (compare-and-set! a m (assoc m2 ::pending v))
                    v
                    (recur)))))))))))

(defn pnext!
  "Pull the next value from stream s, advancing it. Returns u/eop at the end."
  [s]
  (run-step s true))

(defn ppeek
  "Peek at the next value of stream s without advancing it."
  [s]
  (run-step s false))

(defn- snapshot-streams
  "Collect [atom saved-state] pairs for every Stream reachable from v."
  [v]
  (let [found (atom [])
        seen (atom #{})]
    (letfn [(walk [x]
              (cond
                (instance? Stream x)
                (let [a (.state ^Stream x)
                      id (System/identityHashCode a)]
                  (when-not (@seen id)
                    (swap! seen conj id)
                    (swap! found conj [a @a])
                    (walk @a)))
                (map? x) (run! walk (vals x))
                (sequential? x) (run! walk x)
                (set? x) (run! walk x)))]
      (walk v)
      @found)))

(defn- lazy-seq?
  "True for unrealized lazy seqs (possibly infinite). Vectors and lists
  are realized+indexed instead, so quoted lists still cycle."
  [x]
  (instance? clojure.lang.LazySeq x))

(defn- walk-stream
  "Stream consuming a (possibly infinite) seq head-to-tail, eop at its
  natural end. Never realizes the seq: Overtone-style infinite
  `(repeatedly f)` values work as pattern children."
  [s]
  (->Stream (atom {:s s})
            (fn [{:keys [s]}]
              (if-let [sq (seq s)]
                [(first sq) {:s (rest sq)}]
                u/eop))))

(defn as-pstream
  "Coerce x to a Stream. Stream identity; pattern map -> fresh Stream
  via build-stream; vector/list -> infinite cycling pseq; lazy seq ->
  head-to-tail walk (infinite-friendly); anything else -> infinite
  const stream."
  [x]
  (cond
    (pstream? x) x
    (pattern? x) (build-stream x)
    (lazy-seq? x) (walk-stream x)
    (sequential? x) (build-stream {:ptype ::pseq :list (vec x) :repeats :inf})
    :else (let [v x]
            (->Stream (atom {}) (fn [st] [v st])))))

(defn next-n
  "Pull up to n values from x (coerced via as-pstream), stopping before
   eop. Returns a vector; eop is never included."
  [x n]
  (let [s (as-pstream x)]
    (loop [i 0 out []]
      (if (>= i n)
        out
        (let [v (pnext! s)]
          (if (u/eop? v)
            out
            (recur (inc i) (conj out v))))))))

(defn take-events
  "Like next-n: pull up to n values, stopping before eop. Kept as the
   event-pattern entry point used by clock/pbind code."
  [x n]
  (next-n x n))

(defn peek-n
  "Peek at up to n values from stream s without advancing it (snapshot/
   restore of all reachable stream state, so nested streams are exact)."
  [s n]
  (let [snap (snapshot-streams s)]
    (try
      (loop [i 0 out []]
        (if (>= i n)
          out
          (let [v (pnext! s)]
            (if (u/eop? v)
              out
              (recur (inc i) (conj out v))))))
      (finally
        (doseq [[a saved] snap]
          (reset! a saved))))))

;; ------------------------------------------------------------------
;; Shared helpers

(defn- norm-count
  "Validate a length/repeats bound: number or :inf only."
  [v what]
  (cond
    (= :inf v) :inf
    (and (number? v) (>= v 0)) v
    :else (throw (ex-info (str "patterns: " what " must be a number or :inf")
                          {:value v}))))

(defn- remain?
  "True if remaining-count r allows another output."
  [r]
  (or (= :inf r) (and (number? r) (pos? r))))

(defn- dec-rem
  "Decrement a remaining-count (numbers only; :inf passes through)."
  [r]
  (if (number? r) (dec r) r))

(defn- resolve-coll
  "Resolve a list/weights slot to a vector. Sequential -> vec as-is;
   fn -> called (must return sequential); pattern map -> child stream is
   NOT supported here (use resolve-source); anything else throws."
  [v what]
  (cond
    (sequential? v) (vec v)
    (fn? v) (let [r (v)]
              (if (sequential? r)
                (vec r)
                (throw (ex-info (str "patterns: " what " fn must return a sequential")
                                {:value r}))))
    :else (throw (ex-info (str "patterns: " what " must be sequential or fn")
                          {:value v}))))

(defn- source-stream
  "Build a per-output resolver for a slot value: sequential -> fixed
   vector source; fn -> called per output; pattern map -> child stream
   pulled per output; plain value -> const."
  [v]
  (cond
    (sequential? v) {:fixed (vec v)}
    (pattern? v) {:stream (as-pstream v)}
    (fn? v) {:fn v}
    :else {:const v}))

(defn- pull-source
  "Pull one value from a source-stream map. Returns [value eop?]."
  [{:keys [fixed stream f const]}]
  (cond
    fixed [fixed false]
    stream (let [v (pnext! stream)] [v (u/eop? v)])
    f (let [v (f)] [v false])
    :else [const false]))

(defn- white-sample
  "Uniform sample in [lo hi]: inclusive int when both bounds are
   integers, else uniform double in [lo hi)."
  [lo hi]
  (if (and (integer? lo) (integer? hi))
    (u/rand-int-range lo hi)
    (u/rand-range lo hi)))

(defn- pick-index
  "Weighted index into n slots with cumulative weights cum (last == 1)."
  [cum]
  (let [r (u/rand-range 0.0 1.0)]
    (loop [i 0]
      (if (or (> (nth cum i) r) (= i (dec (count cum))))
        i
        (recur (inc i))))))

(defn- cumulative
  [ws]
  (let [total (apply + ws)]
    (when (or (zero? total) (neg? total))
      (throw (ex-info "patterns: weights must sum to a positive number"
                      {:weights ws})))
    (let [n (map #(/ % total) ws)]
      (second (reduce (fn [[acc out] w] [(+ acc w) (conj out (+ acc w))])
                      [0 []] n)))))

(defn- shuffle-with-rng
  "Fisher-Yates shuffle of vector v using u/*rng* (seedable)."
  [v]
  (let [a (object-array v)
        n (alength a)]
    (loop [i (dec n)]
      (when (pos? i)
        (let [j (.nextInt ^java.util.Random u/*rng* (inc i))
              t (aget a i)]
          (aset a i (aget a j))
          (aset a j t))
        (recur (dec i))))
    (vec a)))

;; ------------------------------------------------------------------
;; pseq / pser

(defn pseq
  "Sequentially yield items from coll, repeating the whole list repeats
   times (default :inf). (pseq [5 6 7] 2) -> 5 6 7 5 6 7 then eop."
  [coll & [repeats offset]]
  {:ptype ::pseq
   :list (if (sequential? coll) (vec coll) coll)
   :repeats (norm-count (if (nil? repeats) :inf repeats) "repeats")
   :offset (if (nil? offset) 0 offset)})

(defmethod build-stream ::pseq
  [{:keys [list repeats offset] :or {repeats :inf offset 0}}]
  (let [src (source-stream (if (sequential? list) (vec list) list))
        off (source-stream offset)]
    (->Stream
     (atom {:n 0 :remaining (norm-count repeats "repeats")})
     (fn [st]
      (let [{:keys [n remaining]} st
            [items items-eop?] (pull-source src)]
           (when items-eop? (throw (ex-info "patterns: pseq list stream ended" {})))
           (let [items (if (sequential? items)
                         (vec items)
                         (throw (ex-info "patterns: pseq list must resolve to a sequential"
                                         {:value items})))]
             (when (empty? items)
               (throw (ex-info "patterns: pseq list must not be empty" {})))
             (let [remaining (if (and (pos? n) (zero? (mod n (count items))))
                               (dec-rem remaining)
                               remaining)]
               (if-not (remain? remaining)
                 u/eop
                 (let [[o o-eop?] (pull-source off)]
                   (if o-eop?
                     u/eop
                     [(nth items (mod (+ n o) (count items)))
                      (assoc st :n (inc n) :remaining remaining)]))))))))))

(defn pser
  "Sequentially yield values from coll, LENGTH values total.
   (pser [5 6 7] 2) -> 5 6 then eop."
  [coll & [length offset]]
  {:ptype ::pser
   :list (if (sequential? coll) (vec coll) coll)
   :length (norm-count (if (nil? length) :inf length) "length")
   :offset (if (nil? offset) 0 offset)})

(defmethod build-stream ::pser
  [{:keys [list length offset] :or {length :inf offset 0}}]
  (let [src (source-stream (if (sequential? list) (vec list) list))
        off (source-stream offset)]
    (->Stream
     (atom {:n 0 :idx 0 :remaining (norm-count length "length")})
     (fn [st]
       (let [{:keys [n idx remaining]} st]
         (if-not (remain? remaining)
           u/eop
           (let [[items items-eop?] (pull-source src)]
             (when items-eop? (throw (ex-info "patterns: pser list stream ended" {})))
             (let [items (if (sequential? items)
                           (vec items)
                           (throw (ex-info "patterns: pser list must resolve to a sequential"
                                           {:value items})))]
               (when (empty? items)
                 (throw (ex-info "patterns: pser list must not be empty" {})))
               (let [[o o-eop?] (pull-source off)]
                 (if o-eop?
                   u/eop
                   [(nth items (mod (+ o idx) (count items)))
                    (assoc st :n (inc n) :idx (inc idx)
                           :remaining (dec-rem remaining))]))))))))))

;; ------------------------------------------------------------------
;; prand / pxrand / pwrand

(defn prand
  "Yield random values from coll, length outputs total."
  [coll & [length]]
  {:ptype ::prand
   :list (if (sequential? coll) (vec coll) coll)
   :length (norm-count (if (nil? length) :inf length) "length")})

(defn- embed-pick-step
  "Shared pull for prand/pxrand: hold a picked pattern's stream across
  pulls until it ends, then re-pick (SC list-pattern embedding). Plain
  values yield directly. `pick` draws a fresh element from ITEMS."
  [items st pick]
  (loop [sub (:sub st)]
    (if sub
      (let [v (pnext! sub)]
        (if (u/eop? v)
          (recur nil)
          [v (assoc st :sub sub :remaining (dec-rem (:remaining st)))]))
      (let [picked (pick)]
        (if (pattern? picked)
          (recur (as-pstream picked))
          [picked (assoc st :remaining (dec-rem (:remaining st)))])))))

(defmethod build-stream ::prand
  [{:keys [list length] :or {length :inf}}]
  (let [src (source-stream (if (sequential? list) (vec list) list))]
    (->Stream
     (atom {:remaining (norm-count length "length") :sub nil})
     (fn [st]
       (if-not (remain? (:remaining st))
         u/eop
         (let [[items items-eop?] (pull-source src)]
           (when items-eop? (throw (ex-info "patterns: prand list stream ended" {})))
           (let [items (if (sequential? items)
                         (vec items)
                         (throw (ex-info "patterns: prand list must resolve to a sequential"
                                         {:value items})))]
             (when (empty? items)
               (throw (ex-info "patterns: prand list must not be empty" {})))
             (embed-pick-step items st
               #(nth items (u/rand-int-range 0 (dec (count items))))))))))))

(defn pxrand
  "Yield random values from coll, never repeating equal values twice in
   a row. Static colls must hold at least two distinct elements."
  [coll & [length]]
  (when (sequential? coll)
    (let [v (vec coll)]
      (when (empty? v)
        (throw (ex-info "patterns: pxrand list must not be empty" {})))
      (assert (some #(not= % (first v)) v)
              "pxrand's input list must have at least two non-equal elements")))
  {:ptype ::pxrand
   :list (if (sequential? coll) (vec coll) coll)
   :length (norm-count (if (nil? length) :inf length) "length")})

(defmethod build-stream ::pxrand
  [{:keys [list length] :or {length :inf}}]
  (let [src (source-stream (if (sequential? list) (vec list) list))]
    (->Stream
     (atom {:remaining (norm-count length "length") :last ::none :sub nil})
     (fn [st]
       (if-not (remain? (:remaining st))
         u/eop
         (let [[items items-eop?] (pull-source src)]
           (when items-eop? (throw (ex-info "patterns: pxrand list stream ended" {})))
           (let [items (if (sequential? items)
                         (vec items)
                         (throw (ex-info "patterns: pxrand list must resolve to a sequential"
                                         {:value items})))]
             (when (empty? items)
               (throw (ex-info "patterns: pxrand list must not be empty" {})))
             (let [draw (fn [last]
                          (if (= 1 (count items))
                            (first items)
                            (loop [c (nth items (u/rand-int-range 0 (dec (count items))))]
                              (if (= c last)
                                (recur (nth items (u/rand-int-range 0 (dec (count items)))))
                                c))))]
               (loop [sub (:sub st) last (:last st)]
                 (if sub
                   (let [v (pnext! sub)]
                     (if (u/eop? v)
                       (recur nil last)
                       [v (assoc st :sub sub :last last
                                   :remaining (dec-rem (:remaining st)))]))
                   (let [picked (draw last)]
                     (if (pattern? picked)
                       (recur (as-pstream picked) picked)
                       [picked (assoc st :last picked
                                         :remaining (dec-rem (:remaining st)))]))))))))))))

(defn pwrand
  "Yield random elements from coll weighted by weights (default :equal),
   length outputs total. weights may be :equal, a sequential of numbers,
   a fn, or a pattern resolving per output to a sequential of numbers."
  [coll & [weights length]]
  {:ptype ::pwrand
   :list (if (sequential? coll) (vec coll) coll)
   :weights (if (nil? weights) :equal weights)
   :length (norm-count (if (nil? length) :inf length) "length")})

(defn- resolve-weight-elems
  "Build per-output weight resolvers for a weights slot value."
  [w]
  (cond
    (= :equal w) :equal
    (sequential? w) (mapv (fn [e]
                            (cond (number? e) {:const (double e)}
                                  (fn? e) {:fn e}
                                  (pattern? e) {:stream (as-pstream e)}
                                  :else (throw (ex-info "patterns: pwrand weight must be a number, fn, or pattern"
                                                        {:value e}))))
                          w)
    (fn? w) {:fn w}
    (pattern? w) {:stream (as-pstream w)}
    :else (throw (ex-info "patterns: pwrand weights must be :equal, sequential, fn, or pattern"
                          {:value w}))))

(defn- pull-weights
  "Resolve one weight vector for an output over n items."
  [resolvers n]
  (cond
    (= :equal resolvers) (vec (repeat n (/ 1.0 n)))
    (sequential? resolvers)
    (mapv (fn [i]
            (if (< i (count resolvers))
              (let [{:keys [const stream f]} (nth resolvers i)]
                (cond
                  (some? const) const
                  stream (let [v (pnext! stream)]
                           (when (u/eop? v)
                             (throw (ex-info "patterns: pwrand weight stream ended" {})))
                           (double v))
                  f (double (f))))
              0.0))
          (range n))
    :else (let [{:keys [stream f]} resolvers
                v (if stream
                    (let [x (pnext! stream)]
                      (when (u/eop? x)
                        (throw (ex-info "patterns: pwrand weight stream ended" {})))
                      x)
                    (f))]
            (if (sequential? v)
              (let [nums (mapv double v)]
                (if (< (count nums) n)
                  (into nums (repeat (- n (count nums)) 0.0))
                  (subvec nums 0 n)))
              (throw (ex-info "patterns: pwrand weights must resolve to a sequential of numbers"
                              {:value v}))))))

(defmethod build-stream ::pwrand
  [{:keys [list weights length] :or {weights :equal length :inf}}]
  (let [src (source-stream (if (sequential? list) (vec list) list))
        wres (resolve-weight-elems weights)]
    (->Stream
     (atom {:remaining (norm-count length "length")})
     (fn [st]
       (if-not (remain? (:remaining st))
         u/eop
         (let [[items items-eop?] (pull-source src)]
           (when items-eop? (throw (ex-info "patterns: pwrand list stream ended" {})))
           (let [items (if (sequential? items)
                         (vec items)
                         (throw (ex-info "patterns: pwrand list must resolve to a sequential"
                                         {:value items})))]
             (when (empty? items)
               (throw (ex-info "patterns: pwrand list must not be empty" {})))
             [(nth items (pick-index (cumulative (pull-weights wres (count items)))))
              (assoc st :remaining (dec-rem (:remaining st)))])))))))

;; ------------------------------------------------------------------
;; pwhite / pbrown / pexprand / pgauss

(defn pwhite
  "Uniform random numbers in [lo hi]. 0-arity -> [0.0 1.0), 1-arity ->
   [0.0 hi]. Integer bounds yield inclusive ints, else doubles."
  [& [lo hi length]]
  (let [[lo hi] (cond
                  (and (nil? lo) (nil? hi)) [0.0 1.0]
                  (nil? hi) [0.0 lo]
                  :else [lo hi])]
    {:ptype ::pwhite :lo lo :hi hi
     :length (norm-count (if (nil? length) :inf length) "length")}))

(defmethod build-stream ::pwhite
  [{:keys [lo hi length] :or {lo 0.0 hi 1.0 length :inf}}]
  (let [los (source-stream lo)
        his (source-stream hi)]
    (->Stream
     (atom {:remaining (norm-count length "length")})
     (fn [st]
       (if-not (remain? (:remaining st))
         u/eop
         (let [[nlo lo-eop?] (pull-source los)
               [nhi hi-eop?] (pull-source his)]
           (if (or lo-eop? hi-eop?)
             u/eop
             [(white-sample nlo nhi)
              (assoc st :remaining (dec-rem (:remaining st)))])))))))

(defn pbrown
  "Brownian walk in [lo hi], each step at most step from the previous,
   clamped. step defaults to 1 for integer bounds, else 0.125."
  [& [lo hi step length]]
  (let [lo-p? (some? hi)
        lo (if lo-p? lo (cond (nil? lo) 0.0 (integer? lo) 0 :else lo))
        hi (if lo-p? hi (if (nil? lo) 1.0 lo))
        step (if (some? step)
               step
               (if (and (integer? lo) (integer? hi)) 1 0.125))]
    {:ptype ::pbrown :lo lo :hi hi :step step
     :length (norm-count (if (nil? length) :inf length) "length")}))

(defmethod build-stream ::pbrown
  [{:keys [lo hi step length] :or {lo 0.0 hi 1.0 step 0.125 length :inf}}]
  (let [los (source-stream lo)
        his (source-stream hi)
        sts (source-stream step)]
    (->Stream
     (atom {:remaining (norm-count length "length") :cur ::none})
     (fn [st]
       (if-not (remain? (:remaining st))
         u/eop
         (let [[nlo lo-eop?] (pull-source los)
               [nhi hi-eop?] (pull-source his)
               [nst st-eop?] (pull-source sts)]
           (if (or lo-eop? hi-eop? st-eop?)
             u/eop
             (let [cur (if (= ::none (:cur st))
                         (white-sample (min nlo nhi) (max nlo nhi))
                         (:cur st))
                   nxt (min nhi (max nlo (+ cur (white-sample (- nst) nst))))]
               [nxt (assoc st :cur nxt :remaining (dec-rem (:remaining st)))]))))))))

(defn pexprand
  "Exponentially-distributed random numbers in [lo hi]. lo/hi must be
   nonzero and same-signed (asserted by util/rand-exp)."
  [& [lo hi length]]
  {:ptype ::pexprand
   :lo (if (nil? lo) 0.0001 lo)
   :hi (if (nil? hi) 1.0 hi)
   :length (norm-count (if (nil? length) :inf length) "length")})

(defmethod build-stream ::pexprand
  [{:keys [lo hi length] :or {lo 0.0001 hi 1.0 length :inf}}]
  (let [los (source-stream lo)
        his (source-stream hi)]
    (->Stream
     (atom {:remaining (norm-count length "length")})
     (fn [st]
       (if-not (remain? (:remaining st))
         u/eop
         (let [[nlo lo-eop?] (pull-source los)
               [nhi hi-eop?] (pull-source his)]
           (if (or lo-eop? hi-eop?)
             u/eop
             [(u/rand-exp nlo nhi)
              (assoc st :remaining (dec-rem (:remaining st)))])))))))

(defn pgauss
  "Gaussian random numbers with mean and std-deviation dev."
  [& [mean dev length]]
  {:ptype ::pgauss
   :mean (if (nil? mean) 0.0 mean)
   :dev (if (nil? dev) 1.0 dev)
   :length (norm-count (if (nil? length) :inf length) "length")})

(defmethod build-stream ::pgauss
  [{:keys [mean dev length] :or {mean 0.0 dev 1.0 length :inf}}]
  (let [ms (source-stream mean)
        ds (source-stream dev)]
    (->Stream
     (atom {:remaining (norm-count length "length")})
     (fn [st]
       (if-not (remain? (:remaining st))
         u/eop
         (let [[nm m-eop?] (pull-source ms)
               [nd d-eop?] (pull-source ds)]
           (if (or m-eop? d-eop?)
             u/eop
             [(u/rand-gauss nm nd)
              (assoc st :remaining (dec-rem (:remaining st)))])))))))

;; ------------------------------------------------------------------
;; pseries / pseries* / pgeom / pgeom*

(defn pseries
  "Yield start, then add step per output, length outputs total."
  [& [start step length]]
  {:ptype ::pseries
   :start (if (nil? start) 0 start)
   :step (if (nil? step) 1 step)
   :length (norm-count (if (nil? length) :inf length) "length")})

(defmethod build-stream ::pseries
  [{:keys [start step length] :or {start 0 step 1 length :inf}}]
  (let [ss (source-stream start)
        ps (source-stream step)]
    (->Stream
     (atom {:cur ::none :remaining (norm-count length "length")})
     (fn [st]
       (let [cur (if (= ::none (:cur st))
                   (let [[v eop?] (pull-source ss)]
                     (if eop? ::eop-val v))
                   (:cur st))]
         (cond
           (= ::eop-val cur) u/eop
           (not (remain? (:remaining st))) u/eop
           :else (let [[nxt nxt-eop?] (pull-source ps)]
                   (cond
                     nxt-eop? u/eop
                     (number? nxt) [cur (assoc st :cur (+ cur nxt)
                                                 :remaining (dec-rem (:remaining st)))]
                     :else [cur (assoc st :cur ::eop-val
                                          :remaining (dec-rem (:remaining st)))]))))))))

(defn pseries*
  "Sugar: pseries from start to end linearly in length steps. length
   defaults so the step is 1; length must be an integer >= 2."
  [& [start end length]]
  (let [start (if (nil? start) 0 start)
        end (if (nil? end) 1 end)
        length (or length (max 2 (Math/round (double (inc (abs (- end start)))))))]
    (when (or (not (integer? length)) (< length 2))
      (throw (ex-info "patterns: pseries* length must be an integer >= 2"
                      {:length length})))
    (pseries start (/ (- end start) (dec length)) length)))

(defn pgeom
  "Yield start, then multiply by grow per output, length outputs total."
  [& [start grow length]]
  {:ptype ::pgeom
   :start (if (nil? start) 1 start)
   :grow (if (nil? grow) 2 grow)
   :length (norm-count (if (nil? length) :inf length) "length")})

(defmethod build-stream ::pgeom
  [{:keys [start grow length] :or {start 1 grow 2 length :inf}}]
  (let [ss (source-stream start)
        gs (source-stream grow)]
    (->Stream
     (atom {:cur ::none :first? true :remaining (norm-count length "length")})
     (fn [st]
       (cond
         (= ::eop-val (:cur st)) u/eop
         (not (remain? (:remaining st))) u/eop
         (:first? st)
         (let [[v eop?] (pull-source ss)]
           (if eop?
             u/eop
             [v (assoc st :cur v :first? false
                       :remaining (dec-rem (:remaining st)))]))
         :else (let [[g g-eop?] (pull-source gs)]
                 (if g-eop?
                   u/eop
                   (let [nv (* (:cur st) g)]
                     [nv (assoc st :cur nv
                                :remaining (dec-rem (:remaining st)))]))))))))

(defn pgeom*
  "Sugar: pgeom from start to end exponentially in length steps.
   length must be an integer >= 2."
  [& [start end length]]
  (let [start (if (nil? start) 0.01 start)
        end (if (nil? end) 1 end)
        length (if (nil? length) 16 length)]
    (when (or (not (integer? length)) (< length 2))
      (throw (ex-info "patterns: pgeom* length must be an integer >= 2"
                      {:length length})))
    (pgeom start (Math/pow (/ end start) (/ 1.0 (dec length))) length)))

;; ------------------------------------------------------------------
;; pfunc / repeat-each / plazy / pn

(defn pfunc
  "Yield (func) per output, length outputs total. func may also be a
   pattern whose outputs are fns (each is called); non-fn outputs pass
   through as-is."
  [func & [length]]
  {:ptype ::pfunc :func func
   :length (norm-count (if (nil? length) :inf length) "length")})

(defmethod build-stream ::pfunc
  [{:keys [func length] :or {length :inf}}]
  (let [child (when-not (fn? func) (as-pstream func))]
    (->Stream
     (atom {:remaining (norm-count length "length")})
     (fn [st]
       (if-not (remain? (:remaining st))
         u/eop
         (let [f (if child
                   (let [v (pnext! child)]
                     (if (u/eop? v) ::eop-val v))
                   func)]
           (if (= ::eop-val f)
             u/eop
             [(if (fn? f) (f) f)
              (assoc st :remaining (dec-rem (:remaining st)))])))))))

(defn repeat-each
  "Repeat each value from pat repeats times. repeats may be a number, a
   pattern, or a fn (called with the current value, or 0-arity).
   Upstream name: pr — renamed because pr collides with clojure.core/pr.
   The stream `:ptype` stays ::pr."
  [pat & [repeats]]
  {:ptype ::pr :pattern pat
   :repeats (if (nil? repeats) :inf repeats)})

(defn- call-repeat-fn
  [f v]
  (try (f v)
       (catch clojure.lang.ArityException _
         (f))))

(defmethod build-stream ::pr
  [{:keys [pattern repeats] :or {repeats :inf}}]
  (let [ps (as-pstream pattern)
        rs (cond (fn? repeats) {:f repeats}
                 (pattern? repeats) {:stream (as-pstream repeats)}
                 (sequential? repeats) {:stream (as-pstream repeats)}
                 :else {:const (norm-count repeats "repeats")})]
    (->Stream
     (atom {:cur ::none :rem nil})
     (fn [st]
       (loop [cur (:cur st) rem (:rem st)]
         (if (remain? rem)
           [cur (assoc st :cur cur :rem (dec-rem rem))]
           (let [v (pnext! ps)]
             (if (u/eop? v)
               u/eop
              (let [{:keys [const stream f]} rs
                    r (cond const const
                            stream (pnext! stream)
                            f (call-repeat-fn f v))]
                 (if (u/eop? r)
                   u/eop
                   (let [r (norm-count r "repeats")]
                     (if (remain? r)
                       [v (assoc st :cur v :rem (dec-rem r))]
                       (recur ::none nil)))))))))))))

(defn plazy
  "Call (func) to build a pattern, yield it to exhaustion, then rebuild.
   Repeats the generate-and-yield cycle repeats times total."
  [func & [repeats]]
  {:ptype ::plazy :func func
   :repeats (norm-count (if (nil? repeats) :inf repeats) "repeats")})

(defmethod build-stream ::plazy
  [{:keys [func repeats] :or {repeats :inf}}]
  (->Stream
   (atom {:cur nil :remaining (norm-count repeats "repeats")})
   (fn [st]
     (loop [cur (:cur st) remaining (:remaining st)]
       (if cur
         (let [v (pnext! cur)]
           (if (u/eop? v)
             (recur nil remaining)
             [v (assoc st :cur cur :remaining remaining)]))
         (if-not (remain? remaining)
           u/eop
           (recur (as-pstream (func)) (dec-rem remaining))))))))

(defn pn
  "Embed the full subpattern pat, repeating the whole embed repeats times."
  [pat & [repeats]]
  {:ptype ::pn :pattern pat
   :repeats (norm-count (if (nil? repeats) :inf repeats) "repeats")})


(defmethod build-stream ::pn
  [{:keys [pattern repeats] :or {repeats :inf}}]
  (->Stream
   (atom {:cur nil :remaining (norm-count repeats "repeats")})
   (fn [st]
     (loop [cur (:cur st) remaining (:remaining st)]
      (let [cur (or cur (as-pstream pattern))
            v (pnext! cur)]
           (if-not (u/eop? v)
             [v (assoc st :cur cur :remaining remaining)]
             (let [remaining (dec-rem remaining)]
               (if-not (remain? remaining)
                 u/eop
                 (recur nil remaining)))))))))

;; ------------------------------------------------------------------
;; pshuf / protate / place

(defn pshuf
  "Shuffle coll, yield each shuffled item, reshuffling repeats times.
   coll may be sequential, a 0-arity fn, or a (finite) pattern to buffer."
  [coll & [repeats]]
  {:ptype ::pshuf
   :list (cond
           (sequential? coll) (vec coll)
           (fn? coll) coll
           (pattern? coll) coll
           :else (throw (ex-info "patterns: pshuf coll must be sequential, fn, or pattern"
                                 {:value coll})))
   :repeats (norm-count (if (nil? repeats) :inf repeats) "repeats")})

(defn- buffer-items
  "Realize a pshuf/protate source to a vector. Patterns buffer until eop
   (cap 100000 outputs, else throw); fns are called once."
  [coll what]
  (let [items (cond
                (sequential? coll) (vec coll)
                (fn? coll) (resolve-coll (coll) what)
                (pattern? coll) (let [s (as-pstream coll)]
                                  (loop [i 0 out []]
                                    (when (> i 100000)
                                      (throw (ex-info (str "patterns: " what " requires a finite pattern")
                                                      {})))
                                    (let [v (pnext! s)]
                                      (if (u/eop? v)
                                        out
                                        (recur (inc i) (conj out v))))))
                :else (throw (ex-info (str "patterns: " what " coll must be sequential, fn, or pattern")
                                      {:value coll})))]
    (when (empty? items)
      (throw (ex-info (str "patterns: " what " list must not be empty") {})))
    items))

(defmethod build-stream ::pshuf
  [{:keys [list repeats] :or {repeats :inf}}]
  (let [items (buffer-items list "pshuf")]
    (->Stream
     (atom {:n 0 :remaining (norm-count repeats "repeats")
            :shuffled (shuffle-with-rng items)})
     (fn [st]
       (let [{:keys [n remaining shuffled]} st
             nlen (count items)
             cycle? (and (pos? n) (zero? (mod n nlen)))
             remaining (if cycle? (dec-rem remaining) remaining)]
         (if-not (remain? remaining)
           u/eop
           (let [shuffled (if cycle? (shuffle-with-rng items) shuffled)]
             [(nth shuffled (mod n nlen))
              (assoc st :n (inc n) :remaining remaining
                     :shuffled shuffled)])))))))

(defn protate
  "Rotate finite pattern pat by shift outputs (wrapping). Buffers the
   whole pattern, so pat must be finite."
  [pat & [shift]]
  {:ptype ::protate :pattern pat :shift (if (nil? shift) 0 shift)})

(defmethod build-stream ::protate
  [{:keys [pattern shift] :or {shift 0}}]
  (let [items (buffer-items (cond (pattern? pattern) pattern
                                  (sequential? pattern) (vec pattern)
                                  (fn? pattern) pattern
                                  :else (throw (ex-info "patterns: protate needs a pattern, sequential, or fn"
                                                        {:value pattern})))
                            "protate")
        sh (source-stream shift)
        nlen (count items)]
    (->Stream
     (atom {:n 0})
     (fn [st]
       (if (or (zero? nlen) (>= (:n st) nlen))
         u/eop
         (let [[s s-eop?] (pull-source sh)]
           (if s-eop?
             u/eop
             [(nth items (mod (- (:n st) s) nlen))
              (assoc st :n (inc (:n st)))])))))))

(defn place
  "Yield each value of coll in sequence, repeats cycles total. Elements
   that are sequentials index into themselves per cycle (wrapping);
   other elements resolve via as-pstream."
  [coll & [repeats]]
  (when-not (sequential? coll)
    (throw (ex-info "patterns: place coll must be sequential" {:value coll})))
  (let [v (vec coll)]
    (when (empty? v)
      (throw (ex-info "patterns: place coll must not be empty" {})))
    (doseq [e v]
      (when (and (sequential? e) (empty? e))
        (throw (ex-info "patterns: place sublists must not be empty" {:value e}))))
    {:ptype ::place :list v
     :repeats (norm-count (if (nil? repeats) :inf repeats) "repeats")}))

(defmethod build-stream ::place
  [{:keys [list repeats] :or {repeats :inf}}]
  (let [elems (mapv (fn [e] (if (sequential? e) (vec e) (as-pstream e))) list)
        nlen (count elems)]
    (->Stream
     (atom {:n 0 :cycle 0 :remaining (norm-count repeats "repeats")})
     (fn [st]
       (let [{:keys [n cycle remaining]} st
             [cycle remaining] (if (and (pos? n) (zero? (mod n nlen)))
                                 [(inc cycle) (dec-rem remaining)]
                                 [cycle remaining])]
         (if-not (remain? remaining)
           u/eop
           (let [el (nth elems (mod n nlen))]
             (if (vector? el)
               [(nth el (mod cycle (count el)))
                (assoc st :n (inc n) :cycle cycle :remaining remaining)]
               (let [v (pnext! el)]
                 (if (u/eop? v)
                   u/eop
                   [v (assoc st :n (inc n) :cycle cycle :remaining remaining)]))))))))))
