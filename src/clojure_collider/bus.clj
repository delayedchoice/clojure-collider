(ns clojure-collider.bus
  "Audio/control bus allocation. Mirrors bus.lisp.
   Server is duck-typed (needs :lock, :audio-buses, :control-buses atoms)
   so this ns never requires server.clj (no cycle). Unlike upstream, server
   is always explicit — no *s* default here (server.clj callers pass it).
   floatfy stays numeric-only (synth.ugen); coerce buses via :busnum."
  (:import [java.util.concurrent.locks ReentrantLock]))

(defrecord Bus [busnum type chanls server])

(defmethod print-method Bus [b ^java.io.Writer w]
  (.write w (str "#<bus " (name (:type b)) (:busnum b) " x" (:chanls b) ">")))

(defmacro ^:private with-server-lock [server & body]
  `(let [l# ^ReentrantLock (:lock ~server)]
     (.lock l#)
     (try ~@body (finally (.unlock l#)))))

(defn bus? [x] (instance? Bus x))

(defn- bus-vec [server type]
  (if (= type :audio) (:audio-buses server) (:control-buses server)))

(defn- find-consecutive
  "First index with `channels` free slots from `start`, or nil."
  [buses channels start]
  (loop [pos start]
    (let [pos (loop [i pos]
                (cond (>= i (count buses)) nil
                      (nil? (nth buses i)) i
                      :else (recur (inc i))))]
      (cond (nil? pos) nil
            (every? #(and (< % (count buses)) (nil? (nth buses %)))
                    (range pos (+ pos channels))) pos
            :else (recur (inc pos))))))

(defn get-next-bus
  "Allocate `channels` consecutive buses; explicit busnum overwrites,
   like upstream. Throws when full."
  [server type channels & [busnum]]
  (when (not (contains? #{:audio :control} type))
    (throw (ex-info "bus type must be :audio/:control" {:type type})))
  (with-server-lock server
    (let [buses @(bus-vec server type)
          n (or busnum (find-consecutive buses channels 0))]
      (when (or (nil? n) (< n 0) (> (+ n channels) (count buses)))
        (throw (ex-info "no consecutive buses available"
                        {:type type :channels channels :busnum busnum})))
      (let [bus-obj (map->Bus {:busnum n :type type :chanls channels :server server})]
        (reset! (bus-vec server type)
                (reduce (fn [v i] (assoc v i bus-obj))
                        buses (range n (+ n channels))))
        bus-obj))))

(defn bus-alloc [type server & {:keys [chanls busnum] :or {chanls 1}}]
  (get-next-bus server type chanls busnum))

(defn bus-audio [server & {:keys [chanls busnum] :or {chanls 1}}]
  (bus-alloc :audio server :chanls chanls :busnum busnum))

(defn bus-control [server & {:keys [chanls busnum] :or {chanls 1}}]
  (bus-alloc :control server :chanls chanls :busnum busnum))

(defn bus-free
  [bus & [server]]
  (let [server (or server (:server bus))
        v (bus-vec server (:type bus))]
    (with-server-lock server
      (reset! v (reduce (fn [b i] (assoc b i nil))
                        @v (range (:busnum bus) (+ (:busnum bus) (:chanls bus))))))
    nil))

(defn bus-string [bus]
  (str (if (= :audio (:type bus)) "a" "c") (:busnum bus)))
