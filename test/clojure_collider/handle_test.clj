(ns clojure-collider.handle-test
  (:require [clojure.test :refer [deftest is testing]]
            [clojure-collider.handle :as handle]))

(deftest register-resolve-release
  (testing "parts round-trip, release forgets, unknown is nil"
    (let [h (handle/register! {:server :s :x 1})]
      (is (= {:server :s :x 1} (handle/resolve-handle h)))
      (is (nil? (handle/release! h)))
      (is (nil? (handle/resolve-handle h)))
      (is (nil? (handle/release! h)))
      (is (nil? (handle/resolve-handle {:id 999999})))
      (is (nil? (handle/resolve-handle nil))))))

(deftest value-stays-small
  (testing "a handle holding a huge map still prints tiny"
    (let [big (into {} (map (fn [i] [i (apply str (repeat 1000 "x"))]) (range 200)))
          h (handle/register! {:server :s :blob big})]
      (is (< (count (pr-str h)) 100))
      (handle/release! h))))
