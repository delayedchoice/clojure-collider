(ns clojure-collider.handle
  "Opaque id-handles for REPL use.

   A handle that holds streams, scheduler queues or sockets prints as tens
   of KB of single-line EDN; returning that as an eval value wedges nREPL
   clients (Conjure never serves the next eval). Handles here are an id
   into a registry - one field, tiny under default printing no matter how
   often the record is redefined, with no print-method that can go stale
   pointing at an older class generation. Resolve via resolve-handle;
   entries leave the registry when released (e.g. after server-quit).

   Usage: (def h (handle/register! {:server s ...}))
          (handle/resolve-handle h) ;=> parts map, or nil when unknown
          (handle/release! h)       ;=> nil, idempotent")

(defrecord Handle [id])

(defonce ^:private registry (atom {}))
(defonce ^:private handle-seq (atom 0))

(defn register!
  "Store parts map M, return an opaque Handle for it."
  [m]
  (let [id (swap! handle-seq inc)]
    (swap! registry assoc id m)
    (->Handle id)))

(defn resolve-handle
  "Return the parts map registered for H, or nil if H is unknown,
   released, or not a Handle."
  [h]
  (get @registry (:id h)))

(defn release!
  "Forget H's parts. Idempotent, returns nil."
  [h]
  (swap! registry dissoc (:id h))
  nil)
