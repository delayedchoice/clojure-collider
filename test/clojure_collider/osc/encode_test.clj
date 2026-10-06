(ns clojure-collider.osc.encode-test
  (:require [clojure.test :refer [deftest is]]
            [clojure-collider.osc.encode :as enc])
  (:import [java.util Arrays]))

(defn bytes= [a b] (Arrays/equals ^bytes a ^bytes b))

(deftest immediate-timetag
  (is (bytes= (byte-array [0 0 0 0 0 0 0 1]) enc/immediate-timetag-bytes))
  (is (bytes= enc/immediate-timetag-bytes (enc/sc-encode-timetag :now))))

(deftest osc-strings
  (is (bytes= (byte-array [47 115 116 97 116 117 115 0 44 0 0 0]) ; "/status\0,\0\0\0"
              (enc/encode-message "/status")))
  (is (bytes= (enc/osc-string-bytes "/sync")
              (byte-array [47 115 121 110 99 0 0 0]))))

(deftest int-float-encodings
  (let [i (enc/encode-int32 1)]
    (is (= [0 0 0 1] (map #(bit-and % 255) i))))
  (let [i (enc/encode-int32 -1)]
    (is (= [255 255 255 255] (map #(bit-and % 255) i))))
  (let [f (enc/encode-float32 (float 1.0))]
    (is (= 4 (alength f))))
  (let [d (enc/encode-float64 (double 1.0))]
    (is (= 8 (alength d)))))

(deftest typetags
  ;; typetags are padded to 4 bytes with nulls, so string will contain nulls
  (is (= 4 (alength (enc/sc-encode-typetags [1]))))
  (is (bytes= (byte-array [44 105 0 0]) (enc/sc-encode-typetags [1])))
  (is (bytes= (byte-array [44 102 0 0]) (enc/sc-encode-typetags [(float 1.0)])))
  (is (bytes= (byte-array [44 100 0 0]) (enc/sc-encode-typetags [(double 1.0)])))
  (is (bytes= (byte-array [44 115 0 0]) (enc/sc-encode-typetags ["hi"])))
  ;; array -> [iii] for single vector arg
  (let [tags (enc/sc-make-type-tags [[1 2 3]])]
    (is (= "[iii]" (apply str tags)))))

(deftest encode-decode-roundtrip
  (doseq [[addr args] [["/status" []]
                       ["/sync" [-1]]
                       ["/s_new" ["sine" 1000 1 0 "freq" (float 440)]]
                       ["/n_set" [1000 "freq" (double 440.0)]]]]
    (let [msg (apply enc/encode-message addr args)
          [daddr _dtags vals] (enc/decode-message msg)]
      (is (= addr daddr))
      (is (= (count args) (count vals)))
      ;; type-specific: ints exact, floats approx
      (doseq [[a v] (map vector args vals)]
        (cond (integer? a) (is (= a v))
              (float? a) (is (< (Math/abs (- a v)) 1e-5))
              (instance? Double a) (is (< (Math/abs (- (double a) (double v))) 1e-9))
              (string? a) (is (= a v)))))))

(deftest bundle-encoding
  (let [b (enc/encode-bundle [["/sync" -1]] :now)]
    (is (= 35 (bit-and (aget b 0) 255))) ; '#'
    (is (bytes= (byte-array [35 98 117 110 100 108 101 0]) (Arrays/copyOfRange b 0 8)))
    (is (bytes= enc/immediate-timetag-bytes (Arrays/copyOfRange b 8 16)))
    ;; should contain message length 16
    (let [len (enc/decode-int32 b 16)]
      (is (= 16 len))))
  (let [b (enc/encode-bundle [["/sync" -1] ["/notify" 1]] :now)
        dec (enc/decode-bundle b)]
    (is (= 2 (count (:messages dec))))
    (is (= "/sync" (first (first (:messages dec)))))
    (is (= "/notify" (first (second (:messages dec)))))))
