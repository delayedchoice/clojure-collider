(ns hooks.defop
  "clj-kondo hooks for clojure-collider.synth.operators/def-unary-op and
   def-binary-op. Each defines a var named by its first argument (analyzed
   as a variadic fn). Remaining args are spliced with a/b declared so
   constant-fold clauses and basic-fns resolve."
  (:require [clj-kondo.hooks-api :as api]))

(defn- def-of [node]
  (let [[_macro nm & body] (:children node)]
    {:node (api/list-node
            (concat
             [(api/token-node 'do)
              (api/list-node
               [(api/token-node 'declare)
                (api/token-node 'a)
                (api/token-node 'b)])
              (api/list-node
               [(api/token-node 'def)
                (api/token-node (:value nm))
                (api/list-node
                 [(api/token-node 'fn)
                  (api/vector-node
                   [(api/token-node '&) (api/token-node '_)])])])]
             body))}))

(defn def-unary-op [{:keys [node]}] (def-of node))

(defn def-binary-op [{:keys [node]}] (def-of node))
