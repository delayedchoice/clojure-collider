(ns thursday-afternoon-test
  "redefine-sequences! lets loop edits sound without rebooting: shrink
   `loops`, redefine, and only the remaining loops play; restore and all
   15 play again. Boots a real scsynth; skips when it can't boot."
  (:require [clojure.test :refer [deftest is testing]]
            [clojure-collider.handle :as handle]
            [pattern :as p]
            [thursday-afternoon :as th]))

(defn- try-start [port]
  (try
    (th/start! :port port)
    (catch Exception e (println "SKIP redefine:" (.getMessage e)) nil)))

(defn- task-count [h]
  (count @(:tasks (:clock (handle/resolve-handle h)))))

(deftest redefine-sequences-takes-effect-live
  (when-let [h (try-start 57193)]
    (try
      (testing "all 15 loops play after start"
        (is (= 15 (task-count h))))
      (testing "shrinking loops stops the removed ones"
        (let [subset (vec (take 3 thursday-afternoon/loops))]
          (with-redefs [thursday-afternoon/loops subset]
            (let [r (th/redefine-sequences! h)]
              (is (= {:redefined 3 :restarted 3 :stopped-stale 12} r))
              (is (= 3 (task-count h)))))))
      (testing "restoring loops brings all 15 back"
        (let [r (th/redefine-sequences! h)]
          (is (= {:redefined 15 :restarted 15 :stopped-stale 0} r))
          (is (= 15 (task-count h)))))
      (finally
        (p/halt! h)))))
