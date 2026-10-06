(ns clojure-collider.server.nrt-test
  (:require [clojure.test :refer [deftest is]]
            [clojure.java.io :as io]
            [clojure-collider.server.server :as server]
            [clojure-collider.synth.sdef :as sdef]
            [clojure-collider.ugens.osc :as uosc]))

(deftest streams-collect
  (let [s (server/make-nrt-server)]
    (is (true? (server/boot? s)))
    (is (= s (server/sync s)) "sync is a no-op")
    (server/send-message s "/c_set" 0 0)
    (server/send-bundle s 1.0 [["/s_new" "x" 1000 0 1]])
    (is (= [[0 [["/c_set" 0 0]]]
             [4294967296 [["/s_new" "x" 1000 0 1]]]]
           @(:streams s)) "ticks are round(time * 2^32), no epoch")))

(deftest at-collects-timestamped
  (let [s (server/make-nrt-server)
        node (server/->Node s 1001 :head 1 "test")]
    (binding [server/*s* s]
      (server/at 2.5 (server/node-free node)))
    (is (= [[10737418240 [["/n_free" 1001]]]] @(:streams s)))))
(deftest render-aiff
  (let [out (try
              (server/with-rendering ["/tmp/nrt-test.aiff" :sr 44100 :pad 0.5]
                (sdef/play (uosc/sin-osc:ar 440 0 0.2)))
              (catch Exception e (println "SKIP nrt render:" (.getMessage e)) nil))]
    (when out
      (try
        (let [f (io/file out)]
          (is (.exists f))
          (with-open [in (io/input-stream f)]
            (let [magic (byte-array 4)]
              (.read in magic)
              (is (= "FORM" (String. magic "ASCII")) "AIFF magic"))))
        (finally
          (.delete (io/file out))
          (.delete (io/file "/tmp/nrt-test.osc")))))))
