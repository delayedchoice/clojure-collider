(ns hooks.ports
  "clj-kondo hooks for binding macros kondo cannot see through.
   with-controls ((a 1) (b 2)) ...  -> (let [a 1 b 2] ...)
   with-node [id server] node ...    -> (let [id nil server nil] ...)"
  (:require [clj-kondo.hooks-api :as api]))

(defn with-controls [{:keys [node]}]
  (let [[_macro a b & rest] (:children node)
        ;; sdef/play threads the synthdef atom into with-controls heads, so
        ;; play bodies read (with-controls PARAMS BODY) with a sequential
        ;; second child; explicit calls read (with-controls SD PARAMS BODY).
        ;; The explicit atom is re-emitted as a leading body form so kondo
        ;; sees its use (the real macro consumes it in make-control).
        [bindings body pre] (if (contains? #{:list :vector} (:tag a))
                             [a (cons b rest) nil]
                             [b rest a])
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
                    (if pre [pre] [])
                    body))}))

(defn with-node [{:keys [node]}]
  (let [[_macro bind-vec node-expr & body] (:children node)
        [id server] (:children bind-vec)]
    {:node (api/list-node
            (concat [(api/token-node 'let)
                     (api/vector-node [id node-expr server node-expr])]
                    body))}))

(defn with-rendering
  "with-rendering [sym output & opts] body ... -> (let [sym nil _ output _ opt ...] body ...).
   Only sym is really bound; the rest are referenced under throwaway _
   so kondo sees their use (the macro evaluates them at runtime too)."
  [{:keys [node]}]
  (let [[_macro bind-vec & body] (:children node)
        [sym & rest-args] (:children bind-vec)
        used-pairs (mapcat (fn [arg] [(api/token-node '_) arg]) rest-args)]
    {:node (api/list-node
            (concat [(api/token-node 'let)
                     (api/vector-node (into [sym (api/token-node nil)]
                                            used-pairs))]
                    body))}))
