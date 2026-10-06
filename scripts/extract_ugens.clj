(ns extract-ugens
  "Parse upstream ugens/*.lisp defugen forms into EDN IR.
   Usage: clojure -M scripts/extract_ugens.clj > /tmp/ugen_ir.edn"
  (:require [clojure.edn :as edn]))

;; -- mini s-expr reader (comments stripped, strings kept) ---------------------
(defn- read-string-tok [s i]
  (let [n (count s)]
    (loop [j (inc i) sb (StringBuilder.)]
      (let [d (.charAt s j)]
        (cond (= d \\) (do (.append sb (.charAt s (inc j))) (recur (+ j 2) sb))
              (= d \") [(inc j) [:str (str sb)]]
              :else (do (.append sb d) (recur (inc j) sb)))))))

(defn- read-atom-end [s i]
  (let [n (count s)]
    (loop [k i]
      (if (or (>= k n) (#{ \( \) \" \space \tab \newline \return \;} (.charAt s k)))
        k (recur (inc k))))))

(defn tokenize [s]
  (let [n (count s)]
    (loop [i 0 toks []]
      (if (>= i n) toks
        (let [c (.charAt s i)]
          (cond
            (Character/isWhitespace c) (recur (inc i) toks)
            (= c \;) (let [j (or (clojure.string/index-of s "\n" i) n)]
                       (recur (inc j) toks))
            (= c \() (recur (inc i) (conj toks :lp))
            (= c \)) (recur (inc i) (conj toks :rp))
            (= c \') (recur (inc i) (conj toks :q))
            (= c \") (let [[j t] (read-string-tok s i)] (recur j (conj toks t)))
            (= c \#) (let [d (.charAt s (inc i))]
                       (cond (= d \') (recur (+ i 2) (conj toks :fn-q))
                             (= d \() (recur (+ i 2) (conj toks :vec-lp))
                             :else (let [j (read-atom-end s (inc i))]
                                     (recur j (conj toks [:char (subs s i j)])))))
            :else (let [j (read-atom-end s i)]
                    (recur j (conj toks [:atom (subs s i j)])))))))))
(declare read-form)

(defn read-forms [toks]
  (loop [t toks acc []]
    (if (empty? t) [acc nil]
      (let [[f rest] (read-form t)]
        (recur rest (conj acc f))))))

(defn read-form [[t & r]]
  (cond (= t :lp) (loop [t r acc []]
                    (let [[f rest] (read-form t)]
                      (if (= f :rp) [acc rest]
                        (recur rest (conj acc f)))))
        (= t :vec-lp) (loop [t r acc []]
                        (let [[f rest] (read-form t)]
                          (if (= f :rp) [(vec acc) rest]
                            (recur rest (conj acc f)))))
        (= t :q) (let [[f rest] (read-form r)] [[`quote f] rest])
        (= t :fn-q) (let [[f rest] (read-form r)] [[`function f] rest])
        :else [t r]))

(defn atom-val [[_ s]]
  (cond (re-matches #"-?\d+" s) (Long/parseLong s)
        (re-matches #"-?\d*\.\d+(e[+-]?\d+)?" (clojure.string/lower-case s)) (Double/parseDouble s)
        (= s "t") true
        (= s "nil") nil
        (clojure.string/starts-with? s ":") (keyword (subs s 1))
        :else (symbol s)))

(defn simplify [f]
  (if (vector? f)
    (cond (= :atom (first f)) (atom-val f)
          (= :str (first f)) (second f)
          (= :char (first f)) (symbol (second f))
          (and (= 2 (count f)) (symbol? (first f)) (= "quote" (name (first f))))
          (list 'quote (simplify (second f)))
          (and (= 2 (count f)) (symbol? (first f)) (= "function" (name (first f))))
          (list 'function (simplify (second f)))
          :else (mapv simplify f))
    f))

(defn parse-file [path]
  (let [toks (tokenize (slurp path))
        [forms _] (read-forms toks)]
    (mapv simplify forms)))

;; -- lambda-list parse ---------------------------------------------------------
(defn parse-lambda-list [ll]
  (loop [t (seq ll) mode :req req [] opt [] key [] rest nil allow-other false]
    (if (empty? t) {:req req :opt opt :key key :rest rest :allow-other allow-other}
      (let [x (first t)]
        (cond (= x '&optional) (recur (next t) :opt req opt key rest allow-other)
              (= x '&key) (recur (next t) :key req opt key rest allow-other)
              (= x '&rest) (recur (next t) :rest req opt key rest allow-other)
              (= x '&allow-other-keys) (recur (next t) mode req opt key rest true)
              (= x '&body) (recur (next t) :rest req opt key rest allow-other)
              (= mode :req) (recur (next t) mode (conj req x) opt key rest allow-other)
              (= mode :opt) (recur (next t) mode req (conj opt (if (sequential? x)
                                                                {:name (first x) :default (second x)}
                                                                {:name x :default nil})) key rest allow-other)
              (= mode :key) (recur (next t) mode req opt (conj key (if (sequential? x)
                                                                    {:name (if (sequential? (first x))
                                                                             (second (first x)) (first x))
                                                                     :default (second x)}
                                                                    {:name x :default nil})) rest allow-other)
              (= mode :rest) (recur (next t) mode req opt key x allow-other))))))

;; -- defugen IR -----------------------------------------------------------------
(defn free-calls
  "Symbols in call position, minus bound names and known macros."
  [body bound]
  (let [known '#{multinew funcall apply madd new declare let let* flet labels if when unless cond case ecase
                 setf setq progn prog1 loop dotimes dolist lambda quote function
                 + - * / = < > <= >= eql equal and or not max min list cons car cdr first second third rest
                 length nth elt aref make-array vector ensure-list ensure-cons chan-offset}]
    (into #{}
          (comp (filter sequential?) (map first)
                (filter symbol?) (remove #(contains? (into known bound) %)))
          (tree-seq sequential? seq body))))

(defn classify-variant [[ratespec & body]]
  (let [rate (if (sequential? ratespec) (first ratespec) ratespec)
        body (remove #(and (sequential? %) (= 'declare (first %))) body)
        b1 (first body)
        madd? (and (sequential? b1) (= 'madd (first b1)))
        madd-tail (if madd? (max 0 (- (count b1) 2)) 0)
        core (if madd? (second b1) b1)
        [flavor inputs] (cond
                          (and (sequential? core) (= 'multinew (first core))
                               (= 'new (second core)) (sequential? (nth core 2 nil))
                               (= 'quote (first (nth core 2))))
                          [(second (nth core 2)) (drop 3 core)]
                          (and (sequential? core) (= 'funcall (first core))
                               (= 'new (second core)) (sequential? (nth core 2 nil))
                               (= 'quote (first (nth core 2))))
                          [(str (second (nth core 2)) "*apply") (drop 3 core)]
                          :else [:custom core])]
    {:rate rate :madd madd? :madd-tail madd-tail :flavor (str flavor)
     :inputs (mapv str inputs) :raw (str (first body))
     :custom-calls (sort (map str (free-calls body #{})))}))

(defn defugen-ir [form file]
  (let [[_ [lname scname] args & more] form
        ;; `function` binds the VARIANTS LIST; kwargs follow
        variants-list (first (filter #(and (sequential? %)
                                           (not (#{'function 'quote 'lambda} (first %))))
                                     more))
        check (second (drop-while #(not= % :check-fn) more))
        sig (second (drop-while #(not= % :signal-range) more))]
    {:file file :lisp-name (str lname) :sc-name scname
     :args (parse-lambda-list args)
     :check-fn (cond (nil? check) :default
                     (and (sequential? check) (= 'function (first check))) (str "fn:" (second check))
                     (and (sequential? check) (= 'lambda (first check))) (str "lambda:" (pr-str (nth check 2 nil)))
                     :else (str check))
     :signal-range (or sig :bipolar)
     :variants (mapv classify-variant (or variants-list []))}))

(defn -main []
  (let [roots [["" "ugens/"]
               ["SC3plugins/" "ugens/SC3plugins/"]
               ["Extensions/" "ugens/Extensions/"]
               ["quarks/miSCellaneous_lib/" "ugens/quarks/miSCellaneous_lib/"]
               ["quarks/redSys/" "ugens/quarks/redSys/"]]
        base "/home/bobby/dev/ai/ports/super-colider/cl-collider/"]
    (doseq [[prefix rel] roots
            f (sort (map str (.list (java.io.File. (str base rel)))))]
      (when (clojure.string/ends-with? f ".lisp")
        (let [forms (parse-file (str base rel f))
              fname (str prefix f)]
          (doseq [form forms]
            (try
              (when (and (sequential? form) (= 'defugen (first form)))
                (prn (defugen-ir form fname)))
              (when (and (sequential? form) (= 'def-pv-chain-ugen (first form)))
                (let [[lname sc] (second form)
                      args (nth form 2 nil)
                      body (drop 3 form)]
                  (prn (defugen-ir (list* 'defugen (list lname sc) args
                                          (list (list* :kr body)) :check-fn nil)
                                   fname))))
              (when (and (sequential? form) (= 'def-info-ugen-base (first form)))
                (let [[lname sc] (second form)]
                  (prn {:file fname :lisp-name (str lname) :sc-name sc
                        :args {:req [] :opt [] :key [] :rest nil :allow-other false}
                        :check-fn :default :signal-range :bipolar
                        :variants [{:rate :ir :madd false :flavor "ugen" :inputs []
                                    :raw "[multinew new (quote ugen)]" :custom-calls ()}]})))
              (when (and (sequential? form) (= 'def-buf-info-ugen-base (first form)))
                (let [[lname sc] (second form)]
                  (prn {:file fname :lisp-name (str lname) :sc-name sc
                        :args {:req [(symbol "bufnum")] :opt [] :key [] :rest nil :allow-other false}
                        :check-fn :default :signal-range :bipolar
                        :variants [{:rate :kr :madd false :flavor "ugen" :inputs ["bufnum"]
                                    :raw "[multinew new (quote ugen) bufnum]" :custom-calls ()}
                                   {:rate :ir :madd false :flavor "ugen" :inputs ["bufnum"]
                                    :raw "[multinew new (quote ugen) bufnum]" :custom-calls ()}]})))
              (when (and (sequential? form) (= 'defun (first form))
                         (re-matches #".*\.(ar|kr|ir)" (str (second form))))
                (prn {:file fname :defun (str (second form))
                      :args (parse-lambda-list (nth form 2 nil))
                      :custom true}))
              (catch Exception e
                (binding [*out* *err*]
                  (println "FAILED" fname (pr-str (take 2 form)) (.getMessage e)))))))))))

(-main)
