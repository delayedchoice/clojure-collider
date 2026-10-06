(ns hooks.ports
  "clj-kondo hooks for binding macros kondo cannot see through.
   with-controls ((a 1) (b 2)) ...  -> (let [a 1 b 2] ...)
   with-node [id server] node ...    -> (let [id nil server nil] ...)"
  (:require [clj-kondo.hooks-api :as api]))

(defn with-controls [{:keys [node]}]
  (let [[_macro bindings & body] (:children node)
        kids (:children bindings)
        pairs (if (and (seq kids) (seq (:children (first kids))))
                ;; ((amp 0.5) (gate 1)) pair-list shape
                (mapcat (fn [p]
                          (let [[a b] (:children p)]
                            [a (or b (api/token-node 0))]))
                        kids)
                ;; [freq 440 amp 0.5] flat vector shape (already let-like)
                kids)]
    {:node (api/list-node
            (concat [(api/token-node 'let)
                     (api/vector-node pairs)]
                    body))}))

(defn with-node [{:keys [node]}]
  (let [[_macro bind-vec node-expr & body] (:children node)
        [id server] (:children bind-vec)]
    {:node (api/list-node
            (concat [(api/token-node 'let)
                     (api/vector-node [id node-expr server node-expr])]
                    body))}))
