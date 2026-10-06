(ns clojure-collider.synth.convert-test
  "convert-code parity: head rewriting, binding awareness, table API."
  (:require [clojure.test :refer [deftest is]]
            [clojure-collider.synth.convert :as conv]))

(deftest table-api
  (is (= '+ (conv/synthdef-equivalent-function '+)))
  (conv/set-synthdef-equivalent! 'my-sin 'sin)
  (is (= 'sin (conv/synthdef-equivalent-function 'my-sin)))
  (conv/set-synthdef-equivalent! 'my-sin nil)
  (is (= 'my-sin (conv/synthdef-equivalent-function 'my-sin))))

(deftest head-rewriting
  (conv/set-synthdef-equivalent! 'blip 'sin-osc)
  (is (= '(sin-osc 440) (conv/convert-code '(blip 440))))
  (is (= '(sin-osc (+ 1 2)) (conv/convert-code '(blip (+ 1 2)))))
  (conv/set-synthdef-equivalent! 'blip nil)
  (is (= '(blip 440) (conv/convert-code '(blip 440)))))

(deftest binding-awareness
  ;; let syms untouched, inits + body rewritten
  (conv/set-synthdef-equivalent! 'blip 'sin-osc)
  (is (= '(let [blip (sin-osc 440)] (sin-osc blip))
         (conv/convert-code '(let [blip (blip 440)] (blip blip)))))
  (is (= '(loop [x (sin-osc 1)] (sin-osc x))
         (conv/convert-code '(loop [x (blip 1)] (blip x)))))
  (is (= '(fn [a] (sin-osc a))
         (conv/convert-code '(fn [a] (blip a)))))
  (is (= '(with-controls [f (sin-osc 1)] (sin-osc f))
         (conv/convert-code '(with-controls [f (blip 1)] (blip f)))))
  (conv/set-synthdef-equivalent! 'blip nil))

(deftest atoms-passthrough
  (is (nil? (conv/convert-code nil)))
  (is (= 440 (conv/convert-code 440)))
  (is (= 'freq (conv/convert-code 'freq)))
  (is (= '(+ a b) (conv/convert-code '(+ a b)))))
