(ns clojure-collider.synth.sdef
  "High-level DSL: defsynth/synth/play/proxy. Mirrors the matching
   synthdef.lisp region (ll.397-567): metadata store, recv/load-synthdef,
   defsynth macro, synth fn, play macro, proxy/proxy-ctrl.
   with-rendering (NRT) lives in clojure-collider.server.server."
  (:refer-clojure :exclude [proxy])
  (:require [clojure.string :as str]
            [clojure-collider.synth.ugen :as ugen]
            [clojure-collider.synth.control :as ctl]
            [clojure-collider.synth.convert :as conv]
            [clojure-collider.synth.synthdef :as synthdef]
            [clojure-collider.synth.operators :as ops]
            [clojure-collider.ugens.env-gen :as e]
            [clojure-collider.ugens.filter :as flt]
            [clojure-collider.ugens.in-out :as io]
            [clojure-collider.osc.encode :as enc]
            [clojure-collider.osc.transmit :as tx]
            [clojure-collider.server.server :as server])
  (:import [clojure_collider.server.server Node NrtServer ExternalServer]))

(defonce ^:private metadata-store (atom {}))

(defn- kw-key [x]
  (keyword (str/lower-case (cond (keyword? x) (name x)
                                 (symbol? x) (name x)
                                 :else (str x)))))

(defn- synth-key [s]
  (kw-key (if (instance? Node s) (:name s) s)))

(defn synthdef-metadata
  "Get metadata map for SYNTH (name/node), or the KEY entry."
  ([s] (get @metadata-store (synth-key s)))
  ([s k] (get-in @metadata-store [(synth-key s) (kw-key k)])))

(defn set-synthdef-metadata!
  "Set metadata KEY for SYNTH to VALUE."
  [s k v]
  (swap! metadata-store assoc-in [(synth-key s) (kw-key k)] v)
  v)

;; -- send (recv/load-synthdef, synthdef.lisp ll.143-157) --------------------------

(defn load-synthdef
  "Write the synthdef to a temp file and /d_load it (local server only)."
  [server synthdef-atom completion]
  (assert (server/is-local? server) "load-synthdef needs a local server")
  (let [path (str (System/getProperty "java.io.tmpdir") "/"
                  (:name @synthdef-atom) ".scsyndef")]
    (synthdef/write-synthdef-file synthdef-atom path)
    (if completion
      (server/send-message server "/d_load" path completion)
      (server/send-message server "/d_load" path))))

(defn recv-synthdef
  "Send the synthdef via /d_recv (with COMPLETION message, may be nil);
   falls back to a temp file + /d_load past the datagram limit on local
   servers, errors otherwise."
  [server synthdef-atom completion]
  (let [data (synthdef/encode-synthdef synthdef-atom)]
    (if (<= (alength ^bytes data) tx/max-datagram)
      (if completion
        (server/send-message server "/d_recv" data completion)
        (server/send-message server "/d_recv" data))
      (if (server/is-local? server)
        (do (binding [*out* *err*]
              (println "synthdef too large for UDP; falling back to /d_load"))
            (load-synthdef server synthdef-atom completion))
        (throw (ex-info "synthdef too large for datagram"
                       {:size (alength ^bytes data)}))))))

;; -- defsynth (synthdef.lisp l.425) -------------------------------------------------

(defmacro defsynth
  "Define a synthdef NAME on SERVER with Overtone-vector params [freq 440]
   or CL list ((freq 440) (amp 0.5 :ar)). Builds the graph functionally
   (no thread-local build state: every body form's value is a graph root),
   sends it via /d_recv when SERVER is booted, returns the synthdef atom.
   Stashes :name/:controls/:body metadata (at runtime, keyed by name)."
  [server name params & body]
  (let [norm (if (vector? params)
               params
               ;; stay a seq: with-controls dispatches vector vs list
               (apply list (map (fn [p] (if (sequential? p) p (list p 0.0)))
                                params)))
        pairs (if (vector? params)
                (mapv vec (partition 2 params))
                (mapv vec norm))
        converted (mapv conv/convert-code body)
        gsyms (vec (repeatedly (count converted) gensym))
        ;; bare symbols would evaluate as vars at runtime; quote them.
        ;; Keywords/strings self-evaluate; other forms evaluate normally.
        name-form (if (symbol? name) (list 'quote name) name)]
    `(let [s# ~server
            nm# (clojure.core/name ~name-form)
            sname# (str/lower-case nm#)
            sd# (synthdef/make-synthdef sname#)
            ;; every body form's value is a collection root: preserves
            ;; upstream's register-everything semantics without dynamics.
            roots# (ctl/with-controls sd# ~norm
                     (let [~@(interleave gsyms converted)]
                       [~@gsyms]))]
       (set-synthdef-metadata! nm# :name (symbol nm#))
       (set-synthdef-metadata! nm# :controls '~pairs)
       (set-synthdef-metadata! nm# :body '~body)
       (synthdef/build-synthdef sd# roots#)
       (when (and s# (server/boot? s#))
         (recv-synthdef s# sd# nil)
         (server/sync s#))
       sd#)))

;; -- synth (synthdef.lisp l.487) ------------------------------------------------------


(defn synth-msg
  "Build (not send) a synth start: returns [node msg-vector]. Pure
   constructor for bundle assembly; see send-bundle."
  [server synth-name & args]
  (let [m (apply hash-map args)
        id (or (:id m) (server/get-next-id server))
        to (or (:to m) @(:default-group server))
        pos (or (:pos m) :head)
        name-str (str/lower-case (name synth-name))
        pairs (mapcat (fn [[k v]]
                        [(str/lower-case (name k))
                         (if (number? v) (ugen/floatfy v) v)])
                      (dissoc m :id :to :pos))
        node (server/->Node server id pos to name-str)
        msg (apply server/make-synth-msg server name-str id to pos pairs)]
    [node msg]))

(defn synth
  "Start a synth by name on SERVER (default group). Keyword args :id :pos
   :to select placement; the rest are control values."
  [server synth-name & args]
  (let [[node msg] (apply synth-msg server synth-name args)]
    (server/message-distribute node msg server)))

;; -- play (synthdef.lisp l.447) --------------------------------------------------------

(defn play-outlets
  "Fan RESULT out over BUS x GAIN (upstream play labels outlets). Returns
   the built Out ugens (graph roots for build-synthdef)."
  [f bus result gain lag]
  (let [bs (if (sequential? bus) (vec bus) [bus])
        gs (if (sequential? gain) (vec gain) [gain])
        n (max (count bs) (count gs))]
    (if (and (= n 1) (number? gain))
      [(f (first bs) (ops/* result (flt/var-lag:kr gain lag)))]
      (mapv (fn [i]
              (f (nth bs (mod i (count bs)))
                 (ops/* (flt/var-lag:kr (nth gs (mod i (count gs))) lag)
                        result)))
            (range n)))))

(defn- thread-synthdef
  "Rewrite with-controls heads in FORM to take SD first: (with-controls
   params & body) -> (with-controls sd params & body). Bare and
   namespaced heads; quote forms are not descended into. Lets play
   bodies declare controls without thread-local build state."
  [sd form]
  (cond (and (seq? form) (= 'quote (first form))) form
        (and (seq? form) (symbol? (first form))
             (= "with-controls" (name (first form))))
        (list* (first form) sd (rest form))
        (seq? form) (doall (map #(thread-synthdef sd %) form))
        (vector? form) (mapv #(thread-synthdef sd %) form)
        (map? form) (into {} (map (fn [[k v]]
                                     [(thread-synthdef sd k)
                                      (thread-synthdef sd v)])
                                   form))
        :else form))

(defmacro play
  "Build a temp synth from BODY on SERVER, fade it in with an EnvGen
   gate, start it. (play server (sin-osc:ar 440) :out-bus 0 :gain 0.5).
   Scalar bodies build an empty synth, like upstream. with-controls forms
   in BODY are threaded the in-progress synthdef automatically."
  [server body & {:keys [id out-bus gain lag fade to pos name]
                  :or {out-bus 0 gain 1.0 lag 1.0 fade 0.02 pos :head
                       name "temp-synth"}}]
  (let [sd-sym (gensym "sd")]
    `(let [s# ~server
            nm# ~name
            ~sd-sym (synthdef/make-synthdef nm#)
            result# ~(thread-synthdef sd-sym (conv/convert-code body))]
       (when-not (= :scalar (ugen/rate result#))
         (set-synthdef-metadata! nm# :fade-time ~fade)
         (let [[dt# buses# gain# lag# gate#]
               (ctl/make-control
                ~sd-sym
                [{:sym 'fade :name "fade" :value (float ~fade) :rate nil :lag nil}
                 {:sym 'out-bus :name "out-bus" :value (float ~out-bus) :rate nil :lag nil}
                 {:sym 'gate :name "gate" :value 1.0 :rate nil :lag nil}
                 {:sym 'gain :name "gain" :value (float ~gain) :rate nil :lag nil}
                 {:sym 'lag :name "lag" :value (float ~lag) :rate nil :lag nil}])
               start-val# (ops/<= dt# 0)
               env# (e/env-gen:kr (e/env [start-val# 1 0] [1 1] :lin 1)
                                  :gate gate# :time-scale dt# :act :free)
               result2# (ops/* env# result#)
               outs# (case (ugen/rate result2#)
                       :audio (play-outlets io/out:ar buses# result2# gain# lag#)
                       :control (play-outlets io/out:kr buses# result2# gain# lag#)
                       (throw (ex-info "Play: result is not a UGen" {:result result2#})))]
           (synthdef/build-synthdef ~sd-sym outs#)
           (let [target# (if ~to ~to @(:default-group s#))
                 node-id# (or ~id (server/get-next-id s#))
                 node# (server/->Node s# node-id# ~pos target# nm#)
                 extra# (or (synthdef-metadata nm# :params) [])
                 pairs# (mapcat (fn [[[k# v#]]]
                                  [(str/lower-case (name k#)) (ugen/floatfy v#)])
                                (partition 2 extra#))
                 completion# (apply enc/encode-message
                                    (apply server/make-synth-msg
                                           s# nm# node-id# target# ~pos pairs#))]
             (recv-synthdef s# ~sd-sym completion#)
             (server/sync s#)
             node#))))))

;; -- proxy (synthdef.lisp l.520) -------------------------------------------------------

(defn get-controls-list
  "Scan FORM for (with-controls ...) and return its params, or nil.
   Matches bare and namespace-qualified heads (ctl/with-controls)."
  [form]
  (cond (nil? form) nil
        (sequential? form)
        (if (and (seq form) (symbol? (first form))
                 (= "with-controls" (name (first form))))
          (second form)
          (some get-controls-list (rest form)))
        :else nil))

(defn proxy-key-name
  "Normalize a proxy KEY to its downcased temp-synth name."
  [k]
  (str/lower-case (cond (keyword? k) (name k)
                        (symbol? k) (name k)
                        :else (str k))))
(defn- flat-params-assoc
  "Assoc V under K in flat [k v k v ...] params (played by play, l.176)."
  [pvs k v]
  (let [vs (vec pvs)
        kn (keyword (str/lower-case (name k)))
        i (.indexOf ^java.util.List
                    (mapv #(when (keyword? %) (keyword (str/lower-case (name %))))
                          (take-nth 2 vs))
                    kn)]
    (if (neg? i) (conj vs k v) (assoc vs (inc (* 2 i)) v))))

(defmacro proxy
  "Node proxy: (proxy server :key body & opts). Rebuilds BODY via play
   under the key's temp-synth name, swaps the node-proxy-table entry,
   frees (or gate-fades) the old node. Nil BODY frees the proxy
   (upstream l.520)."
  [server key body & {:keys [id gain fade rel pos to out-bus]
                      :or {gain 1.0 fade 0.5 rel 1 pos :head out-bus 0}}]
  (let [s (gensym "s") node (gensym "node")
        alive (gensym "alive") has-fade (gensym "has-fade")
        idv (gensym "id") fadev (gensym "fade") d-key (gensym "d-key")]
    (if body
      `(let [~s ~server
             ~node (get @(:node-proxy-table ~s) ~key)
             ~alive (boolean (when ~node
                               (if (instance? NrtServer ~s)
                                 true
                                 (server/is-playing? ~node))))
             ~has-fade (synthdef-metadata ~key :fade-time)
             ~idv ~id
             ~fadev ~fade]
         (when (and ~idv (instance? ExternalServer ~s)
                    (boolean (some #{~idv} @(:node-watcher ~s))))
           (throw (ex-info "already running id" {:id ~idv})))
         (let [~d-key (proxy-key-name ~key)]
           (set-synthdef-metadata! ~d-key :name ~d-key)
           (set-synthdef-metadata! ~d-key :controls
             (ctl/normalize-params (or (get-controls-list '~body) [])))
           (set-synthdef-metadata! ~d-key :body '~body))
         (let [new-node# (play ~s ~body :id ~idv :out-bus ~out-bus :fade ~fadev
                               :to ~to :pos ~pos :gain ~gain :name (proxy-key-name ~key))]
             (swap! (:node-proxy-table ~s) assoc ~key new-node#)
             (when ~alive
               (if ~has-fade
                 (server/ctrl ~node :gate 0 :fade (* ~rel ~fadev))
                 (server/node-free ~node)))
            new-node#))
      `(let [~s ~server
             ~node (get @(:node-proxy-table ~s) ~key)
             ~alive (boolean (when ~node
                               (if (instance? NrtServer ~s)
                                 true
                                 (server/is-playing? ~node))))]
         (when ~alive
           (server/node-free ~node))))))

(defn proxy-ctrl
  "Replace proxy KEY's node on SERVER via (synth server key & params);
   persists control tweaks into metadata :params and fades the old node
   (upstream l.546)."
  [server key & params]
  (let [m (apply hash-map params)
        rel (or (:rel m) 1) fade (or (:fade m) 0.5)
        s server
        node (get @(:node-proxy-table s) key)]
    (assert node (str "can't find proxy " key))
    (let [alive? (boolean (if (instance? NrtServer s)
                            true
                            (server/is-playing? node)))
          fade-time (synthdef-metadata key :fade-time)
          new-node (apply synth s key params)]
      (doseq [[k v] (partition 2 params)]
        (let [kn (str/lower-case (name k))
              known? (some #(and (= kn (:name %))
                                 (not (contains? #{:trig} (:rate %))))
                           (or (synthdef-metadata key :controls) []))]
          (when (or known? (contains? #{:out-bus :gain} (keyword kn)))
            (set-synthdef-metadata! key :params
              (flat-params-assoc
               (or (synthdef-metadata key :params) []) k v)))))
      (swap! (:node-proxy-table s) assoc key new-node)
      (when alive?
        (if fade-time
          (server/ctrl node :gate 0 :fade (* rel fade))
          (server/node-free node)))
      new-node)))
