(ns clojure-collider.server.server
  "External + NRT scsynth servers: boot/quit/sync/responders/node-watcher,
   NrtServer score collection, with-rendering offline render.
   Mirrors server.lisp server/rt-server/external-server/nrt-server core.
   Divergences: sync uses per-call CompletableFuture (5s timeout) instead of
   per-thread semaphores; sync-id map is per-server (upstream CLASS-shared)."
  (:refer-clojure :exclude [sync])
  (:require [clojure.java.shell :as shell]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure-collider.bus :as bus]
            [clojure-collider.osc.encode :as enc]
            [clojure-collider.osc.transmit :as tx]
            [clojure-collider.synth.ugen :as ugen]
            [clojure-collider.server.options :as opts]
            [clojure-collider.server.scheduler :as sched])
  (:import [java.util.concurrent ConcurrentHashMap CompletableFuture TimeUnit ConcurrentLinkedQueue]
           [java.util.concurrent.locks ReentrantLock]))
(declare server-free-all make-group send-bundle)

;; -- dynamics ------------------------------------------------------------

(def ^:dynamic *s* nil)
(def ^:dynamic *sc-synth-program* "scsynth")
(def ^:dynamic *sc-plugin-paths* nil)
(def ^:dynamic *sc-synthdefs-path*
  (str (System/getProperty "user.home") "/.local/share/SuperCollider/synthdefs/"))
(def ^:dynamic *async-in-place* nil)
(def ^:dynamic *run-level* :execute)
(def ^:dynamic *bundle-msg-container* nil)
(def ^:dynamic *bundle-synth-container* nil)
(def ^:dynamic *nrt-pad* nil)

(def ^:private boot-hooks (atom []))
(def ^:private quit-hooks (atom []))
(def ^:private all-servers (atom []))
(def server-free-all-hooks (atom []))
(def stop-hooks (atom []))

(defn add-boot-hook! [f] (swap! boot-hooks conj f) f)
(defn add-quit-hook! [f] (swap! quit-hooks conj f) f)

(def ntp-epoch-offset 2208988800)
(def ^:private sync-timeout-secs 5)

;; -- record ------------------------------------------------------------------
(defrecord NrtServer
  [name options streams id group-id default-group node-proxy-table
   node-watcher boot? buffers scheduler tempo-clock latency])

(defmethod print-method NrtServer [s ^java.io.Writer w]
  (.write w (str "#<nrt-server " (:name s) ">")))
(defrecord ExternalServer
  [name options host port just-connect?
   lock id group-id default-group
   buffers audio-buses control-buses
   scheduler tempo-clock device sc-process sc-thread boot?
   sample-rate node-watcher volume volume-control-synth
   buffer-get-handlers control-get-handlers sync-ids sync-counter
   on-free-handlers node-proxy-table])

(defmethod print-method ExternalServer [s ^java.io.Writer w]
  ;; Cycle-safe: server <-> default-group reference each other; the record
  ;; default printer recurses forever (StackOverflowError in REPL/tests).
  (.write w (str "#<external-server " (:name s) " " (:host s) ":" (:port s)
                 " boot?" (boolean (when-let [b (:boot? s)] @b)) ">")))

(defn- fresh-state [name options host port just-connect?]
  (map->ExternalServer
   {:name name :options options :host host :port port :just-connect? (boolean just-connect?)
    :lock (ReentrantLock.) :id (atom [999]) :group-id (atom 1) :default-group (atom nil)
    :buffers (atom []) :audio-buses (atom []) :control-buses (atom [])
    :scheduler (atom nil) :tempo-clock (atom nil)
    :device (atom nil) :sc-process (atom nil) :sc-thread (atom nil) :boot? (atom false)
    :sample-rate (atom nil) :node-watcher (atom nil) :volume (atom 0.0)
    :volume-control-synth (atom nil)
    :buffer-get-handlers (ConcurrentHashMap.) :control-get-handlers (ConcurrentHashMap.)
    :sync-ids (ConcurrentHashMap.) :sync-counter (atom 0)
    :on-free-handlers (ConcurrentHashMap.) :node-proxy-table (atom {})}))

(defn make-external-server
  [server-name & {:keys [options host port just-connect?]
                  :or {host "127.0.0.1" options nil}}]
  (when (nil? port)
    (throw (ex-info "server port must be specified" {:name server-name})))
  (let [s (fresh-state server-name (or options (opts/make-server-options))
                       host port just-connect?)]
    (reset! (:scheduler s) (sched/make-scheduler server-name s))
    (reset! (:tempo-clock s) (sched/make-tempo-clock server-name s))
    (swap! all-servers conj s)
    s))

(defn all-running-servers []
  (filterv #(deref (:boot? %)) @all-servers))

(defn boot? [server] (boolean @(:boot? server)))
(defn is-local? [server] (= "127.0.0.1" (:host server)))

(defn get-next-id [server]
  (first (swap! (:id server) (fn [[n]] [(inc n)]))))

;; -- messaging -----------------------------------------------------------------

(defn make-nrt-server
  "Non-realtime server: send-message/send-bundle collect [ntp-ticks messages]
   into :streams instead of touching UDP. TempoClock runs on a frozen
   timestamp (0.0); never run it. Mirrors nrt-server (server.lisp l.566)."
  [& {:keys [bpm options] :or {bpm 60.0}}]
  (let [options (or options (opts/make-server-options))
        s (map->NrtServer
             {:name "NRTSynth" :options options :streams (atom [])
              :id (atom [999]) :group-id (atom 1) :default-group (atom nil)
              :node-proxy-table (atom {}) :node-watcher (atom #{})
              :boot? (atom true) :buffers (atom []) :scheduler (atom nil)
              :tempo-clock (atom nil) :latency 0.0})]
    (reset! (:buffers s) (vec (repeat (:num-sample-buffers options) nil)))
    (reset! (:scheduler s) (sched/make-scheduler "NRTSynth" s))
    (reset! (:tempo-clock s) (sched/make-tempo-clock "NRTSynth" s
                                                    :bpm (double bpm)
                                                    :timestamp-fn (constantly 0.0)))
    (swap! all-servers conj s)
    s))
(def nrt-formats {:int16 "int16" :int24 "int24" :float "float" :double "double"})

(def nrt-cmd-names
  "Integer OSC command ids -> address strings. scsynth resolves int ids
   over UDP and inside /d_recv completions, but NOT as top-level NRT score
   messages ('Command not found'), so the score writer rewrites them."
  {9 "/s_new" 15 "/n_set"})

(defn nrt-score-message
  "Rewrite one collected message for the score file (see nrt-cmd-names)."
  [msg]
  (let [addr (first msg)]
    (if (integer? addr)
      (if-let [named (get nrt-cmd-names (int addr))]
        (assoc (vec msg) 0 named)
        (throw (ex-info "NRT score cannot encode integer command id"
                        {:id addr :message msg})))
      msg)))
(defmacro with-rendering
  "Render BODY offline: binds *s* to an NrtServer, collects the OSC score,
   writes <output>.osc, runs `scsynth -N`, deletes the score unless
   :keep-osc-file. Returns the absolute output path.
   (with-rendering [\"/tmp/x.aiff\" :sr 44100 :format :int24] (play ...)).
   Scheduler callbacks need a running clock, so prefer (at t ...) inside.
   Mirrors with-rendering (server.lisp l.582)."
  [[output & {:keys [pad keep-osc-file format sr bpm num-of-output]
              :or {format :int24 sr 44100 num-of-output 2}}]
   & body]
  `(let [file-name# (.getAbsolutePath (io/file ~output))
         dot# (.lastIndexOf file-name# ".")
         _# (when (neg? dot#)
              (throw (ex-info "with-rendering output needs an extension"
                              {:output ~output})))
         osc-file# (str (subs file-name# 0 dot#) ".osc")
         ext# (str/upper-case (subs file-name# (inc dot#)))
         fmt# (or (nrt-formats ~format)
                  (throw (ex-info "unknown NRT format" {:format ~format})))
         nrt# (make-nrt-server :bpm (double (or ~bpm (if *s* (clock-bpm) 60.0))))]
     (binding [*s* nrt# *nrt-pad* ~pad]
       (reset! (:default-group nrt#) (make-group :id 1 :pos :head :to 0 :server nrt#))
       ~@body
       (when *nrt-pad*
         (send-bundle *s* (double *nrt-pad*) [["/c_set" 0 0]]))
       (let [pad-ticks# (when ~pad (long (* (double ~pad) 4294967296.0)))
             ordered# (sort-by first @(:streams nrt#))
             kept# (if pad-ticks#
                     (take-while #(<= (first %) pad-ticks#) ordered#)
                     ordered#)]
         (with-open [out# (io/output-stream osc-file#)]
           (doseq [[tt# msgs#] kept#]
             (let [b# (enc/encode-bundle (mapv nrt-score-message msgs#) tt#)
                   n# (enc/encode-int32 (alength b#))]
               (.write out# ^java.io.OutputStream n#)
               (.write out# ^java.io.OutputStream b#)))))
       (let [plug-paths# (map #(.getAbsolutePath (io/file (str %)))
                              (or *sc-plugin-paths* []))
             pb# (doto (ProcessBuilder. ^java.util.List
                                        (into [*sc-synth-program*]
                                              (concat
                                               ;; empty -U overrides the default
                                               ;; plugin path and hides core UGens
                                               (when (seq plug-paths#)
                                                 ["-U" (str/join (str java.io.File/pathSeparatorChar)
                                                                 plug-paths#)])
                                               ["-o" (str ~num-of-output)
                                                "-N" osc-file# "_" file-name# (str ~sr) ext# fmt#])))
                       (.redirectErrorStream true)
                       (.inheritIO))
             proc# (.start pb#)
             exit# (.waitFor proc#)]
         (when-not (zero? exit#)
           (throw (ex-info "scsynth -N failed"
                           {:exit exit# :osc osc-file# :output file-name#}))))
       (when-not ~keep-osc-file
         (.delete (io/file osc-file#)))
       file-name#)))

(defn send-message [server & msg]
  (if (instance? NrtServer server)
    (send-bundle server 0.0 [(vec msg)])
    (let [dev @(:device server)]
      (when (nil? dev)
        (throw (ex-info "server has no OSC device (not booted?)" {:server (:name server)})))
      (apply tx/send-message dev msg))))

(defn send-bundle [server time messages]
  (if (instance? NrtServer server)
    ;; upstream nrt-server: push [(round (* time 2^32)) messages], no
    ;; latency, no NTP epoch (score times are relative).
    (do (swap! (:streams server) conj
                [(long (Math/round (* (double time) 4294967296.0)))
                 (mapv vec messages)])
        server)
    (let [dev @(:device server)
          tt (long (Math/round (* (+ (double time)
                                      (or (:latency server) 0.0)
                                      ntp-epoch-offset)
                                   4294967296.0)))]
      (apply tx/send-bundle dev tt messages))))

(defn install-reply-responder [server cmd f]
  (tx/add-osc-responder @(:device server) (str cmd) f))

(defn uninstall-reply-responder [server cmd]
  (tx/remove-osc-responder @(:device server) (str cmd)))

(defn add-reply-responder [cmd handler]
  (install-reply-responder *s* cmd handler))

(defn remove-reply-responder [cmd]
  (uninstall-reply-responder *s* cmd))

(defn message-distribute [synth msg server]
  (if (= :bundle *run-level*)
    (do (assert (= *s* server) "bundle mode needs *s*")
        (swap! *bundle-msg-container* conj msg)
        (swap! *bundle-synth-container* conj synth))
    (apply send-message server msg))
  synth)

;; -- sync --------------------------------------------------------------------------

(defn- reply-thread? [server]
  (let [dev @(:device server)
        lt (when dev @(:listening-thread dev))]
    (and lt (identical? (Thread/currentThread) ^Thread lt))))

(defn- clock-thread? [server]
  (let [t @(:thread @(:tempo-clock server))]
    (and t (identical? (Thread/currentThread) ^Thread t))))

(defn sync
  ([] (sync *s*))
  ([server]
   (cond (instance? NrtServer server) server
         *async-in-place* nil
         (or (reply-thread? server) (clock-thread? server))
         (binding [*out* *err*]
           (println "SYNC has no effect in reply or TempoClock thread")
           nil)
         :else
         (let [id (swap! (:sync-counter server) inc)
               fut (CompletableFuture.)]
           (.put ^ConcurrentHashMap (:sync-ids server) (int id) fut)
           (send-message server "/sync" (int id))
           (try (.get fut sync-timeout-secs TimeUnit/SECONDS)
                (catch java.util.concurrent.TimeoutException _
                  (binding [*out* *err*]
                    (println "SYNC timed out after" sync-timeout-secs "s"))
                  nil)
                (finally (.remove ^ConcurrentHashMap (:sync-ids server) (int id))))))
   server))

(defmacro with-async
  "Suppress inner sync calls; with :blocking, one sync at the end."
  [opts & body]
  `(let [res# (binding [*async-in-place* true] ~@body)]
     (when (:blocking ~opts)
       (sync))
     res#))

;; -- responders -----------------------------------------------------------------------

(defn- complete-buffer-handler [server key values]
  (let [^ConcurrentLinkedQueue q (.get ^ConcurrentHashMap (:buffer-get-handlers server) key)]
    (when q
      (when-let [h (.poll q)]
        (h (if (some? values) values (nth @(:buffers server) (second key) nil)))))))

(defn- on-async-handler [server key f]
  (let [m ^ConcurrentHashMap (:buffer-get-handlers server)]
    (.putIfAbsent m key (ConcurrentLinkedQueue.))
    (.offer ^ConcurrentLinkedQueue (.get m key) f)))

(defn await-buffer-reply
  "Register one-shot f for reply `key` ([path bufnum...]). Used by buffer.clj."
  [server key f]
  (on-async-handler server key f))

(defn- initialize-server-responder [server]
  (install-reply-responder
   server "/done"
   (fn [path & options]
     (cond (= path "/quit") (reset! (:boot? server) false)
           (and (string? path) (str/starts-with? path "/b"))
           (complete-buffer-handler server [(str path) (int (first options))] nil))))
  (install-reply-responder
   server "/status.reply"
   (fn [& args]
     (when (nil? @(:sample-rate server))
       (reset! (:sample-rate server) (nth args (- (count args) 2))))
     nil))
  (install-reply-responder
   server "/synced"
   (fn [id]
     (if (= -1 (int id))
       (reset! (:boot? server) true)
       (when-let [^CompletableFuture fut (.remove ^ConcurrentHashMap (:sync-ids server) (int id))]
         (.complete fut true)))))
  (install-reply-responder
   server "/c_set"
   (fn [bus-number value]
     (when-let [h (.remove ^ConcurrentHashMap (:control-get-handlers server) (int bus-number))]
       (h value))))
  (install-reply-responder
   server "/b_set"
   (fn [bufnum index value]
     (complete-buffer-handler server ["/b_set" (int bufnum) (int index)] value)))
  (install-reply-responder
   server "/b_setn"
   (fn [bufnum start frames & values]
     (complete-buffer-handler server ["/b_setn" (int bufnum) (int start) (int frames)]
                              (vec values))))
  (install-reply-responder
   server "/b_info"
   (fn [bufnum frames chanls sr]
     (let [bufs @(:buffers server)
           b (nth bufs (int bufnum) nil)]
       (when (some? b)
         (reset! (:buffers server)
                 (assoc bufs (int bufnum)
                        (assoc b :frames (int frames) :chanls (int chanls) :sr sr)))))))
  ;; NOTE: /fail is swallowed per upstream. Debugging trap: a rejected
  ;; command (e.g. /notify under "too many users") is otherwise invisible.
  (install-reply-responder server "/fail" (fn [& _] nil))
  (install-reply-responder
   server "/n_go"
   (fn [id & _]
     (swap! (:node-watcher server)
            (fn [w] (if (some #(= % (int id)) w) w (conj (vec w) (int id)))))))
  (install-reply-responder
   server "/n_end"
   (fn [id & _]
     (when-let [h (.remove ^ConcurrentHashMap (:on-free-handlers server) (int id))]
       (try (h) (catch Exception _ nil)))
     (swap! (:node-watcher server) (fn [w] (vec (remove #(= % (int id)) w))))))
  (install-reply-responder server "/d_removed" (fn [& _] nil))
  (install-reply-responder server "/n_move" (fn [& _] nil)))

;; -- boot/quit --------------------------------------------------------------------------

(defn- scsynth-args [server]
  (into ["-u" (str (:port server))] (opts/build-server-options (:options server))))

(defn- bootup-server-process [server]
  (when-not (:just-connect? server)
    (let [pb (doto (ProcessBuilder. ^java.util.List (into [*sc-synth-program*] (scsynth-args server)))
               (.redirectErrorStream true))
          proc (.start pb)
          drain (doto (Thread. ^Runnable
                               (fn []
                                 (let [rdr (io/reader (.getInputStream proc))]
                                   (loop [line (.readLine ^java.io.BufferedReader rdr)]
                                     (when (some? line)
                                       (binding [*out* *err*]
                                         (println (str "[" (:name server) "]") line))
                                       (recur (.readLine ^java.io.BufferedReader rdr)))))))
                  (.setDaemon true))]
      (.start drain)
      (reset! (:sc-process server) proc)
      (reset! (:sc-thread server) drain)))
  (reset! (:device server) (tx/osc-device (:host server) (:port server) :local-port 0))
  server)

(defn- cleanup-server [server]
  (when-let [^Thread t @(:sc-thread server)]
    (try (.join t 2000) (catch Exception _ nil))
    (reset! (:sc-thread server) nil))
  (when-let [^Process p @(:sc-process server)]
    (when (.isAlive p) (.destroyForcibly p))
    (reset! (:sc-process server) nil))
  (when-let [dev @(:device server)]
    (tx/close-device dev)
    (reset! (:device server) nil))
  server)


(defn server-boot [server]
  (when (boot? server)
    (throw (ex-info "server already running" {:server (:name server)})))
  (bootup-server-process server)
  (initialize-server-responder server)
  (loop [tries 0]
    (when (and (not (boot? server)) (< tries 30))
      (send-message server "/sync" (int -1))
      (Thread/sleep 30)
      (recur (inc tries))))
  (when-not (boot? server)
    (cleanup-server server)
    (throw (ex-info "server failed to boot" {:server (:name server)})))
  (reset! (:node-watcher server) [0])
  (send-message server "/notify" (int 1))
  (send-message server "/status")
  (sched/sched-run @(:scheduler server))
  (sched/tempo-clock-run @(:tempo-clock server))
  (sync server)
  (server-free-all server)
  (let [options (:options server)]
    (reset! (:id server) [999])
    (reset! (:group-id server) 1)
    (reset! (:node-proxy-table server) {})
    (reset! (:buffers server) (vec (repeat (:num-sample-buffers options) nil)))
    (reset! (:audio-buses server) (vec (repeat (:num-audio-bus options) nil)))
    (reset! (:control-buses server) (vec (repeat (:num-control-bus options) nil)))
    (dotimes [i (:num-output-bus options)]
      (bus/get-next-bus server :audio 1 i))
    (dotimes [i (:num-input-bus options)]
      (bus/get-next-bus server :audio 1 (+ i (:num-output-bus options)))))
  (.clear ^ConcurrentHashMap (:on-free-handlers server))
  (reset! (:volume server) 0.0)
  (reset! (:volume-control-synth server) nil)
  (doseq [f @boot-hooks] (f))
  server)

(defn server-quit [server]
  (when-not (boot? server)
    (throw (ex-info "server is not running" {:server (:name server)})))
  (if (:just-connect? server)
    (do (send-message server "/notify" (int 0))
        (reset! (:boot? server) false)
        (tx/close-device @(:device server))
        (reset! (:device server) nil)
        (sched/sched-stop @(:scheduler server))
        (sched/tempo-clock-stop @(:tempo-clock server)))
    (do (send-message server "/g_freeAll" (int 0))
        (send-message server "/quit")
        (loop [i 0]
          (when (and (boot? server) (< i 200))
            (Thread/sleep 25)
            (recur (inc i))))
        (reset! (:node-watcher server) nil)
        (reset! (:sample-rate server) nil)
        (sched/sched-stop @(:scheduler server))
        (sched/tempo-clock-stop @(:tempo-clock server))
        (cleanup-server server)))
  (doseq [f @quit-hooks] (f))
  server)

;; -- controls -------------------------------------------------------------------------------

(defn control-get [server index & [action]]
  (let [idx (long (float index))
        result (when (nil? action) (promise))]
    (.put ^ConcurrentHashMap (:control-get-handlers server) (int idx)
          (if action action (fn [v] (deliver result v))))
    (send-message server "/c_get" (int idx))
    (when (nil? action)
      (sync server)
      @result)))

(defn control-set [server index value]
  (send-message server "/c_set" (float index) value))

(defn server-status
  ([] (server-status *s*))
  ([server] (send-message server "/status")))

;; -- jack --------------------------------------------------------------------------------------

(defn jack-connect
  ([] (jack-connect *s* "SuperCollider" "system:capture" "system:playback"))
  ([server client-name input-name output-name]
   (when (str/starts-with? (str/lower-case (System/getProperty "os.name")) "linux")
     (let [opts (:options server)]
       (dotimes [i (:num-input-bus opts)]
         (try (shell/sh "jack_connect" (str input-name "_" (inc i))
                        (str client-name ":in_" (inc i)))
              (catch Exception _ nil)))
       (dotimes [i (:num-output-bus opts)]
         (try (shell/sh "jack_connect" (str client-name ":out_" (inc i))
                        (str output-name "_" (inc i)))
              (catch Exception _ nil)))))
   server))

;; -- nodes ----------------------------------------------------------------------------
;; Node/group/synth messaging (server.lisp node section). NRT branches and the
;; keyword-node metadata path need Phase-5 synthdef-metadata; RT paths only.

(defrecord Node [server id pos to name])
(defrecord Group [server id pos to name])

(defmethod print-method Node [n ^java.io.Writer w]
  (.write w (str "#<node :id " (:id n) " :name " (:name n) ">")))

(defmethod print-method Group [g ^java.io.Writer w]
  (.write w (str "#<group :id " (:id g) ">")))

(defn node-to-pos [pos]
  (case pos :head 0 :tail 1 :before 2 :after 3 :replace 4 0))

(defn resolve-node
  "Returns [id server-or-*s*] for a node id, Node/Group, or proxy keyword."
  [node]
  (cond (number? node) [node *s*]
        (or (instance? Node node) (instance? Group node)) [(:id node) (:server node)]
        (keyword? node) (let [n (get @(:node-proxy-table *s*) node)]
                          (when n [(:id n) *s*]))
        :else [nil *s*]))

(defmacro with-node
  "Bind [id server] from a node/id/keyword; skips body when unresolvable."
  [[id server] node & body]
  `(let [[~id ~server] (resolve-node ~node)]
     (when ~id ~@body)))

(defn- coerce-val [v]
  (cond (bus/bus? v) (float (:busnum v))
        (number? v) (ugen/floatfy v)
        (sequential? v) (mapv coerce-val v)
        :else (ugen/floatfy v)))

(defn make-synth-msg [server synth-name id to pos & args]
  (with-node [target target-server] to
    (assert (identical? server target-server) "target is not this server")
    (into [9 synth-name id (node-to-pos pos) target] args)))

(defn ctrl [node & kvs]
  (with-node [id server] node
    (let [args (mapcat (fn [[k v]]
                         [(if (number? k) k (str/lower-case (name k)))
                          (coerce-val v)])
                       (partition 2 kvs))]
      (message-distribute node (into [15 id] args) server))))

(defn map-bus [node & params]
  (with-node [id server] node
    (message-distribute node
                        (into ["/n_map" id]
                              (map (fn [p] (if (keyword? p)
                                             (str/lower-case (name p))
                                             (coerce-val p)))
                                   params))
                        server)))

(defn move-node [node target action]
  (let [command (case action :before "/n_before" :after "/n_after"
                       :head "/g_head" :tail "/g_tail")]
    (with-node [id server] node
      (with-node [target-id target-server] target
        (if (contains? #{:before :after} action)
          (message-distribute node [command id target-id] server)
          (message-distribute node [command target-id id] target-server))))))

(defn node-free [node & [server]]
  (let [server (or server *s*)]
    (with-node [id _] node
      (message-distribute node ["/n_free" id] server))))

(defn release [node]
  (ctrl node :gate 0))

(defn is-playing? [node]
  (when node
    (with-node [id server] node
      (boolean (some #(= % id) @(:node-watcher server))))))

(defn on-free [node handler]
  (with-node [id server] node
    (.put ^ConcurrentHashMap (:on-free-handlers server) (int id) handler)))

(defn make-group [& {:keys [id server pos to] :or {pos :head}}]
  (let [server (or server *s*)
        to (or to @(:default-group server))]
    (with-node [target-id target-server] to
      (assert (identical? server target-server) "target is not this server")
      (let [gid (if id id (swap! (:group-id server) inc))
            group (map->Group {:server server :id gid :pos pos :to target-id})]
        (message-distribute group ["/g_new" gid (node-to-pos pos) target-id] server)
        (sync server)
        group))))

(defn server-query-all-nodes
  ([] (server-query-all-nodes *s*))
  ([server] (send-message server "/g_dumpTree" (int 0) (int 0))))

(defn group-free-all [group]
  (with-node [id server] group
    (send-message server "/g_freeAll" (int id))))

(defn server-free-all
  ([] (server-free-all *s*))
  ([server]
   (binding [*s* server]
     (sched/sched-clear @(:scheduler server))
     (sched/tempo-clock-clear @(:tempo-clock server))
     (send-message server "/g_freeAll" (int 0))
     (send-message server "/clearSched")
     (reset! (:default-group server) (make-group :id 1 :pos :head :to 0))
     (doseq [hook @server-free-all-hooks] (hook)))
   server))

(defn stop
  ([] (stop @(:default-group *s*)))
  ([group & groups]
   (sched/sched-clear @(:scheduler *s*))
   (sched/tempo-clock-clear @(:tempo-clock *s*))
   (send-message *s* "/clearSched")
   (doseq [group (cons group groups)]
     (with-node [id _] group
       (send-message *s* "/g_freeAll" (int id))))
   (doseq [hook @stop-hooks] (hook))
   *s*))

(defmacro at
  "Collect body messages into one timestamped bundle (RT sends, NRT collects)."
  [time & body]
  `(binding [*run-level* :bundle
             *bundle-msg-container* (atom [])
             *bundle-synth-container* (atom [])]
     ~@body
     (send-bundle *s* ~time @*bundle-msg-container*)
     (ugen/unbubble (reverse @*bundle-synth-container*))))

;; -- scheduler/clock facade (server.lisp tail; RT paths) ---------------------------------

(defn callback [time f & args]
  (apply sched/sched-add @(:scheduler *s*) time f args))

(defn now []
  (sched/sched-time @(:scheduler *s*)))

(defn quant
  ([next-time] (sched/sched-quant @(:scheduler *s*) next-time 0.5))
  ([next-time offset] (sched/sched-quant @(:scheduler *s*) next-time offset)))

(defn clock-bpm
  ([] (sched/tempo-clock-bpm @(:tempo-clock *s*)))
  ([bpm] (sched/tempo-clock-bpm @(:tempo-clock *s*) bpm)))

(defn clock-beats []
  (sched/tempo-clock-beats @(:tempo-clock *s*)))

(defn clock-quant [q]
  (sched/tempo-clock-quant @(:tempo-clock *s*) q))

(defn beat-dur [beats]
  (* @(:beat-dur @(:tempo-clock *s*)) beats))

(defn dur-beat [secs]
  (* secs (/ (clock-bpm) 60.0)))

(defn clock-add [beat f & args]
  (sched/tempo-clock-add @(:tempo-clock *s*) beat (fn [] (apply f args))))

(defn clock-clear []
  (sched/tempo-clock-clear @(:tempo-clock *s*)))

(defmacro at-beat [beat & body]
  `(let [clock# @(:tempo-clock *s*)]
     (at (+ (sched/beats-to-secs clock# ~beat) @(:timing-offset clock#))
         ~@body)))

(defmacro at-task [beat & body]
  `(clock-add (+ ~beat (* (:sched-ahead (:options *s*) 0.2) (/ (clock-bpm) 60.0)))
              (fn [] ~@body)))
