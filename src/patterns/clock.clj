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
            [clojure-collider.server.scheduler :as sched]))

;; -- clock --------------------------------------------------------------------

(def ^:dynamic *clock*
  "The default clock: {:tempo-clock TempoClock :tasks (atom [task])
   :enabled-backends (atom [backend])}."
  nil)

(defn make-clock
  "Make a clock map with an empty task list and no backends.
   TEMPO-CLOCK is a scheduler/TempoClock (or nil for untimed use)."
  ([] (make-clock nil))
  ([tempo-clock]
   {:tempo-clock tempo-clock
    :tasks (atom [])
    :enabled-backends (atom [])}))

(defn start-clock-loop
  "Ensure a running clock in *clock* and return it. TEMPO is BPM (default 60,
   i.e. upstream make-clock tempo 1 BPS). Reuses the existing clock unless
   :force is true."
  [& {:keys [tempo force] :or {tempo 60}}]
  (when (or force (nil? *clock*))
    (let [tc (sched/make-tempo-clock "patterns clock" nil :bpm (double tempo)
                                     :time-sync? false)]
      (sched/tempo-clock-run tc)
      (alter-var-root #'*clock* (constantly (make-clock tc)))))
  *clock*)

(defn enable-backend!
  "Add backend B to clock's enabled backends (default *clock*). Returns B."
  ([b] (enable-backend! *clock* b))
  ([clock b]
   (swap! (:enabled-backends clock)
          (fn [bs] (if (some #(identical? % b) bs) bs (conj bs b))))
   b))

(defn disable-backend!
  "Remove backend B from clock's enabled backends. Returns B."
  ([b] (disable-backend! *clock* b))
  ([clock b]
   (swap! (:enabled-backends clock)
          (fn [bs] (vec (remove #(identical? % b) bs))))
   b))

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
   immediately re-ending source is dropped after 64 chained restarts)."
  ([clock task] (step-task! clock task 0))
  ([clock task depth]
   (if (> depth 64)
     (remove-task! clock task)
     (step-body! clock task depth))))

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
  "Play X (pattern map, event map, or pdef keyword) on CLOCK (*clock*).
   Returns the task map. The task loops until stopped; see end/stop."
  ([x] (play *clock* x))
  ([clock x & {:keys [quant loop?] :or {loop? true}}]
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
     (when-let [tc (:tempo-clock clock)]
       (sched/tempo-clock-add tc (double start)
         (fn [] (step-task! clock task))))
     task)))

(defn playing-p
  "True when TASK (or the task playing pdef keyword) is on CLOCK."
  ([x] (playing-p *clock* x))
  ([clock x]
   (let [ts @(:tasks clock)]
     (boolean
      (if (keyword? x)
        (let [src (try (resolve-source x) (catch clojure.lang.ExceptionInfo _ ::missing))]
          (some #(= (:source %) src) ts))
        (some #(or (= (:id %) (:id x)) (= % x)) ts))))))

(defn stop
  "Stop a task, the tasks playing pdef keyword X, all tasks (:all / no
  arg), or all tasks on CLOCK-MAP (1-arity with a clock map)."
  ([] (stop *clock* :all))
  ([x] (if (and (map? x) (contains? x :tasks))
         (stop x :all)
         (stop *clock* x)))
  ([clock x]
   (cond
     (or (= x :all) (nil? x))
     (do (reset! (:tasks clock) []) nil)

     (keyword? x)
     (let [src (resolve-source x)]
       (swap! (:tasks clock)
              (fn [ts] (vec (remove #(= (:source %) src) ts))))
       nil)

     :else
     (remove-task! clock x))))

(defn end
  "End TASK (or tasks playing pdef keyword X): finish the current cycle
   without looping (sets loop? false). Returns the updated task, or nil."
  ([x] (end *clock* x))
  ([clock x]
   (if (keyword? x)
     (let [src (resolve-source x)]
       (swap! (:tasks clock)
              (fn [ts] (mapv (fn [t]
                               (if (= (:source t) src)
                                 (do (reset! (:loop? t) false) t)
                                 t))
                             ts)))
       nil)
     (do (reset! (:loop? x) false) x))))

(defn play-or-stop
  "Stop X if it is playing, else play it. Returns the task or nil."
  ([x] (play-or-stop *clock* x))
  ([clock x]
   (if (playing-p clock x)
     (do (stop clock (if (keyword? x)
                       x
                       (first (filter #(= (:id %) (:id x))
                                      @(:tasks clock)))))
         nil)
     (play clock x))))

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
