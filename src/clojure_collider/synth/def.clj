(ns clojure-collider.synth.def
  "High-level DSL: defsynth/synth/play/proxy. Mirrors the matching
   synthdef.lisp region (ll.397-567): metadata store, *synth-definition-mode*,
   recv/load-synthdef, defsynth macro, synth fn, play macro, proxy/proxy-ctrl.
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

;; -- metadata (synthdef.lisp *synthdef-metadata*) --------------------------------

(defonce ^:private metadata-store (atom {}))

(def ^:dynamic *synth-definition-mode* :recv)
(def ^:dynamic *temp-synth-name* "temp-synth")

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
  [synthdef-atom completion]
  (let [s server/*s*]
    (assert (server/is-local? s) "load-synthdef needs a local server")
    (let [path (str (System/getProperty "java.io.tmpdir") "/"
                    (:name @synthdef-atom) ".scsyndef")]
      (synthdef/write-synthdef-file synthdef-atom path)
      (if completion
        (server/send-message s "/d_load" path completion)
        (server/send-message s "/d_load" path)))))

(defn recv-synthdef
  "Send the synthdef via /d_recv (with COMPLETION message, may be nil);
   falls back to a temp file + /d_load past the datagram limit on local
   servers, errors otherwise."
  [synthdef-atom completion]
  (let [data (synthdef/encode-synthdef synthdef-atom)]
    (if (<= (alength ^bytes data) tx/max-datagram)
      (if completion
        (server/send-message server/*s* "/d_recv" data completion)
        (server/send-message server/*s* "/d_recv" data))
      (if (server/is-local? server/*s*)
        (do (binding [*out* *err*]
              (println "synthdef too large for UDP; falling back to /d_load"))
            (load-synthdef synthdef-atom completion))
        (throw (ex-info "synthdef too large for datagram"
                        {:size (alength ^bytes data)}))))))

;; -- defsynth (synthdef.lisp l.425) -------------------------------------------------

(defmacro defsynth
  "Define a synthdef NAME with Overtone-vector params [freq 440] or CL list
   ((freq 440) (amp 0.5 :ar)). Builds the graph, sends it when a server is
   booted, returns the synthdef atom. Stashes :name/:controls/:body metadata
   at expansion, like upstream."
  [name params & body]
  (let [norm (if (vector? params)
               params
               ;; stay a seq: with-controls dispatches vector vs list
               (apply list (map (fn [p] (if (sequential? p) p (list p 0.0)))
                                params)))
        pairs (if (vector? params)
                (mapv vec (partition 2 params))
                (mapv vec norm))
        sname (clojure.core/name name)]
    (set-synthdef-metadata! sname :name name)
    (set-synthdef-metadata! sname :controls pairs)
    (set-synthdef-metadata! sname :body (vec body))
    `(let [sd# (synthdef/make-synthdef ~(str/lower-case sname))]
       (binding [ugen/*synthdef* sd#]
         (ctl/with-controls ~norm
           ~@(map conv/convert-code body)
           (synthdef/build-synthdef sd#)
           (when (and server/*s* (server/boot? server/*s*))
             (case *synth-definition-mode*
               :recv (recv-synthdef sd# nil)
               :load (load-synthdef sd# nil))
             (server/sync))
           sd#)))))

;; -- synth (synthdef.lisp l.487) ------------------------------------------------------

(defn synth
  "Start a synth by name on *s* (default group). Keyword args :id :pos :to
   select placement; the rest are control values."
  [synth-name & args]
  (let [s server/*s*
        m (apply hash-map args)
        id (or (:id m) (server/get-next-id s))
        to (or (:to m) @(:default-group s))
        pos (or (:pos m) :head)
        name-str (str/lower-case (name synth-name))
        pairs (mapcat (fn [[k v]]
                        [(str/lower-case (name k))
                         (if (number? v) (ugen/floatfy v) v)])
                      (dissoc m :id :to :pos))
        node (server/->Node s id pos to name-str)
        msg (apply server/make-synth-msg s name-str id to pos pairs)]
    (server/message-distribute node msg s)))

;; -- play (synthdef.lisp l.447) --------------------------------------------------------

(defn play-outlets
  "Fan RESULT out over BUS x GAIN (upstream play labels outlets)."
  [f bus result gain lag]
  (let [bs (if (sequential? bus) (vec bus) [bus])
        gs (if (sequential? gain) (vec gain) [gain])
        n (max (count bs) (count gs))]
    (if (and (= n 1) (number? gain))
      (f (first bs) (ops/* result (flt/var-lag:kr gain lag)))
      (doseq [i (range n)]
        (f (nth bs (mod i (count bs)))
           (ops/* (flt/var-lag:kr (nth gs (mod i (count gs))) lag)
                  result))))))

(defmacro play
  "Build a temp synth from BODY, fade it in with an EnvGen gate, start it.
   (play (sin-osc:ar 440) :out-bus 0 :gain 0.5). Scalar bodies build an
   empty synth, like upstream."
  [body & {:keys [id out-bus gain lag fade to pos]
           :or {out-bus 0 gain 1.0 lag 1.0 fade 0.02 pos :head}}]
  `(let [sd# (synthdef/make-synthdef *temp-synth-name*)]
     (binding [ugen/*synthdef* sd#]
       (let [result# ~(conv/convert-code body)]
         (when-not (= :scalar (ugen/rate result#))
           (set-synthdef-metadata! *temp-synth-name* :fade-time ~fade)
           (let [[dt# buses# gate# gain# lag#]
                 (ctl/make-control
                  sd#
                  [{:sym 'fade :name "fade" :value (float ~fade) :rate nil :lag nil}
                   {:sym 'out-bus :name "out-bus" :value (float ~out-bus) :rate nil :lag nil}
                   {:sym 'gate :name "gate" :value 1.0 :rate nil :lag nil}
                   {:sym 'gain :name "gain" :value (float ~gain) :rate nil :lag nil}
                   {:sym 'lag :name "lag" :value (float ~lag) :rate nil :lag nil}])
                 start-val# (ops/<= dt# 0)
                 env# (e/env-gen:kr (e/env [start-val# 1 0] [1 1] :lin 1)
                                    :gate gate# :time-scale dt# :act :free)
                 result2# (ops/* env# result#)]
             (case (ugen/rate result2#)
               :audio (play-outlets io/out:ar buses# result2# gain# lag#)
               :control (play-outlets io/out:kr buses# result2# gain# lag#)
               (throw (ex-info "Play: result is not a UGen" {:result result2#}))))))
       (synthdef/build-synthdef sd#)
       (let [s# server/*s*
             target# (if ~to ~to @(:default-group s#))
             node-id# (or ~id (server/get-next-id s#))
             node# (server/->Node s# node-id# ~pos target# *temp-synth-name*)
             extra# (or (synthdef-metadata *temp-synth-name* :params) [])
             pairs# (mapcat (fn [[[k# v#]]]
                              [(str/lower-case (name k#)) (ugen/floatfy v#)])
                            (partition 2 extra#))
             completion# (apply enc/encode-message
                                (apply server/make-synth-msg
                                       s# *temp-synth-name* node-id# target# ~pos pairs#))]
         (recv-synthdef sd# completion#)
         (server/sync s#)
         node#))))

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
  "Node proxy: (proxy :key body & opts). Rebuilds BODY via play under
   *temp-synth-name* = key, swaps the node-proxy-table entry, frees (or
   gate-fades) the old node. Nil BODY frees the proxy (upstream l.520)."
  [key body & {:keys [id gain fade rel pos to out-bus]
               :or {gain 1.0 fade 0.5 rel 1 pos :head out-bus 0}}]
  (let [s (gensym "s") node (gensym "node")
        alive (gensym "alive") has-fade (gensym "has-fade")
        idv (gensym "id") fadev (gensym "fade") d-key (gensym "d-key")]
    (if body
      `(let [~s server/*s*
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
         (binding [*temp-synth-name* (proxy-key-name ~key)]
           (let [new-node# (play ~body :id ~idv :out-bus ~out-bus :fade ~fadev
                                 :to ~to :pos ~pos :gain ~gain)]
             (swap! (:node-proxy-table ~s) assoc ~key new-node#)
             (when ~alive
               (if ~has-fade
                 (server/ctrl ~node :gate 0 :fade (* ~rel ~fadev))
                 (server/node-free ~node)))
             new-node#)))
      `(let [~s server/*s*
             ~node (get @(:node-proxy-table ~s) ~key)
             ~alive (boolean (when ~node
                               (if (instance? NrtServer ~s)
                                 true
                                 (server/is-playing? ~node))))]
         (when ~alive
           (server/node-free ~node))))))

(defn proxy-ctrl
  "Replace proxy KEY's node via (synth key & params); persists control
   tweaks into metadata :params and fades the old node (upstream l.546)."
  [key & params]
  (let [m (apply hash-map params)
        rel (or (:rel m) 1) fade (or (:fade m) 0.5)
        s server/*s*
        node (get @(:node-proxy-table s) key)]
    (assert node (str "can't find proxy " key))
    (let [alive? (boolean (if (instance? NrtServer s)
                            true
                            (server/is-playing? node)))
          fade-time (synthdef-metadata key :fade-time)
          new-node (apply synth key params)]
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
