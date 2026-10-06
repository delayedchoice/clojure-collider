(ns clojure-collider.server.scheduler
  "Wall-clock Scheduler + TempoClock. Mirrors scheduler.lisp.
   Wall-clock unix-time scheduling (NTP-step fragile, like upstream);
   PriorityBlockingQueue + ReentrantLock/Condition replace pileup+bordeaux.
   SCHED_RR elevation is a documented no-op (JVM cannot do it).
   Stop uses a ::stop task sentinel (upstream compares a symbol)."
  (:import [java.util.concurrent PriorityBlockingQueue TimeUnit]
           [java.util.concurrent.locks ReentrantLock]
           [java.util Comparator]))

;; -- clocks ---------------------------------------------------------------------

(defn unix-time
  "Wall-clock seconds since the Unix epoch (gettimeofday family upstream)."
  []
  (/ (double (System/currentTimeMillis)) 1000.0))

(defn monotonic-time
  "Monotonic seconds (nanoTime). Upstream defines this but never schedules
   with it; kept for the same future use."
  []
  (/ (double (System/nanoTime)) 1e9))

(defn set-realtime-priority
  "No-op. Upstream self-elevates worker threads to SCHED_RR via
   pthread_setschedparam; the JVM has no equivalent. Best effort below."
  []
  (try (.setPriority (Thread/currentThread) Thread/MAX_PRIORITY)
       (catch Exception _ nil))
  nil)

;; -- engine -----------------------------------------------------------------------

(def ^:private stop-task ::scheduler-stop)

(defn- event-comparator []
  (reify Comparator
    (compare [_ a b] (compare (:timestamp a) (:timestamp b)))))

(defn- make-queue []
  (PriorityBlockingQueue. 100 (event-comparator)))

(defmacro ^:private with-lock [sched & body]
  `(let [l# ^ReentrantLock (:lock ~sched)]
     (.lock l#)
     (try ~@body (finally (.unlock l#)))))

(defn- sched-ahead-secs [server]
  (or (:sched-ahead server) 0.2))

(defn sched-time [sched]
  ((:timestamp-fn sched)))

(defn sched-status [sched]
  @(:status sched))

(defn- running? [sched]
  (= :running (sched-status sched)))

(defn- push-event [sched timestamp task]
  (with-lock sched
    (.put ^PriorityBlockingQueue (:queue sched) {:timestamp (double timestamp) :task task})
    (.signal ^java.util.concurrent.locks.Condition (:cond sched))))

(defn sched-add
  "Enqueue (apply f args) at wall-clock `time` seconds. Stored timestamp is
   time minus the server sched-ahead, so the task fires early by lookahead.
   bound-fn conveys the scheduling thread's dynamic bindings (*s* etc.)
   to the worker, like CL's globals."
  [sched time f & args]
  (push-event sched (- (double time) (sched-ahead-secs (:server sched)))
              (bound-fn [] (apply f args)))
  nil)

(defn sched-clear [sched]
  (with-lock sched
    (.clear ^PriorityBlockingQueue (:queue sched))
    (.signal ^java.util.concurrent.locks.Condition (:cond sched)))
  nil)

(defn sched-quant
  "Next time strictly after now+offset on a quantum grid (always rounds up,
   like upstream)."
  ([sched q] (sched-quant sched q 0.0))
  ([sched q offset]
   (let [t (+ (sched-time sched) (double offset))]
     (+ t (- (double q) (mod t (double q)))))))

(defn- pop-due [sched due?]
  (with-lock sched
    (loop [out []]
      (let [head (.peek ^PriorityBlockingQueue (:queue sched))]
        (if (and head (due? (:timestamp head)))
          (do (.poll ^PriorityBlockingQueue (:queue sched))
              (recur (conj out head)))
          out)))))

(defn- park-empty [sched]
  (with-lock sched
    (while (nil? (.peek ^PriorityBlockingQueue (:queue sched)))
      (.await ^java.util.concurrent.locks.Condition (:cond sched)))))

(defn- park-until [sched nanos]
  (with-lock sched
    (.awaitNanos ^java.util.concurrent.locks.Condition (:cond sched) (long nanos))))

(defn- spawn-worker [sched thread-name body-fn]
  (let [t (Thread. ^Runnable body-fn)]
    (.setName t thread-name)
    (.setDaemon t true)
    (.start t)
    (reset! (:thread sched) t)
    t))

(defn- run-tasks [events]
  (reduce (fn [stop? {:keys [task]}]
            (try (if (= (task) stop-task) true stop?)
                 (catch Exception e
                   (binding [*out* *err*]
                     (println "Error" (.getMessage e) "in scheduler thread"))
                   stop?)))
          false events))

;; -- Scheduler ----------------------------------------------------------------------

(defrecord Scheduler [name server lock cond queue thread status timestamp-fn])

(defn make-scheduler
  [sched-name server & {:keys [timestamp-fn] :or {timestamp-fn unix-time}}]
  (let [lock (ReentrantLock.)]
    (map->Scheduler {:name sched-name :server server :lock lock :cond (.newCondition lock)
                     :queue (make-queue) :thread (atom nil) :status (atom :stop)
                     :timestamp-fn timestamp-fn})))

(defn sched-run [sched]
  (when (= :stop (sched-status sched))
    (spawn-worker sched (str "[" (:name sched) "] scheduler thread")
                  (fn []
                    (set-realtime-priority)
                    (reset! (:status sched) :running)
                    (loop []
                      (park-empty sched)
                      (let [head (.peek ^PriorityBlockingQueue (:queue sched))
                            timeout (- (:timestamp head) (sched-time sched))]
                        (if (pos? timeout)
                          (do (park-until sched (long (* timeout 1e9)))
                              (recur))
                          (when-not (run-tasks (pop-due sched (fn [ts] (<= ts (sched-time sched)))))
                            (recur))))))))
  :running)

(defn sched-stop [sched]
  (when (running? sched)
    (sched-clear sched)
    (sched-add sched -1 (fn [] stop-task))
    (.join ^Thread @(:thread sched))
    (reset! (:status sched) :stop))
  nil)

;; -- TempoClock -----------------------------------------------------------------------

(defrecord TempoClock [name server lock cond queue thread status timestamp-fn
                       bpm base-seconds base-beats beat-dur
                       timing-offset time-sync? sync-thread sync-run sync-lock sync-cond])

(defn make-tempo-clock
  [clock-name server & {:keys [bpm timestamp-fn time-sync?]
                        :or {bpm 60.0 timestamp-fn unix-time time-sync? true}}]
  (let [lock (ReentrantLock.)
        sync-lock (ReentrantLock.)
        now (double (timestamp-fn))]
    (map->TempoClock {:name clock-name :server server :lock lock :cond (.newCondition lock)
                      :queue (make-queue) :thread (atom nil) :status (atom :stop)
                      :timestamp-fn timestamp-fn
                      :bpm (atom (double bpm)) :base-seconds (atom now) :base-beats (atom 0.0)
                      :beat-dur (atom (/ 60.0 (double bpm)))
                      :timing-offset (atom 0.0) :time-sync? time-sync?
                      :sync-thread (atom nil) :sync-run (atom true)
                      :sync-lock sync-lock :sync-cond (.newCondition sync-lock)})))

(defn beats-to-secs [clock beats]
  (+ (* (- (double beats) @(:base-beats clock)) @(:beat-dur clock))
     @(:base-seconds clock)))

(defn secs-to-beats [clock secs]
  (+ (* (- (double secs) @(:base-seconds clock)) (/ @(:bpm clock) 60.0))
     @(:base-beats clock)))

(defn tempo-clock-beats [clock]
  (secs-to-beats clock (sched-time clock)))

(declare tempo-clock-set-bpm)

(defn tempo-clock-bpm
  ([clock] @(:bpm clock))
  ([clock new-bpm] (tempo-clock-set-bpm clock new-bpm) @(:bpm clock)))

(defn tempo-clock-set-bpm [clock new-bpm]
  (with-lock clock
    (let [now-beats (secs-to-beats clock (sched-time clock))]
      (reset! (:base-seconds clock) (beats-to-secs clock now-beats))
      (reset! (:base-beats clock) now-beats)
      (reset! (:bpm clock) (double new-bpm))
      (reset! (:beat-dur clock) (/ 60.0 (double new-bpm)))
      (.signal ^java.util.concurrent.locks.Condition (:cond clock))))
  nil)

(defn tempo-clock-add [clock beats f & args]
  (push-event clock (double beats) (bound-fn [] (apply f args)))
  nil)

(defn tempo-clock-clear [clock]
  (sched-clear clock)
  nil)

(defn tempo-clock-quant [clock q]
  (let [b (secs-to-beats clock (+ (sched-ahead-secs (:server clock)) (sched-time clock)))]
    (+ b (- (double q) (mod b (double q))))))

(defn- sync-with-unix-time [clock]
  (let [ts (:timestamp-fn clock)
        best (apply min-key :diff
                    (repeatedly 5 (fn []
                                     (let [before (double (ts))
                                           u (unix-time)
                                           after (double (ts))
                                           diff (- after before)]
                                       {:diff diff :offset (- u (+ before (/ diff 2.0)))}))))]
    (reset! (:timing-offset clock) (:offset best))))

(defn- resync-run [clock]
  (sync-with-unix-time clock)
  (when (:time-sync? clock)
    (let [t (Thread. ^Runnable
                     (fn []
                       (loop []
                         (let [l ^ReentrantLock (:sync-lock clock)]
                           (.lock l)
                           (try (.await ^java.util.concurrent.locks.Condition
                                        (:sync-cond clock) 20 TimeUnit/SECONDS)
                                (finally (.unlock l))))
                         (when @(:sync-run clock)
                           (sync-with-unix-time clock)
                           (recur)))))]
      (.setName t (str "[" (:name clock) "] resync time thread"))
      (.setDaemon t true)
      (.start t)
      (reset! (:sync-thread clock) t))))

(defn- resync-stop [clock]
  (when @(:sync-thread clock)
    (reset! (:sync-run clock) false)
    (let [l ^ReentrantLock (:sync-lock clock)]
      (.lock l)
      (try (.signal ^java.util.concurrent.locks.Condition (:sync-cond clock))
           (finally (.unlock l))))
    (.join ^Thread @(:sync-thread clock))
    (reset! (:sync-thread clock) nil)))

(defn tempo-clock-run [clock]
  (when (= :stop (sched-status clock))
    (resync-run clock)
    (spawn-worker clock (str "[" (:name clock) "] TempoClock thread")
                  (fn []
                    (set-realtime-priority)
                    (reset! (:status clock) :running)
                    (loop []
                      (park-empty clock)
                      (let [ahead (sched-ahead-secs (:server clock))
                            head (.peek ^PriorityBlockingQueue (:queue clock))
                            timeout (- (beats-to-secs clock (:timestamp head)) ahead
                                       (sched-time clock))]
                        (if (pos? timeout)
                          (do (park-until clock (long (* timeout 1e9)))
                              (recur))
                          (let [due? (fn [b] (<= (beats-to-secs clock b)
                                                 (+ (sched-time clock) ahead)))]
                            (when-not (run-tasks (pop-due clock due?))
                              (recur)))))))))
  :running)

(defn tempo-clock-stop [clock]
  (resync-stop clock)
  (when (running? clock)
    (let [ahead (sched-ahead-secs (:server clock))]
      (tempo-clock-add clock (+ (tempo-clock-beats clock)
                                (/ (* ahead 0.5) @(:beat-dur clock)))
                       (fn [] stop-task)))
    (.join ^Thread @(:thread clock))
    (tempo-clock-clear clock)
    (reset! (:status clock) :stop))
  nil)
