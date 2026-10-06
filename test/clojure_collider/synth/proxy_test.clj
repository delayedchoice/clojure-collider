(ns clojure-collider.synth.proxy-test
  (:require [clojure.test :refer [deftest is testing]]
            [clojure-collider.server.server :as server]
            [clojure-collider.synth.sdef :as sdef]
            [clojure-collider.ugens.osc :as uosc]))

(deftest controls-scan
  (is (nil? (sdef/get-controls-list nil)))
  (is (nil? (sdef/get-controls-list '(out:ar 0 (sin-osc:ar 440)))) "no with-controls")
  (is (= '[freq 440] (sdef/get-controls-list '(out:ar 0 (with-controls [freq 440] (sin-osc:ar freq))))))
  (is (= '((freq 440)) (sdef/get-controls-list
                         '(ctl/with-controls ((freq 440)) (sin-osc:ar freq))))
      "qualified heads match")
  (is (= "pz" (sdef/proxy-key-name :pz)))
  (is (= "pz" (sdef/proxy-key-name "PZ"))))

(deftest live-proxy-cycle
  (let [s (try
            (let [sv (server/make-external-server "proxy-test" :port 57120
                                                  :just-connect? true)]
              (server/server-boot sv)
              sv)
            (catch Exception e (println "SKIP proxy live:" (.getMessage e)) nil))]
    (when s
      (try
        (binding [server/*s* s]
          (testing "create + replace + ctrl + free"
            (let [n1 (sdef/proxy :pz (uosc/sin-osc:ar 440 0 0.1))]
              (is (= :pz (first (keys @(:node-proxy-table s)))))
              (is (server/is-playing? n1))
              (Thread/sleep 300)
              (let [n2 (sdef/proxy :pz (uosc/sin-osc:ar 660 0 0.1))]
                (is (= n2 (get @(:node-proxy-table s) :pz)) "table swaps")
                (is (loop [i 0]
                      (cond (not (server/is-playing? n1)) true
                            (> i 100) false
                            :else (do (Thread/sleep 20) (recur (inc i)))))
                    "old node gate-frees after release")
                (Thread/sleep 300)
                (sdef/proxy-ctrl :pz :out-bus 0)
                (is (= [:out-bus 0] (sdef/synthdef-metadata :pz :params)))
                (sdef/proxy :pz nil)
                (is (loop [i 0]
                      (cond (not (server/is-playing? n2)) true
                            (> i 100) false
                            :else (do (Thread/sleep 20) (recur (inc i)))))
                    "nil body frees")))))
        (finally
          (server/server-quit s))))))
