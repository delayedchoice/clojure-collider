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
            [clojure-collider.server.scheduler :as sched]
            [clojure-collider.server.silent :as silent])
  (:import [java.util.concurrent ConcurrentHashMap CompletableFuture TimeUnit ConcurrentLinkedQueue]
           [java.util.concurrent.locks ReentrantLock]
           [java.net DatagramSocket DatagramPacket InetAddress]))
(declare server-free-all make-group send-bundle)

;; -- shared registries (atoms are fine: no thread-local state) -----------------------

(def ^:private boot-hooks (atom []))
(def ^:private quit-hooks (atom []))
(def ^:private all-servers (atom []))
(def ^:private boot-logs
  "Recent scsynth stderr lines per server name (ring of 40). Boot reads
  these so a failed boot can report scsynth's own reason instead of a
  bare failure. Dissoc'd on cleanup."
  (atom {}))
(defn- log-boot-line! [server line]
  (swap! boot-logs update (:name server)
         (fn [v] (let [v (conj (or v []) line)]
                   (if (> (count v) 40) (subvec v 1) v)))))
(def server-free-all-hooks (atom []))
(def stop-hooks (atom []))

(defn add-boot-hook! [f] (swap! boot-hooks conj f) f)
(defn add-quit-hook! [f] (swap! quit-hooks conj f) f)

(def ntp-epoch-offset 2208988800)
(def ^:private sync-timeout-secs 5)

;; -- record ------------------------------------------------------------------
(defrecord NrtServer
  [name options streams lock id group-id default-group node-proxy-table
   node-watcher boot? buffers audio-buses control-buses
   scheduler tempo-clock latency])

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
              :lock (ReentrantLock.)
              :id (atom [999]) :group-id (atom 1) :default-group (atom nil)
              :node-proxy-table (atom {}) :node-watcher (atom #{})
              :boot? (atom true) :buffers (atom [])
              :audio-buses (atom []) :control-buses (atom [])
              :scheduler (atom nil)
              :tempo-clock (atom nil) :latency 0.0})]
    (reset! (:buffers s) (vec (repeat (:num-sample-buffers options) nil)))
    (reset! (:audio-buses s) (vec (repeat (:num-audio-bus options) nil)))
    (reset! (:control-buses s) (vec (repeat (:num-control-bus options) nil)))
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
  "Render BODY offline: lexically binds SYM to an NrtServer, collects the
   OSC score, writes <output>.osc, runs `scsynth -N`, deletes the score
   unless :keep-osc-file. Returns the absolute output path.
   (with-rendering [s \"/tmp/x.aiff\" :sr 44100 :format :int24] (play s ...)).
   Mirrors with-rendering (server.lisp l.582)."
  [[sym output & {:keys [pad keep-osc-file format sr bpm num-of-output]
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
         ~sym (make-nrt-server :bpm (double (or ~bpm 60.0)))]
     (reset! (:default-group ~sym) (make-group :id 1 :pos :head :to 0 :server ~sym))
     ~@body
     (when ~pad
       (send-bundle ~sym (double ~pad) [["/c_set" 0 0]]))
     (let [pad-ticks# (when ~pad (long (* (double ~pad) 4294967296.0)))
            ordered# (sort-by first @(:streams ~sym))
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
                            (or (:plugin-paths (:options ~sym)) []))
           pb# (doto (ProcessBuilder. ^java.util.List
                                      (into [(or (:sc-synth-program (:options ~sym)) "scsynth")]
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
     file-name#))

(defn send-message [server & msg]
  (if (instance? NrtServer server)
    (send-bundle server 0.0 [(vec msg)])
    (let [dev @(:device server)]
      (when (nil? dev)
        (throw (ex-info "server has no OSC device (not booted?)" {:server (:name server)})))
      (apply tx/send-message dev msg))))

(defn ntp-timetag
  "Unix-epoch seconds (+ server latency) -> unsigned 64-bit NTP timetag
  (as a long whose BITS are exact; values past Long/MAX read negative,
  which putLong emits correctly). Split seconds/fraction: a saturating
  double->long would park bundles in year 2262."
  [unix-secs latency]
  (let [t (+ (double unix-secs) (double (or latency 0.0)) ntp-epoch-offset)
        secs (long (Math/floor t))
        frac (long (* (- t secs) 4294967296.0))]
    (bit-or (bit-shift-left secs 32) frac)))

(defn send-bundle [server time messages]
  (if (instance? NrtServer server)
    ;; upstream nrt-server: push [(round (* time 2^32)) messages], no
    ;; latency, no NTP epoch (score times are relative).
    (do (swap! (:streams server) conj
                [(long (Math/round (* (double time) 4294967296.0)))
                 (mapv vec messages)])
        server)
    ;; See ntp-timetag: split seconds/fraction so live bundles land now,
    ;; not in year 2262.
     (let [dev @(:device server)
           _ (when (nil? dev)
               (throw (ex-info "server has no OSC device (quit?)" {:server (:name server)})))
           tt (ntp-timetag time (:latency server))]
       (apply tx/send-bundle dev tt messages))))

(defn install-reply-responder [server cmd f]
  (tx/add-osc-responder @(:device server) (str cmd) f))

(defn uninstall-reply-responder [server cmd]
  (tx/remove-osc-responder @(:device server) (str cmd)))

(defn add-reply-responder [server cmd handler]
  (install-reply-responder server cmd handler))

(defn remove-reply-responder [server cmd]
  (uninstall-reply-responder server cmd))

(defn message-distribute [synth msg server]
  (apply send-message server msg)
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
  [server]
  (cond (instance? NrtServer server) server
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
  server)

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

(defn- scsynth-args
  "CLI args for scsynth. Reads sc config from (:options server):
   :sc-synth-program is applied by the caller (ProcessBuilder command),
   :plugin-paths (nil -> []) overrides the -U plugin path entry,
   :synthdefs-path is excluded here (ensured to exist in server-boot).
   Silent-boot args (see silent/silent-boot-args) supply :device and any
   :extra-args when silent/auto-silence? holds for these options."
  [server]
  (let [opts (into {} (:options server))
        plug-paths (or (:plugin-paths opts) [])
        silent (silent/silent-boot-args opts)
        opts (cond-> (dissoc opts :plugin-paths :sc-synth-program :synthdefs-path)
               silent (merge (select-keys silent [:device])))]
    (cond-> (into ["-u" (str (:port server))] (opts/build-server-options opts))
      (seq plug-paths) (conj "-U" (str/join (str java.io.File/pathSeparatorChar)
                                            (map str plug-paths)))
      (:extra-args silent) (into (:extra-args silent)))))

(defn- check-port-free! [server]
   ;; Fail fast when PORT is already bound (usually another scsynth):
   ;; without this the new process dies on bind while the old server
   ;; answers our /sync (UDP cross-talk), and boot? comes up true with
   ;; no process behind it.
   (try (with-open [_ (DatagramSocket. (int (:port server)))] nil)
        (catch java.net.BindException _
          (throw (ex-info (str "port " (:port server) " already in use")
                         {:server (:name server) :port (:port server)})))))
 (defn- bootup-server-process [server]
  (when-not (:just-connect? server)
   (let [program (or (:sc-synth-program (:options server)) "scsynth")
         pb (doto (ProcessBuilder. ^java.util.List (into [program] (scsynth-args server)))
               (.redirectErrorStream true))
          proc (.start pb)
          drain (doto (Thread. ^Runnable
                                (fn []
                                  (let [rdr (io/reader (.getInputStream proc))]
                                    (loop [line (.readLine ^java.io.BufferedReader rdr)]
                                      (when (some? line)
                                        (log-boot-line! server line)
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
   (swap! boot-logs dissoc (:name server))
   ;; The process is gone by now (destroyed above if it was wedged), so a
   ;; boot? left true by a lost /quit reply must not survive: it makes
   ;; everything downstream believe in a server that has no device.
   (reset! (:boot? server) false)
   server)


(defn server-boot [server]
  (when (boot? server)
    (throw (ex-info "server already running" {:server (:name server)})))
  ;; sc config lives in (:options server): ensure the synthdef directory
  ;; exists and resolve silent-boot overrides (silent slice owns the
  ;; opts-first shapes of auto-silence?/silent-boot-args).
  (when-let [sd (:synthdefs-path (:options server))]
    (.mkdirs (io/file (str sd))))
  (let [silent? (silent/auto-silence? (:options server))
        silent-args (silent/silent-boot-args (:options server))]
    (when (or silent? silent-args)
      (log-boot-line! server "silent boot overrides applied")))
  (when-not (:just-connect? server) (check-port-free! server))
  (bootup-server-process server)
  (initialize-server-responder server)
  ;; Upstream retries bootup while the scsynth thread is alive (server.lisp
  ;; bootup recurses when the thread lives); we mirror that rather than the
  ;; literal 30 tries, since CoreAudio device scans can exceed 0.9s. Cap at
  ;; ~30s so a wedged-but-alive process still fails. No local process
  ;; (:just-connect?) keeps a short leash.
  (let [proc @(:sc-process server)
        budget (if (nil? proc) 30 1000)]
    (loop [tries 0]
      (when (and (not (boot? server))
                 (< tries budget)
                 (or (nil? proc) (.isAlive ^Process proc)))
        (send-message server "/sync" (int -1))
        (Thread/sleep 30)
        (recur (inc tries)))))
  (when-not (boot? server)
     (let [tail (take-last 10 (get @boot-logs (:name server) []))]
       (cleanup-server server)
       (throw (ex-info (str "server failed to boot"
                            (when (seq tail) (str "\nscsynth said:\n" (str/join "\n" tail))))
                       {:server (:name server) :scsynth-log tail}))))
  ;; Our own process died while another server on the port answered /sync
  ;; (UDP cross-talk): boot? is true but there is no server. Fail loudly.
  (let [proc @(:sc-process server)]
     (when (and proc (not (.isAlive ^Process proc)))
       (let [tail (take-last 10 (get @boot-logs (:name server) []))]
         (cleanup-server server)
         (throw (ex-info (str "server process died during boot"
                              (when (seq tail) (str "\nscsynth said:\n" (str/join "\n" tail))))
                         {:server (:name server) :scsynth-log tail})))))
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
  ;; Ctrl+C / System/exit otherwise orphans a ringing scsynth (no language
  ;; side left to free it). Kill our own spawn on JVM exit; a just-connect?
  ;; record is foreign - never touch its process. cleanup-server (not a
  ;; full quit) is idempotent and never blocks the shutdown.
  (when-not (:just-connect? server)
    (try (.. Runtime getRuntime
             (addShutdownHook (Thread. (fn []
                                        (try (cleanup-server server)
                                             (catch Throwable _))))))
         (catch Throwable _)))
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
  [server]
  (send-message server "/status"))

;; -- jack --------------------------------------------------------------------------------------

(defn jack-connect
  ([server] (jack-connect server "SuperCollider" "system:capture" "system:playback"))
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
  "Returns [id server] for a Node/Group record, else [nil nil]. The server
   always comes from the record itself; plain ids/keywords carry no server."
  [node]
  (if (or (instance? Node node) (instance? Group node))
    [(:id node) (:server node)]
    [nil nil]))

(defmacro with-node
  "Bind [id server] from a Node/Group record; skips body when unresolvable."
  [[id server] node & body]
  `(let [[~id ~server] (resolve-node ~node)]
     (when (and ~id ~server) ~@body)))

(defn- coerce-val [v]
  (cond (bus/bus? v) (float (:busnum v))
        (number? v) (ugen/floatfy v)
        (sequential? v) (mapv coerce-val v)
        :else (ugen/floatfy v)))

(defn make-synth-msg [server synth-name id to pos & args]
  (let [target (if (number? to) to (:id to))
        target-server (if (number? to) server (:server to))]
    (assert (identical? server target-server) "target is not this server")
    (into [9 synth-name id (node-to-pos pos) target] args)))

(defn ctrl-msg
  "Pure message vector for (ctrl node & kvs)."
  [node & kvs]
  (let [[id _] (resolve-node node)
        args (mapcat (fn [[k v]]
                       [(if (number? k) k (str/lower-case (name k)))
                        (coerce-val v)])
                     (partition 2 kvs))]
    (into [15 id] args)))

(defn ctrl [node & kvs]
  (with-node [_ server] node
    (message-distribute node (apply ctrl-msg node kvs) server)))

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

(defn node-free-msg
  "Pure message vector for (node-free node)."
  [node & _]
  ["/n_free" (:id node)])

(defn node-free [node]
  (with-node [_ server] node
    (message-distribute node (node-free-msg node) server)))

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
  (assert (some? server) "make-group needs an explicit :server")
  (let [to (or to @(:default-group server))
        target-id (if (number? to) to (:id to))
        target-server (if (number? to) server (:server to))]
    (assert (identical? server target-server) "target is not this server")
    (let [gid (if id id (swap! (:group-id server) inc))
          group (map->Group {:server server :id gid :pos pos :to target-id})]
      (message-distribute group ["/g_new" gid (node-to-pos pos) target-id] server)
      (sync server)
      group)))

(defn server-query-all-nodes
  [server]
  (send-message server "/g_dumpTree" (int 0) (int 0)))

(defn group-free-all [group]
  (with-node [id server] group
    (send-message server "/g_freeAll" (int id))))

(defn server-free-all
  [server]
  (sched/sched-clear @(:scheduler server))
  (sched/tempo-clock-clear @(:tempo-clock server))
  (send-message server "/g_freeAll" (int 0))
  (send-message server "/clearSched")
  (reset! (:default-group server) (make-group :id 1 :pos :head :to 0 :server server))
  (doseq [hook @server-free-all-hooks] (hook))
  server)

(defn stop
  ([server] (stop server @(:default-group server)))
  ([server group & groups]
   (sched/sched-clear @(:scheduler server))
   (sched/tempo-clock-clear @(:tempo-clock server))
   (send-message server "/clearSched")
   (doseq [group (cons group groups)]
     (with-node [id _] group
       (send-message server "/g_freeAll" (int id))))
   (doseq [hook @stop-hooks] (hook))
   server))

(defn live-server?
  "True when S is a booted external server with a live OSC device.
   Guards against lost-/quit-reply corpses (boot? stuck true, device
   closed) and NRT score-collectors (no sound to cut)."
  [s]
  (and (instance? ExternalServer s)
       (boolean (when-let [b (:boot? s)] @b))
       (let [dev (when-let [d (:device s)] @d)]
         (and (some? dev) (= :running @(:status dev))))))

(defn sole-live-server
  "The single live-server? server, or nil unless exactly one is live.
   Never throws: ambiguity dissolves to nil."
  []
  (let [rs (filterv live-server? (all-running-servers))]
    (when (= 1 (count rs)) (first rs))))

(defn panic!
  "Cut all sound immediately. Clears pending language-side scheduler and
   tempo-clock callbacks, sends /clearSched (drops future-timetag bundles
   already on scsynth) and /g_deepFree 0 (frees every synth under root,
   groups left intact - so no group rebuild is needed, unlike /g_freeAll
   which takes group 1 itself). Tails do not ring out.
   Returns the server."
  [server]
  (sched/sched-clear @(:scheduler server))
  (sched/tempo-clock-clear @(:tempo-clock server))
  (send-message server "/clearSched")
  (send-message server "/g_deepFree" (int 0))
  (println :panic-silenced (:name server))
  server)

(defn find-live-servers
  "Probe localhost UDP PORTS for scsynth /status replies. Needs no record,
   binding, or registry - finds servers from any process. Returns the
   responding ports (usually one). A replying non-SC service is harmless:
   the panic pair is meaningless to it."
  [& {:keys [ports] :or {ports (concat (range 57100 57201) [57711])}}]
  (let [sock (DatagramSocket.)
        localhost (InetAddress/getByName "127.0.0.1")]
    (.setSoTimeout sock 150)
    (try
      (filterv (fn [port]
                 (try
                   (let [data (enc/encode-message "/status")
                         pkt (DatagramPacket. data (alength data)
                                              localhost (int port))]
                     (.send sock pkt)
                     (let [buf (byte-array 65535)
                           rp (DatagramPacket. buf (alength buf))]
                       (.receive sock rp)
                       (= port (.getPort rp))))
                   (catch java.net.SocketTimeoutException _ false)
                   (catch Exception _ false)))
               ports)
      (finally (.close sock)))))

(defn panic-port!
  "Send /clearSched + /g_deepFree 0 to HOST:PORT with a throwaway device -
   no server record needed. For silencing a server this process doesn't
   track (different JVM, stale REPL state, quit record whose scsynth
   outlived it). Returns nil."
  [port & {:keys [host] :or {host "127.0.0.1"}}]
  (let [dev (tx/osc-device host (int port) :local-port nil)]
    (try
      (tx/send-message dev "/clearSched")
      (tx/send-message dev "/g_deepFree" (int 0))
      (finally (tx/close-device dev)))
    nil))

(defn panic-all!
  "Strongest silence this process can manage: the panic pair sprayed at
   every (host,port) ever registered here - catching quit-but-ringing
   records the live filter skips - then full panic! on every live server
   (clears queues, frees synths; groups survive /g_deepFree so no
   rebuild is needed). Never throws (each spray is guarded). Truly
   foreign orphans (another machine, an scsynth started outside this
   library) still need `killall scsynth`. Returns nil."
  []
  (let [targets (distinct
                 (keep (fn [{:keys [host port]}]
                         (when (and host port) [host port]))
                       @all-servers))
        live (filterv live-server? (all-running-servers))]
    (doseq [[host port] targets]
      (try (panic-port! port :host host) (catch Exception _)))
    (doseq [s live] (panic! s))
    (println :panic-all {:live (mapv :name live) :sprayed targets})
    nil))

;; -- scheduler/clock facade (server.lisp tail; RT paths) ---------------------------------

(defn callback [server time f & args]
  (apply sched/sched-add @(:scheduler server) time f args))

(defn now [server]
  (sched/sched-time @(:scheduler server)))

(defn quant
  ([server next-time] (sched/sched-quant @(:scheduler server) next-time 0.5))
  ([server next-time offset] (sched/sched-quant @(:scheduler server) next-time offset)))

(defn clock-bpm
  ([server] (sched/tempo-clock-bpm @(:tempo-clock server)))
  ([server bpm] (sched/tempo-clock-bpm @(:tempo-clock server) bpm)))

(defn clock-beats [server]
  (sched/tempo-clock-beats @(:tempo-clock server)))

(defn clock-quant [server q]
  (sched/tempo-clock-quant @(:tempo-clock server) q))

(defn beat-dur [server beats]
  (* @(:beat-dur @(:tempo-clock server)) beats))

(defn dur-beat [server secs]
  (* secs (/ (clock-bpm server) 60.0)))

(defn clock-add [server beat f & args]
  (sched/tempo-clock-add @(:tempo-clock server) beat (fn [] (apply f args))))

(defn clock-clear [server]
  (sched/tempo-clock-clear @(:tempo-clock server)))
