(require 'clojure-collider.ugens 'clojure-collider.core)
(let [nses (->> (all-ns) (map ns-name) (map str)
                (filter #(clojure.string/starts-with? % "clojure-collider.ugens."))
                (remove #(= % "clojure-collider.ugens")) sort)
      non-macro-pubs (fn [n]
                       (->> (ns-publics (symbol n))
                            (remove (fn [[_ v]] (:macro (meta v))))
                            (map first) sort))]
  (println ";; ---- paste into src/clojure_collider/ugens.clj ----")
  (doseq [n nses
          :let [pubs (non-macro-pubs n)]
          :when (seq pubs)]
    (println (str "(import-vars " n "\n  "
                  (clojure.string/join "\n  "
                                       (map #(clojure.string/join " " %)
                                            (partition-all 8 pubs)))
                  ")")))
  (println ";; ---- paste into src/clojure_collider/core.clj ----")
  (let [all (sort (mapcat non-macro-pubs nses))]
    (println (str "(import-vars clojure-collider.ugens\n  "
                  (clojure.string/join "\n  "
                                       (map #(clojure.string/join " " %)
                                            (partition-all 6 all)))
                  ")"))))
