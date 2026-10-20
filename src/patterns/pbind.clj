(ns patterns.pbind
  "Event patterns: pbind, pmono, prest, pb, and the pdef registry.

  Port of defaultxr/cl-patterns v0.7 `pbind` (patterns.lisp) and `pdef`
  (pdef.lisp). Patterns are data maps; streams come from
  patterns.patterns (`build-stream` / `as-pstream` / `pnext!`).

  A pbind map looks like
    {:ptype :patterns.patterns/pbind
     :pairs [k1 v1 k2 v2 ...]   ; order matters (left-to-right)
     :fns   #{k...}             ; keys whose value is (fn [ev] ...)
     :loop  nil}                ; :loop override flag, kept for the clock

  Each pair value is turned into a child stream once, when the pbind
  stream is built. Each step pulls every child left-to-right with
  `patterns.event/*event*` bound to the partially built event map, so
  later keys can read earlier ones. ANY child yielding end-of-pattern
  ends the whole pbind step.

  Special pair keys:
  - `:embed` with a plain map splices that map's entries into the pairs
    at build time. With any other value it is streamed per step and each
    yielded map is merged into the event via `combine-events`.
  - `:loop` is stored on the pattern map as a loop override for the
    clock slice (replay policy); it does not change stepping.
  - Plain `(fn [ev] ...)` values are called each step with the partial
    event map (which is also an IFn, so `(e :dur)` works).
  - `prest` markers pulled for a key set that key to the marker's value
    and force `:type :rest` (silence; the backend checks
    `(= :rest (:type ev))`).
  - A merged map containing `:instrument` or `:type` is passed through
    the event constructor, so the result is a full event map.

  Deferred (upstream `pbind` wrap keys): `:pfor :pfin :pfindur :psync
  :pr :pn :ptrace :pmeta :pchain`. Passing any of these to `pbind`
  throws with a `:deferred` marker; likewise `pb`'s arithmetic
  auto-translation (`+` -> `p+` etc.) is NOT ported. Both are documented
  here so a later slice can pick them up without guessing."
  (:require [patterns.event :as event]
            [patterns.patterns :as p]))

(set! *warn-on-reflection* true)

;; End-of-pattern keyword (binding contract). Spelled literally so this ns
;; never depends on which var name patterns.util gives it.
(def ^:private eop
  :patterns.util/eop)


;; ---------------------------------------------------------------------------
;; prest: rest marker (upstream: not a pattern, just a marker object)

(defrecord Prest [value])

(defn prest
  "Make a rest marker holding VALUE (default 1). When a pbind child yields
  this for a key, the event keeps VALUE for that key but its `:type`
  becomes `:rest`, which the backend renders as silence.
  Upstream: `(prest &optional (value 1))`."
  ([] (->Prest 1))
  ([v] (->Prest v)))

(defn prest?
  "True when X is a `prest` rest marker."
  [x]
  (instance? Prest x))

;; ---------------------------------------------------------------------------
;; pdef registry (upstream pdef.lisp essentials; play/stop live in clock)

(defonce ^:private pdef-registry
  (atom {}))

(defn pdef
  "Register PATTERN under NAME, returning PATTERN. Called with just NAME,
  returns the registered pattern (nil when unknown).
  Playing state and play/stop/end live in patterns.clock."
  ([name]
   (get @pdef-registry name))
  ([name pattern]
   (swap! pdef-registry assoc name pattern)
   pattern))

(defn find-pdef
  "Return the pattern registered under NAME, or nil."
  [name]
  (get @pdef-registry name))

(defn all-pdefs
  "Snapshot of the name -> pattern registry map."
  []
  @pdef-registry)

(defn pdef-pattern
  "Return the pattern for X: pattern maps (anything with `:ptype`) pass
  through, anything else is looked up with `find-pdef`."
  [x]
  (if (and (map? x) (contains? x :ptype))
    x
    (find-pdef x)))

;; ---------------------------------------------------------------------------
;; pbind

(def ^:private deferred-keys
  "Upstream pbind wrap keys deferred to a later slice."
  #{:pfor :pfin :pfindur :psync :pr :pn :ptrace :pmeta :pchain})

(defn pbind
  "Build an event pattern from key/value PAIRS. Each value becomes a child
  stream (plain `(fn [ev] ...)` values are called per step instead); each
  step yields one event map. See ns docstring for `:embed`, `:loop`,
  `prest`, deferred keys, and end conditions."
  [& pairs]
  (when (odd? (count pairs))
    (throw (ex-info "pbind requires an even number of arguments (key/value pairs)"
                    {:pairs pairs})))
  (loop [kvs   (partition 2 pairs)
         faced []          ; flat [k v ...] in encounter order
         fns   #{}
         loop? nil]
    (if (empty? kvs)
      {:ptype ::p/pbind
       :pairs faced
       :fns   fns
       :loop  loop?}
      (let [[k v]    (first kvs)
            rest-kvs (rest kvs)]
        (cond
          (contains? deferred-keys k)
          (throw (ex-info (str "pbind key " k " is deferred to a later slice")
                          {:key k :deferred true}))

          (= k :loop)
          (recur rest-kvs faced fns (boolean v))

          ;; Plain map only: records (Stream, Prest) and pattern maps
          ;; (:ptype) stream per step instead.
          (and (= k :embed) (map? v) (not (record? v)) (not (contains? v :ptype)))
          (recur rest-kvs (into faced (apply concat (seq v))) fns loop?)

          (fn? v)
          (recur rest-kvs (conj faced k v) (conj fns k) loop?)

          :else
          (recur rest-kvs (conj faced k v) fns loop?))))))

(defn pmono
  "Mono event pattern: like `pbind` with `:instrument` set and
  `:type :mono`. Upstream `(pmono instrument &rest pairs)`."
  [instrument & pairs]
  (apply pbind :instrument instrument :type :mono pairs))

(defmacro pb
  "Convenience macro over `pdef`/`pbind` (upstream `pb`, minus arithmetic
  auto-translation, which is deferred). With a single VALUE arg, registers
  it: `(pb :name pattern-map)`. Otherwise builds and registers a pbind:
  `(pb :name :k v ...)`. Returns the registered pattern."
  [name & pairs]
  (if (= 1 (count pairs))
    `(pdef ~name ~(first pairs))
    `(pdef ~name (pbind ~@pairs))))

;; ---------------------------------------------------------------------------
;; stream: one generic Stream, children pulled left-to-right

(defn- finalize-event
  "Turn a merged step map into the yielded value: maps carrying
  `:instrument` or `:type` go through the event constructor (full event
  map); anything else is returned as built."
  [m]
  (if (or (contains? m :instrument) (contains? m :type))
    (apply event/event (apply concat (seq m)))
    m))

(defn- pbind-step
  "Step fn closing over CHILDREN ([k child] vector; fn values stay fns).
  Returns `[event state]` or the eop keyword when any child ends.
  STATE is passed through untouched so patterns' `::pending` lookahead
  keys survive."
  [children]
  (fn [st]
    (loop [cs  children
           acc {}]
      (if (empty? cs)
        [(finalize-event acc) st]
        ;; NOTE: the pull stays inside `binding` but every `recur` sits
        ;; outside it: `binding` expands to try/finally, which `recur`
        ;; may not cross.
        (let [[k child] (first cs)
              more      (rest cs)
              v         (binding [event/*event* acc]
                          (if (fn? child) (child acc) (p/pnext! child)))]
          (cond
            (p/eop? v)
            eop

            (prest? v)
            (recur more (assoc acc k (:value v) :type :rest))

            (= k :embed)
            (if (map? v)
              (recur more (event/combine-events acc v))
              (throw (ex-info ":embed child must yield a map per step"
                              {:yielded v})))

            :else
            (recur more (assoc acc k v))))))))

(defmethod p/build-stream ::p/pbind
  [{:keys [pairs]}]
  (let [children (mapv (fn [[k v]] [k (if (fn? v) v (p/as-pstream v))])
                       (partition 2 pairs))]
    (p/->Stream (atom {:children children}) (pbind-step children))))
