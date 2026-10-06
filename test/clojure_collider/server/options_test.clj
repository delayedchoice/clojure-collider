(ns clojure-collider.server.options-test
  (:require [clojure.test :refer [deftest is testing]]
            [clojure-collider.server.options :as opts]))

(deftest defaults
  (let [o (opts/make-server-options)]
    (is (= 16384 (:num-control-bus o)))
    (is (= 1024 (:num-audio-bus o)))
    (is (= 0 (:verbosity o)))
    (is (nil? (:device o))))
  (let [o (opts/make-server-options {:num-output-bus 2 :verbosity -1})]
    (is (= 2 (:num-output-bus o)))
    (is (= -1 (:verbosity o)))))

(deftest cli-build
  (let [args (opts/build-server-options
              (opts/make-server-options {:ugen-plugins-path []}))]
    (is (= ["-c" "16384" "-a" "1024" "-i" "8" "-o" "8" "-z" "64"
            "-b" "1024" "-n" "1024" "-d" "1024" "-m" "8192" "-w" "64"
            "-r" "64" "-D" "1" "-R" "1" "-l" "64" "-V" "0"]
           args)))
  (testing "nil/false omitted, -U joined, hw-buffer 0 dropped"
    (let [args (opts/build-server-options
                {:num-control-bus 1024 :device nil :verbosity false
                 :hardware-buffer-size 0 :ugen-plugins-path ["/a" "/b"]})]
      (is (= ["-c" "1024" "-U" "/a:/b"] args)))))
