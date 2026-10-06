(ns clojure-collider.synth.convert
  "Body rewriter: synthdef-equivalent-function + convert-code.
   Mirrors synthdef.lisp. With overloaded operators (+ - * / < > ...),
   arithmetic needs no head rewriting, so the default table is empty;
   the mechanism stays for user aliases. The (synth ...) embedding branch
   needs synthdef-metadata (Phase 5) and is deferred.")

(def function-table
  "Head-symbol -> synthdef-compatible equivalent. Empty by default:
   overloaded operators already dispatch, so (+ a b) Just Works."
  (atom {}))

(defn synthdef-equivalent-function [f]
  (get @function-table f f))

(defn set-synthdef-equivalent!
  "Register VALUE as the equivalent of F; nil VALUE removes the entry."
  [f value]
  (if (nil? value)
    (swap! function-table dissoc f)
    (swap! function-table assoc f value))
  value)

(declare convert-code)

(defn- convert-binding-pairs
  "Walk init exprs of [sym init ...] pairs, leaving syms alone."
  [pairs]
  (vec (mapcat (fn [[s init]] [s (convert-code init)])
               (partition 2 pairs))))

(defn convert-code
  "Rewrite head-position symbols via the table, recursing into arg
   position. Binding symbols (let/loop/doseq/dotimes/fn/with-controls)
   are never rewritten; their init/value exprs are."
  ([form] (convert-code form false))
  ([form head?]
   (cond (nil? form) nil
         (and (symbol? form) head?) (synthdef-equivalent-function form)
         (symbol? form) form
         (vector? form)
         (mapv convert-code form)
         (sequential? form)
         (let [[h & t] (vec form)]
           (cond (and (symbol? h) (contains? #{'let 'let* 'loop} h)
                      (vector? (first t)))
                 (list* h (convert-binding-pairs (first t))
                        (map convert-code (rest t)))

                 (and (symbol? h) (contains? #{'doseq 'dotimes} h)
                      (vector? (first t)))
                 (let [[s init] (first t)]
                   (list* h [s (convert-code init)]
                          (map convert-code (rest t))))

                 (and (symbol? h) (= h 'fn))
                 (if (vector? (first t))
                   (list* h (first t) (map convert-code (rest t)))
                   (list* h (first t) (second t)
                          (map convert-code (drop 2 t))))

                 (and (symbol? h) (= h 'with-controls)
                      (vector? (first t)))
                 (list* h (vec (mapcat (fn [[s init]] [s (convert-code init)])
                                       (partition 2 (first t))))
                        (map convert-code (rest t)))

                 :else (cons (convert-code h true) (map convert-code t))))
         :else form)))
