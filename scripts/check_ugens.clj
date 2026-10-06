(require '[clojure.java.io :as io])
(let [base (java.io.File. "src/clojure_collider/ugens")
      files (->> (file-seq base)
                 (filter #(.isFile ^java.io.File %))
                 (map #(.getPath ^java.io.File %))
                 (filter #(clojure.string/ends-with? % ".clj"))
                 sort)]
  (doseq [f files]
    (let [rel (subs f (inc (count (.getPath base))))
          ns-sym (str "clojure-collider.ugens."
                      (-> rel
                          (clojure.string/replace #"\.clj$" "")
                          (clojure.string/replace "_" "-")
                          (clojure.string/replace "/" ".")))]
      (try (require (symbol ns-sym))
           (println :ok ns-sym (count (ns-publics (symbol ns-sym))))
        (catch Exception e
          (println :FAIL ns-sym (.getMessage e)))))))
