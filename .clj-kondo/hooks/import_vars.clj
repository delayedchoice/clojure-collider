(ns hooks.import-vars
  "clj-kondo hook for our dependency-free import-vars macro.
   (import-vars some.ns a b c) is analyzed as (do (def a (fn [& _])) ...)
   so re-exported vars resolve at use sites (incl. apply) without
   uninitialized-var warnings."
  (:require [clj-kondo.hooks-api :as api]))

(defn import-vars [{:keys [node]}]
  (let [[_macro _from-ns & syms] (:children node)]
    {:node
     (api/list-node
      (cons
       (api/token-node 'do)
       (map (fn [s] (api/list-node [(api/token-node 'def)
                                    (api/token-node (:value s))
                                    (api/list-node
                                     [(api/token-node 'fn)
                                      (api/vector-node
                                       [(api/token-node '&)
                                        (api/token-node '_)])])]))
            syms)))}))
