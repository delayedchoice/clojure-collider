(require 'clojure-collider.ugens)
(let [agg (into #{} (keys (ns-publics 'clojure-collider.ugens)))
      src-nses (->> (all-ns) (map ns-name) (map str)
                    (filter #(clojure.string/starts-with? % "clojure-collider.ugens."))
                    (remove #(= % "clojure-collider.ugens")) sort)
      by-name (reduce (fn [m n]
                        (reduce (fn [m [s _]] (update m s (fnil conj []) n))
                                m (ns-publics (symbol n))))
                      {} src-nses)]
  (println :nses (count src-nses))
  (doseq [[s nss] (sort-by first by-name) :when (> (count nss) 1)]
    (println :DUP s nss))
  (doseq [n src-nses
          :let [missing (remove agg (keys (ns-publics (symbol n))))]
          :when (seq missing)]
    (println :MISSING-FROM-AGG n (sort missing)))
  (println :agg-total (count agg)))
