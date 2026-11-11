(ns patterns.clock
  "Clock + play/stop for patterns. Mirrors upstream clock.lisp (MVP slice):
   tasks scheduled on our TempoClock (server/scheduler.clj). A task is a map
   {:id :source :stream :next-beat :loop?} (:next-beat/:loop? are atoms).
   play pulls pnext! values; eop drops the task (or restarts its stream when
   looping); event maps go to every enabled backend via backend/play-event and
   the next step is scheduled at beat+delta. play-take is a synchronous,
   no-timing helper for golden tests."
  (:require [patterns.util :as u]
            [patterns.event :as ev]
            [patterns.patterns :as p]
            [patterns.pbind :as pbind]
            [patterns.backend :as backend]
            [clojure-collider.server.scheduler :as sched]
            [clojure-collider.server.server :as server]
            [clojure-collider.server.options :as opts]))

;; -- clock --------------------------------------------------------------------

(defn make-clock
  "Make a clock map with an empty task list and no backends.
   TEMPO-CLOCK is a scheduler/TempoClock (or nil for untimed use).
   :server holds the clock's sounding server (or nil when serverless)."
  ([] (make-clock nil))
  ([tempo-clock]
   {:tempo-clock tempo-clock
    :tasks (atom [])
    :enabled-backends (atom [])
    :server (atom nil)}))

(defn start-clock-loop
  "Start a fresh pattern clock at TEMPO BPM (default 60, i.e. upstream
   make-clock tempo 1 BPS) and return it. Always builds a new clock;
   nothing is shared or installed globally."
  [& {:keys [tempo] :or {tempo 60}}]
  (let [tc (sched/make-tempo-clock "patterns clock" nil :bpm (double tempo)
                                   :time-sync? false)]
    (sched/tempo-clock-run tc)
    (make-clock tc)))

(defn enable-backend!
  "Add backend B to CLOCK's enabled backends. Returns B."
  [clock b]
  (swap! (:enabled-backends clock)
         (fn [bs] (if (some #(identical? % b) bs) bs (conj bs b))))
  b)

 
 (defn prune-dead-backends!
   "Drop backends whose server has since quit (stale REPL rigs: re-running
   a -main binds a new server while the old backend still points at the
   closed device, and every step NPEs before reaching the live backend).
   A backend survives only with a booted server AND a live OSC device
   (belt and suspenders: a lost /quit reply can leave boot? true on a
   device-less server). Backends without a :server (e.g. DebugBackend)
   are kept."
   [clock]
  (swap! (:enabled-backends clock)
          (fn [bs] (filterv (fn [b] (let [s (:server b)]
                                      (or (nil? s) (server/live-server? s))))
                            bs)))
  clock)

(defn disable-backend!
  "Remove backend B from CLOCK's enabled backends. Returns B."
  [clock b]
  (swap! (:enabled-backends clock)
         (fn [bs] (vec (remove #(identical? % b) bs))))
  b)

(defn setup-performance!
  "One-call live rig: seed the pattern RNG, boot an external scsynth,
   start a fresh pattern clock holding the server. Returns
   {:server s :clock c}. play auto-enables a sounding backend from the
   clock's server, so no backend wiring is needed. Defaults suit dense
   polyphonic examples.
   Input streams are OFF by default (-i 0, output-only); pass :input?
   true to open the input device. (Applies to the built default
   options; explicit :options are used as-is.)"
  [& {:keys [seed port tempo name options input?]
      :or {seed 7 port 57138 tempo 120 name "example"}}]
  (u/seed-rng! seed)
  (let [s (server/make-external-server
             name :port port
             :options (or options
                          (opts/make-server-options
                           (cond-> {:realtime-mem-size 65536
                                   :max-num-nodes 4096}
                             input? (assoc :num-input-bus 8)))))]
    (server/server-boot s)
    (let [clock (start-clock-loop :tempo tempo)]
      (reset! (:server clock) s)
      ;; Re-running a rig in one REPL leaves backends bound to the quit
      ;; server; prune them before this rig adds its own.
      (prune-dead-backends! clock)
      {:server s :clock clock})))

(defn connect-server!
  "One call for the standard live boilerplate: boot SERVER, record it on
   CLOCK, prune dead backends, enable a ClColliderBackend on SERVER.
   Returns SERVER. Enabling up front also suppresses play's
   auto-backend (it only fires with no backends enabled), so exactly one
   backend sounds.
   SERVER boots input-free by default (-i 0, output-only); :input? true
   opens the input device (assocs :num-input-bus 8 into its options
   before boot). TEMPO sets the BPM on CLOCK's tempo clock (when it has
   one; untimed nil tempo-clock clocks keep their own time)."
  [clock server & {:keys [tempo input?] :or {tempo 90}}]
  (let [server (cond-> server input? (update :options assoc :num-input-bus 8))]
    (server/server-boot server)
    (reset! (:server clock) server)
    (when-let [tc (:tempo-clock clock)]
      (sched/tempo-clock-bpm tc (double tempo)))
    ;; Same REPL re-run prune as setup-performance!: drop backends bound
    ;; to quit servers before adding this rig's own.
    (prune-dead-backends! clock)
    (enable-backend! clock (backend/make-cl-collider-backend server))
    server))

;; -- beats / quant --------------------------------------------------------------

(defn- clock-beats
  "Current beat on clock (0 when there is no tempo clock)."
  [clock]
  (if-let [tc (:tempo-clock clock)]
    (sched/tempo-clock-beats tc)
    0.0))

 (defn quant-next
   "Next beat strictly after BEAT on quantum grid Q (rounds up like
   server/quant: an on-grid BEAT advances a full Q). Anything
   non-numeric returns BEAT."
   [q beat]
  (if (number? q)
    (let [qb (double q)
          b (double beat)]
      (+ b (- qb (mod b qb))))
    beat))

(defn beat->sec
  "Convert BEAT (beats) to seconds on clock's tempo clock.
   Falls back to 60 BPM (1 beat = 1 sec) without a tempo clock."
  [clock beat]
  (if-let [tc (:tempo-clock clock)]
    (sched/beats-to-secs tc (double beat))
    (double beat)))

;; -- tasks ----------------------------------------------------------------------

(defonce ^:private task-ids (atom 0))

(defn- resolve-source
  "Resolve X to a playable: keywords look up the pdef registry."
  [x]
  (if (keyword? x)
    (or (pbind/find-pdef x)
        (throw (ex-info (str "play: no pdef named " x) {:name x})))
    x))

(defn- remove-task!
  [clock task]
  (swap! (:tasks clock) (fn [ts] (vec (remove #(= (:id %) (:id task)) ts))))
  nil)

(declare step-body!)

(defn- step-task!
  "Run one step of TASK on CLOCK: pull pnext!, play events, reschedule.
   Eop drops the task, restarting its stream first when looping (an
   immediately re-ending source is dropped after 64 chained restarts).
   Steps whose task is no longer on the clock (stop/remove while a
   tempo-clock callback was already queued) are dropped silently: stop
   only clears the task list, it cannot unqueue the pending callback."
  ([clock task] (step-task! clock task 0))
  ([clock task depth]
   (when (some #(= (:id %) (:id task)) @(:tasks clock))
     (if (> depth 64)
       (remove-task! clock task)
      (step-body! clock task depth)))))

(defn- step-body!
  "Single pull/play/reschedule step (split for the depth guard above)."
  [clock task depth]
  (let [stream (:stream task)
        v (p/pnext! stream)]
    (if (= v u/eop)
      (if @(:loop? task)
        (let [fresh (p/as-pstream (:source task))]
          (swap! (:tasks clock)
                 (fn [ts] (mapv (fn [t]
                                  (if (= (:id t) (:id task))
                                    (assoc t :stream fresh)
                                    t))
                                ts)))
          (step-task! clock (assoc task :stream fresh) (inc depth)))
        (remove-task! clock task))
       (let [event-map (if (map? v) v (ev/event :value v))
             beat @(:next-beat task)
             sec (beat->sec clock beat)
             ;; Releases are computed in seconds from :sustain (beats):
             ;; the backend needs beats/sec, not the 60bpm default.
             bps (if-let [tc (:tempo-clock clock)]
                   (/ (double (sched/tempo-clock-bpm tc)) 60.0)
                   1.0)
             event-map (assoc event-map :tempo bps)
             delta (or (ev/event-value event-map :delta) 1)]
         (doseq [b @(:enabled-backends clock)]
           (backend/play-event b event-map sec))
        (reset! (:next-beat task) (+ beat (double delta)))
        (when-let [tc (:tempo-clock clock)]
          (let [nb @(:next-beat task)]
            (sched/tempo-clock-add tc nb
              (fn [] (step-task! clock task)))))
        nil))))

(defn play
  "Play X (pattern map, event map, or pdef keyword) on CLOCK.
   Returns the task map. The task loops until stopped; see end/stop.
   With no backends enabled, a ClColliderBackend is auto-enabled from
   the clock's live server, else the sole live server; with neither,
   no backend sounds. Enable backends explicitly (e.g. DebugBackend)
   to override or to hear nothing."
  [clock x & {:keys [quant loop?] :or {loop? true}}]
  (when (nil? clock)
    (throw (ex-info "play: no clock; call start-clock-loop first" {})))
  (let [source (resolve-source x)
        stream (p/as-pstream source)
        start (quant-next (or quant (:quant (when (map? source) source))
                              (:play-quant (when (map? source) source)))
                          (clock-beats clock))
        task {:id (swap! task-ids inc)
              :source source
              :stream stream
              :next-beat (atom (double start))
              :loop? (atom (boolean loop?))}]
    (swap! (:tasks clock) conj task)
    (when (empty? @(:enabled-backends clock))
      (when-let [s (or (let [cs @(:server clock)]
                         (when (and cs (server/live-server? cs)) cs))
                       (server/sole-live-server))]
        (enable-backend! clock (backend/make-cl-collider-backend s))))
    (when-let [tc (:tempo-clock clock)]
      (sched/tempo-clock-add tc (double start)
        (fn [] (step-task! clock task))))
    task))

(defn playing-p
  "True when TASK (or the task playing pdef keyword) is on CLOCK."
  [clock x]
  (let [ts @(:tasks clock)]
    (boolean
     (if (keyword? x)
       (let [src (try (resolve-source x) (catch clojure.lang.ExceptionInfo _ ::missing))]
         (some #(= (:source %) src) ts))
       (some #(or (= (:id %) (:id x)) (= % x)) ts)))))

(defn stop
  "Stop a task, the tasks playing pdef keyword X, or all tasks (:all)
   on CLOCK."
  [clock x]
  (cond
    (or (= x :all) (nil? x))
    (do (reset! (:tasks clock) []) nil)

    (keyword? x)
    (let [src (resolve-source x)]
      (swap! (:tasks clock)
             (fn [ts] (vec (remove #(= (:source %) src) ts))))
      nil)

    :else
    (remove-task! clock x)))

(defn panic!
  "Stop everything immediately: drop all clock tasks (no new notes), then
   server/panic-all! - full panic on every live server plus the panic
   pair sprayed at every (host,port) this process ever registered, so
   quit-but-ringing records die too. Cross-process orphans (another REPL
   JVM, a hand-started scsynth) are out of reach: `killall scsynth`.
   Returns nil."
  [clock]
  (let [n (count @(:tasks clock))]
    (stop clock :all)
    (server/panic-all!)
    (println :panic-tasks-dropped n)
    nil))

(defn halt!
  "Stop everything sounding through CLOCK right now and quit its server:
   drop all pattern tasks (no new notes), panic! the server (clear
   language-side schedulers, /clearSched, /g_deepFree - tails do not ring
   out), then server-quit. Synchronous, no fade. Returns :halted. Throws
   on failure - REPL helpers that must not throw catch and report.
   Only reaches CLOCK's own server; servers this process doesn't track
   (other JVMs, lost handles) need server/panic-all! (stays booted) or
   killall scsynth."
  [clock]
  (cond
    (nil? clock)
    (do (println :halt-skipped :reason :no-clock)
        :already-stopped)
    (nil? @(:server clock))
    (do (println :halt-skipped :reason :no-server)
        :already-stopped)
    (not (server/boot? @(:server clock)))
    (do (println :halt-skipped :reason :not-booted)
        :already-stopped)
    :else
    (let [s @(:server clock)]
      (println :halting (:name s))
      (stop clock :all)
      (server/panic! s)
      (server/server-quit s)
      (println :halted (:name s))
      :halted)))

(defn end
  "End TASK (or tasks playing pdef keyword X) on CLOCK: finish the
   current cycle without looping (sets loop? false). Returns the
   updated task, or nil."
  [clock x]
  (if (keyword? x)
    (let [src (resolve-source x)]
      (swap! (:tasks clock)
             (fn [ts] (mapv (fn [t]
                              (if (= (:source t) src)
                                (do (reset! (:loop? t) false) t)
                                t))
                            ts)))
      nil)
    (do (reset! (:loop? x) false) x)))

(defn play-or-stop
  "On CLOCK, stop X if it is playing, else play it. Returns the task or nil."
  [clock x]
  (if (playing-p clock x)
    (do (stop clock (if (keyword? x)
                      x
                      (first (filter #(= (:id %) (:id x))
                                     @(:tasks clock)))))
        nil)
    (play clock x)))

;; -- synchronous test helper -----------------------------------------------------

(defn play-take
  "Pull up to N events from PATTERN through (play-event BACKEND ev beat),
   beats starting at 0 advancing by each event's :delta (default 1).
   No timing involved; golden-testable without scsynth. Returns the vector
   of {:beat b :event ev} pulled (fewer when the pattern ends)."
  [pattern n backend]
  (let [stream (p/as-pstream pattern)]
    (loop [i 0 beat 0.0 out []]
      (if (>= i n)
        out
        (let [v (p/pnext! stream)]
          (if (= v u/eop)
            out
            (let [event-map (if (map? v) v (ev/event :value v))
                  delta (double (or (ev/event-value event-map :delta) 1))]
              (backend/play-event backend event-map (double beat))
              (recur (inc i) (+ beat delta) (conj out {:beat (double beat)
                                                       :event event-map})))))))))
