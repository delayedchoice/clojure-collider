(require '[clojure-collider.synth.ugen :as ugen]
         '[clojure-collider.synth.synthdef :as synthdef]
         '[clojure-collider.ugens.osc :as osc]
         '[clojure-collider.ugens.in-out :as io]
         '[clojure-collider.server.scheduler :as sched])
;; -- topo micro-benchmark: N-unit chains ---------------------------------------
(doseq [n [10 100 500]]
  (let [sd (synthdef/make-synthdef "bench")]
    (binding [ugen/*synthdef* sd]
      (io/out:ar 0 (reduce (fn [sig _] (osc/sin-osc:ar sig 0 1 0))
                           (osc/sin-osc:ar 440 0 1 0)
                           (range n))))
    (let [t0 (System/nanoTime)
          _ (synthdef/build-synthdef sd)
          dt (/ (- (System/nanoTime) t0) 1e6)
          bytes (count (synthdef/encode-synthdef sd))]
      (println :topo :units (inc n) :ms (format "%.2f" dt) :bytes bytes))))
;; -- scheduler jitter: 100 callbacks, 10ms grid --------------------------------
(let [s (sched/make-scheduler "bench" nil)
      errs (atom [])
      t0 (+ (sched/sched-time s) 0.5)]
  (doseq [i (range 100)]
    (let [target (+ t0 (* i 0.01))]
      (sched/sched-add s target
                       (fn [] (swap! errs conj
                                     (- (sched/sched-time s) target))))))
  (sched/sched-run s)
  (loop [i 0]
    (when (and (< (count @errs) 100) (< i 200))
      (Thread/sleep 50) (recur (inc i))))
  (sched/sched-stop s)
  (let [ms (mapv #(* 1000 %) @errs)
        mean (/ (reduce + ms) (count ms))
        devs (mapv #(- % mean) ms)
        mx (apply max (map #(Math/abs (double %)) devs))]
    (println :jitter :n (count ms) :lookahead-ms (format "%.1f" mean)
             :max-dev-ms (format "%.2f" mx))))
