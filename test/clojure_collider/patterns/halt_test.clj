(ns clojure-collider.patterns.halt-test
  "halt! must stop a live rig now: sounding synth + looping pattern task
   go silent, tasks drop, server quits. Boots a real scsynth (like
   transmit-test's live server); skips when scsynth can't boot."
  (:require [clojure.test :refer [deftest is testing]]
            [clojure-collider.handle :as handle]
            [clojure-collider.server.server :as server]
            [clojure-collider.synth.sdef :as sdef]
            [patterns.clock :as clock]
            [pattern :as p]
            [ugens :as ug]))

(defn- try-rig [port]
  (try
    (let [s (server/make-external-server "halt-test" :port port)
          c (clock/start-clock-loop :tempo 60)]
      (clock/connect-server! c s)
      [s c])
    (catch Exception e (println "SKIP halt:" (.getMessage e)) nil)))

(deftest halt-stops-sound-and-quits
  (when-let [[s c] (try-rig 57197)]
    (try
      (sdef/defsynth s :halt-ping [freq 440 amp 0.1]
        (ug/out:ar 0 (ug/pan2:ar (ug/sin-osc:ar freq) 0.0)))
      (sdef/synth s :halt-ping)
      (p/pdef :halt-pat (p/pbind :instrument :halt-ping :freq 440 :dur 0.5))
      (p/play c :halt-pat)
      (Thread/sleep 1500)
      (testing "rig is sounding"
        (is (server/boot? s))
        (is (pos? (count @(:tasks c)))))
      (testing "halt silences and quits"
        (is (= :halted (clock/halt! c)))
        (is (empty? @(:tasks c)))
        (is (not (server/boot? s))))
      (finally
        (when (server/boot? s) (server/server-quit s))))))

(deftest halt-accepts-handles-and-parts-maps
  (testing "library handle and legacy full map both resolve (start!/halt! symmetry)"
    (when-let [[s c] (try-rig 57196)]
      (try
        (let [h (handle/register! {:server s :clock c})]
          (is (= :halted (clock/halt! h)))
          (is (not (server/boot? s)))
          (handle/release! h))
        (finally
          (when (server/boot? s) (server/server-quit s)))))
    (when-let [[s c] (try-rig 57194)]
      (try
        (testing "legacy full map works without a registry entry"
          (is (= :halted (clock/halt! {:server s :clock c})))
          (is (not (server/boot? s))))
        (finally
          (when (server/boot? s) (server/server-quit s)))))))
