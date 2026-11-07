(ns clojure-collider.patterns.clock-rig-test
  "Rig setup must survive REPL re-runs: a second connect-server! in one
   session used to keep the dead first backend, and every pattern step
   NPEd in send-bundle (nil device) before reaching the live one."
  (:require [clojure.test :refer [deftest is testing]]
            [clojure-collider.server.server :as server]
            [clojure-collider.server.scheduler :as sched]
            [patterns.clock :as clock]))

 (defn- try-make [name port]
   (try
     (server/make-external-server name :port port)
     (catch Exception e (println "SKIP rig:" (.getMessage e)) nil)))
(deftest rerun-prunes-dead-backend
  (clock/start-clock-loop :tempo 90 :force true)
  (let [s1 (try-make "rig-a" 57182)]
    (when s1
      (try
        (binding [server/*s* (clock/connect-server! s1 :tempo 90)]
          (testing "first rig wires one backend"
            (is (= 1 (count @(:enabled-backends clock/*clock*))))))
        (server/server-quit s1)
        (let [s2 (try-make "rig-b" 57183)]
          (when s2
            (try
              (binding [server/*s* (clock/connect-server! s2 :tempo 90)]
                (let [bs @(:enabled-backends clock/*clock*)]
                  (testing "dead first backend pruned, live one kept"
                    (is (= 1 (count bs)))
                    (is (identical? s2 (:server (first bs)))))))
              (finally
                (when (server/boot? s2) (server/server-quit s2))))))
        (finally
          (clock/stop :all)
          (when (server/boot? s1) (server/server-quit s1))
           (when-let [tc (:tempo-clock clock/*clock*)]
            (sched/tempo-clock-stop tc)))))))
