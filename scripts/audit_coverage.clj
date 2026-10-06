(require '[clojure.edn :as edn] 'clojure-collider.ugens)
(let [ir (map edn/read-string (clojure.string/split-lines (slurp "/tmp/ugen_ir.edn")))
      dus (filter :lisp-name ir)
      all-nses (->> (all-ns) (map ns-name) (map str)
                    (filter #(clojure.string/starts-with? % "clojure-collider.ugens.")) sort)
      have (into #{} (mapcat (fn [n] (keys (ns-publics (symbol n)))) all-nses))
      want (for [d dus v (:variants d)]
             [(str (clojure.string/lower-case (:lisp-name d)) ":" (name (:rate v)))
              (:file d)])]
  (println :upstream-variants (count want) :have (count have))
  (doseq [[f missing] (sort-by first
                               (reduce (fn [m [fname file]]
                                         (if (contains? have (symbol fname)) m
                                           (update m file (fnil conj []) fname)))
                                       {} want))]
    (println :MISSING f missing)))
