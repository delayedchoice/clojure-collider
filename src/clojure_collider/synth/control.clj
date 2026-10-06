(ns clojure-collider.synth.control
  "Controls: make-control/with-controls/named-control/kr.
   Mirrors synthdef.lisp control section. Param shapes per DECISIONS.md:
   Overtone vector [freq 440 amp 0.5 gate [0 :tr]] primary, CL positional
   ((freq 440) (amp 0.5 :ar)) compat. Lag wrapping (lag.ar/lag.kr) needs
   the Phase-6 Lag UGen and throws until then."
  (:require [clojure.string :as str]
            [clojure-collider.synth.ugen :as ugen]))

;; -- param normalization ------------------------------------------------------

(def ^:private ugen-rates #{:control :audio :trig})

(defn normalize-rate
  "Overtone/CL rate keyword -> ugen rate (:ar->:audio :kr->:control
   :tr->:trig :ir->:scalar, ugen rates pass through)."
  [r]
  (cond (nil? r) nil
        (= r :ar) :audio
        (= r :kr) :control
        (= r :tr) :trig
        (= r :ir) :scalar
        (contains? ugen-rates r) r
        :else (throw (ex-info "unknown control rate" {:rate r}))))

(defn- group-of [rate]
  (cond (= rate :trig) :trig
        (= rate :audio) :audio
        :else :control))

(defn- ensure-list [x]
  (if (sequential? x) (vec x) [x]))

(defn- norm-pair
  "Overtone vector pair [sym spec] -> param map. Spec is a value,
   [value rate] or {:default v :rate r :lag l}."
  [[sym spec]]
  (let [[v r lag] (cond (and (sequential? spec) (= 2 (count spec))
                             (keyword? (second spec)))
                        [(first spec) (second spec) nil]
                        (map? spec)
                        [(:default spec) (:rate spec) (:lag spec)]
                        :else [spec nil nil])]
    {:sym sym :name (str/lower-case (name sym)) :value v :rate (normalize-rate r) :lag lag}))

(defn- norm-list
  "CL positional (name value &optional rate lag) -> param map."
  [[n v r lag]]
  {:sym (symbol (name n))
   :name (str/lower-case (name n))
   :value v
   :rate (normalize-rate (cond (= r :lag) nil
                          (and (keyword? r) (= "lag" (name r))) nil
                          :else r))
   :lag (cond (= r :lag) lag
              (and lag (not (keyword? lag))) lag
              :else nil)
   :fixed-lag? (= r :lag)})

(defn normalize-params
  "Vector (Overtone pairs) or seq-of-lists (CL) -> [param maps]."
  [params]
  (cond (vector? params)
        (do (when (odd? (count params))
              (throw (ex-info "vector params need name/default pairs" {:params params})))
            (mapv norm-pair (partition 2 params)))
        (sequential? params)
        (mapv norm-list params)
        :else (throw (ex-info "params must be vector or list of lists" {:params params}))))

;; -- validation -----------------------------------------------------------------

(defn- assert-param! [{:keys [name value rate]}]
  (when (str/blank? (str name))
    (throw (ex-info "control name must be a string" {:name name})))
  (doseq [v (ensure-list value)]
    (when (not (number? v))
      (throw (ex-info "control value must be number(s)" {:name name :value value}))))
  (when (and rate (not (contains? (conj ugen-rates :scalar :lag) rate)))
    (throw (ex-info "control rate not supported" {:name name :rate rate}))))

;; -- construction ------------------------------------------------------------------

(defn- control-proxies
  "Per-control proxy entries (unbubbled single or vector) for a stored
   Control-family ugen. Output indices run across all controls."
  [stored value-lists]
  (loop [vls value-lists i 0 out []]
    (if (empty? vls)
      out
      (let [n (count (ensure-list (first vls)))
            proxies (mapv (fn [k]
                            (ugen/map->ProxyOutput
                             {:source stored
                              :rate (:rate stored)
                              :signal-range (:signal-range stored)
                              :output-index (+ i k)
                              :synth-index -1
                              :antecedents [] :descendants []
                              :width-first-antecedents []}))
                          (range n))]
        (recur (rest vls) (+ i n) (conj out (ugen/unbubble proxies)))))))

(defn- lag-list [{:keys [lag]}]
  (if (nil? lag) [] (ensure-list lag)))

(defn- add-control-ugen!
  "Create one Control-family ugen at index `start`: appends defaults,
   bumps the count. Returns {:stored <ugen> :proxies [...]}."
  [synthdef-atom ugen-name ugen-rate start value-lists lag-inputs]
  (let [flat-vals (mapv float (mapcat ensure-list value-lists))
        built (ugen/construct ugen-name ugen-rate 'control
                              identity :bipolar start lag-inputs)
        stored (ugen/add-to-synth
                (assoc built
                       :channels (count flat-vals)
                       :inputs (vec lag-inputs)))]
    (swap! synthdef-atom
           (fn [s]
             (-> s
                 (update :controls into flat-vals)
                 (update :control-ugen-count + (count flat-vals)))))
    {:stored stored :proxies (control-proxies stored value-lists)}))

(defn make-control
  "Runtime control creation. `params` are normalized maps (see
   normalize-params). Returns proxy entries in trig/audio/plain order."
  [synthdef-atom params]
  (when (nil? synthdef-atom)
    (throw (ex-info "make-control needs *synthdef*" {})))
  (doseq [p params] (assert-param! p))
  (let [by-group (group-by (comp group-of :rate) params)
        trig (vec (get by-group :trig []))
        audio (vec (get by-group :audio []))
        plain (vec (get by-group :control []))
        order (concat trig audio plain)]
    ;; duplicate names, upstream string= check (existing + within batch)
    (loop [seen (set (map :name (:control-names @synthdef-atom))) ps order]
      (when (seq ps)
        (when (contains? seen (:name (first ps)))
          (throw (ex-info "duplicate control name" {:name (:name (first ps))})))
        (recur (conj seen (:name (first ps))) (rest ps))))
    ;; reserve control-names with running indices, upstream order
    (let [named (second
                 (reduce (fn [[idx acc] p]
                           (let [n (count (ensure-list (:value p)))]
                             [(+ idx n) (conj acc [(:name p) idx])]))
                         [(:control-ugen-count @synthdef-atom) []]
                         order))]
      (swap! synthdef-atom update :control-names into named))
    ;; one ugen per non-empty group; starts align with the name table
    (vec (mapcat (fn [ps uname urate]
                   (if (seq ps)
                     (let [start (:control-ugen-count @synthdef-atom)]
                       (:proxies (add-control-ugen!
                                  synthdef-atom uname urate start
                                  (mapv :value ps)
                                  (vec (mapcat lag-list ps)))))
                     []))
                 [trig audio plain]
                 ["TrigControl" "AudioControl"
                  (if (some :lag plain) "LagControl" "Control")]
                 [:control :audio :control]))))

(defmacro with-controls
  "Bind param symbols to control proxies. Accepts Overtone vector
   [freq 440 amp 0.5] or CL list ((freq 440) (amp 0.5 :ar)). Rates must be
   keyword literals (needed for trig/audio/plain bind order); values are
   runtime expressions."
  [params & body]
  (let [ps (cond (vector? params)
                 (mapv (fn [[s spec]]
                         (cond (and (sequential? spec) (not (map? spec))
                                    (= 2 (count spec)) (keyword? (second spec)))
                               {:sym s :name (str/lower-case (name s))
                                :value (first spec) :rate (second spec) :lag nil}
                               (map? spec)
                               {:sym s :name (str/lower-case (name s))
                                :value (:default spec) :rate (:rate spec) :lag (:lag spec)}
                               :else {:sym s :name (str/lower-case (name s))
                                      :value spec :rate nil :lag nil}))
                       (partition 2 params))
                 (sequential? params)
                 (mapv (fn [[n v r lag]]
                         {:sym (if (symbol? n) n (symbol (name n)))
                          :name (str/lower-case (name n))
                          :value v :rate r :lag lag})
                       params)
                 :else (throw (ex-info "with-controls params must be vector or list"
                                       {:params params})))
        group-key (fn [r]
                    (cond (= r :tr) 0 (= r :trig) 0
                          (= r :ar) 1 (= r :audio) 1
                          (nil? r) 2
                          (keyword? r) 2
                          :else (throw (ex-info "control rate must be a keyword literal"
                                                {:rate r}))))
        order (sort-by (comp group-key :rate) ps)
        syms (mapv :sym order)]
    `(let [[~@syms] (make-control ugen/*synthdef*
                                  [~@(map (fn [{:keys [name value rate lag]}]
                                            `{:name ~name
                                              :value ~value
                                              :rate (let [r# ~rate]
                                                      (if (= r# :lag) nil (normalize-rate r#)))
                                              :lag ~lag})
                                          order)])]
       ~@body)))

;; -- named controls ------------------------------------------------------------------

(defn- norm-name [n]
  (str/lower-case (cond (keyword? n) (name n)
                        (symbol? n) (name n)
                        :else (str n))))

(defn named-control
  ([n rate] (named-control n rate nil nil))
  ([n rate value] (named-control n rate value nil))
  ([n rate value lag]
   (let [syn ugen/*synthdef*]
     (when (nil? syn)
       (throw (ex-info "named-control needs *synthdef*" {})))
     (when (and (sequential? lag) (some #(== 0 %) lag))
       (throw (ex-info "'lagTime' has bad input" {:lag lag})))
     (let [lag (if (and (number? lag) (zero? lag)) nil lag)
           rate (case rate
                  (:kr :control) :control
                  (:ar :audio) :audio
                  (:tr :trig) :trig
                  (throw (ex-info "named-control rate must be :kr/:ar/:tr" {:rate rate})))
           nm (norm-name n)
           ;; Audio+lag always wraps in lag.ar; control+lag wraps unless
           ;; fixed (LagControl inputs). Both need the Phase-6 Lag UGen:
           ;; fail before any side effect.
           fixed-lag? (boolean (and lag (every? number? (ensure-list lag))))
           _ (when (and lag (or (= rate :audio)
                                (and (= rate :control) (not fixed-lag?))))
               (throw (ex-info "lag wrapping needs the Phase-6 Lag UGen" {:name nm})))
           reg (some #(when (= (:name %) nm) %) (:named-controls @syn))]
       (if reg
         (do (when (not (= rate (:rate reg)))
               (throw (ex-info "NamedControl: one rate per control" {:name nm})))
             (when value
               (when (not (= (ensure-list (mapv float (ensure-list value)))
                             (ensure-list (:value reg))))
                 (throw (ex-info "NamedControl: one default per control" {:name nm}))))
             (when (and lag (:fixed-lag reg))
               (when (not (= (ensure-list lag) (ensure-list (:lag reg))))
                 (throw (ex-info "NamedControl: one lag per control" {:name nm}))))
             (:ugen reg))
         (let [fixed-lag fixed-lag?]
           (when (and (= rate :control) value fixed-lag)
             (when (and (number? value) (sequential? lag) (not (= 1 (count lag))))
               (throw (ex-info "single control, multiple lags" {:name nm})))
             (when (and (sequential? value) (sequential? lag)
                        (not (= (count lag) (count value))))
               (throw (ex-info "lag/value count mismatch" {:name nm}))))
           (let [values (mapv float (ensure-list (if (nil? value) 0.0 value)))
                 lags (ensure-list lag)
                 uname (case rate
                         :control (if fixed-lag "LagControl" "Control")
                         :audio "AudioControl"
                         :trig "TrigControl")
                 urate (if (= rate :trig) :control rate)
                 start (:control-ugen-count @syn)
                 {:keys [proxies]} (add-control-ugen!
                                    syn uname urate start [values]
                                    (if fixed-lag
                                      (vec (take (count values)
                                                 (cycle (if (seq lags) lags [0.0]))))
                                      []))
                 proxy (ugen/unbubble proxies)
                 _ (swap! syn
                          (fn [s]
                            (-> s
                                (update :control-names conj [nm start])
                                (update :named-controls conj
                                        {:name nm :rate rate :value values
                                         :lag lags :fixed-lag fixed-lag
                                         :ugen proxy}))))]
             proxy)))))))

(defn kr
  ([n] (named-control n :kr))
  ([n value] (named-control n :kr value))
  ([n value lag] (named-control n :kr value lag)))
