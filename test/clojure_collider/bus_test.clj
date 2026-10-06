(ns clojure-collider.bus-test
  (:require [clojure.test :refer [deftest is]]
            [clojure-collider.bus :as bus])
  (:import [java.util.concurrent.locks ReentrantLock]))

(defn- fake-server [audio control]
  {:lock (ReentrantLock.)
   :audio-buses (atom (vec (repeat audio nil)))
   :control-buses (atom (vec (repeat control nil)))})

(deftest alloc-free
  (let [s (fake-server 8 8)
        b (bus/bus-audio s)]
    (is (= 0 (:busnum b)))
    (is (= 1 (:chanls b)))
    (let [c (bus/bus-control s :chanls 2)]
      (is (= [0 0] (mapv :busnum (take 2 @(:control-buses s)))))
      (is (= 0 (:busnum c))))
    (bus/bus-free b)
    (is (nil? (first @(:audio-buses s))))
    ;; slot reused
    (is (= 0 (:busnum (bus/bus-audio s))))))

(deftest consecutive-search
  (let [s (fake-server 8 8)]
    (bus/bus-audio s :busnum 0)
    (bus/bus-audio s :busnum 2)
    ;; 0 and 2 taken -> 2-wide lands at 3
    (is (= 3 (:busnum (bus/bus-audio s :chanls 2))))
    (is (thrown? clojure.lang.ExceptionInfo
                 (bus/bus-audio s :chanls 8)))
    (is (thrown? clojure.lang.ExceptionInfo
                 (bus/bus-audio s :chanls 1 :busnum 99)))))

(deftest bus-string
  (let [s (fake-server 8 8)]
    (is (= "a4" (bus/bus-string (bus/bus-audio s :busnum 4))))
    (is (= "c2" (bus/bus-string (bus/bus-control s :busnum 2))))))
