(ns clojure-collider.buffer-test
  (:require [clojure.test :refer [deftest is testing]]
            [clojure.java.io :as io]
            [clojure-collider.buffer :as buf]
            [clojure-collider.osc.encode :as enc]
            [clojure-collider.server.server :as server]))

(def live-port 57120)

(defn- with-server [f]
  (let [s (try
            (let [sv (server/make-external-server "buftest" :port live-port
                                                  :just-connect? true)]
              (server/server-boot sv)
              sv)
            (catch Exception e (println "SKIP buffer tests:" (.getMessage e)) nil))]
    (when s
      (try (binding [server/*s* s] (f s))
           (finally (server/server-quit s))))))

(deftest buffer-dur-pure
  (is (= 1.0 (buf/buffer-dur (buf/map->Buffer {:frames 44100 :chanls 1 :sr 44100})))))

(deftest completion-blob-single-wrap
  (testing "completion args are raw message bytes; the encoder blob-wraps
   byte[] itself. Pre-wrapping double-blobs and scsynth silently drops the
   completion (no /b_info, no error)."
    (let [raw (@#'buf/query-blob 7)
          msg (apply enc/encode-message "/b_alloc"
                     [(int 7) (int 512) (int 1) raw (float 0.0)])
          [_ tags [_n _ _ blob _]] (enc/decode-message msg)]
      (is (= ",iiibf" (str "," tags)))
      (is (bytes? blob))
      ;; single wrap: inner message decodes straight to /b_query
      (let [[addr _ [qn]] (enc/decode-message blob)]
        (is (= "/b_query" addr))
        (is (= 7 qn))))))

(deftest handler-keys-round-trip
  (testing "await-buffer-reply stores under the exact key the /b_set* responder
   looks up. Both sides must use Integer bufnum parts: ConcurrentHashMap
   misses across Integer/Long (proven by probe), so any side rebuilt with
   long breaks dispatch. End-to-end parity is covered by the live get/getn
   tests below (they pass only if registration and responder agree)."
    (let [m (java.util.concurrent.ConcurrentHashMap.)
          server {:buffer-get-handlers m}
          seen (promise)]
      (server/await-buffer-reply server ["/b_set" (int 3) (int 0)]
                                 (fn [v] (deliver seen v)))
      (let [q (.get m ["/b_set" (int 3) (int 0)])]
        (is (some? q))
        ((.poll q) 0.5)
        (is (= 0.5 (deref seen 1000 :timeout)))))))

(deftest alloc-info-free
  (with-server
   (fn [s]
     (testing "alloc fills frames/chanls/sr via /b_info"
       (let [b (buf/buffer-alloc 512 :server s)]
         (is (= 512 (:frames b)))
         (is (= 1 (:chanls b)))
         (is (pos? (:sr b)))
         (testing "set/get round-trip"
           (buf/buffer-set b 0 0.25)
           (is (< (Math/abs (- 0.25 (buf/buffer-get b 0))) 1e-4)))
         (testing "zero + getn"
           (buf/buffer-zero b)
           (is (every? #(= 0.0 (float %)) (buf/buffer-getn b 0 4))))
         (testing "free reuses slot"
           (let [n (:bufnum b)]
             (buf/buffer-free b)
             (is (nil? (nth @(:buffers s) n)))
             (is (= n (:bufnum (buf/buffer-alloc 64 :server s))))
             (buf/buffer-free n s))))))))

(deftest consecutive-alloc
  (with-server
   (fn [s]
     (let [bs (buf/buffer-alloc-consecutive 3 128 :server s)]
       (is (= 3 (count bs)))
       (is (= [(:bufnum (first bs)) (:bufnum (second bs)) (:bufnum (nth bs 2))]
              (range (:bufnum (first bs)) (+ 3 (:bufnum (first bs))))))
       (is (every? #(= 128 (:frames %)) bs))
       (doseq [b bs] (buf/buffer-free b))))))

(deftest buffer-copy-round-trip
  (testing "copy lands frames in dst (pins the /b_gen address fix)"
    (with-server
     (fn [s]
       (let [src (buf/buffer-alloc 8 :server s)
             dst (buf/buffer-alloc 8 :server s)]
         (buf/buffer-set src 0 0.75)
         (buf/buffer-copy dst src)
         (is (< (Math/abs (- 0.75 (buf/buffer-get dst 0))) 1e-4))
         (buf/buffer-free src)
         (buf/buffer-free dst))))))

(deftest pure-wavetable-math
  (testing "wavetable formats are exact ports (float-preserving)"
    (is (= [2.0 -1.0 -1.0 1.0] (buf/as-wavetable [1.0 0.0])))
    (is (= [2.0 -1.0 -1.0 1.0] (buf/vector-in-wavetable-format [1.0 0.0])))
    (is (= [2.0 -1.0] (buf/as-wavetable-no-wrap [1.0 0.0])))
    (is (= [0.0 0.5 1.0] (buf/linear-resample [0.0 1.0] 3)))
    (is (= [1 2 3] (buf/linear-resample [1 2 3] 3)) "identity returns input")))

(deftest wav-file-round-trip
  (testing "LE float32 WAV parses back to the same samples"
    (let [tmp (java.io.File/createTempFile "wavtest" ".wav")
          vs [0.5 -0.25 0.0 1.0]]
      (try
        (buf/write-mono-fl32-wav (.getAbsolutePath tmp) 44100 vs)
        (let [bytes (java.nio.file.Files/readAllBytes (.toPath tmp))
              bb (doto (java.nio.ByteBuffer/wrap bytes)
                   (.order java.nio.ByteOrder/LITTLE_ENDIAN))]
          (is (= "RIFF" (String. (doto (byte-array 4) (->> (.get bb))) "ASCII")))
          (.position bb 44)
          (is (= vs (vec (repeatedly 4 #(.getFloat bb))))))
        (finally (.delete tmp))))))

(deftest nrt-command-shapes
  (testing "/b_gen and chunked /b_setn collect exact score messages"
    (let [s (server/make-nrt-server)
          buf (buf/map->Buffer {:bufnum 7 :server s :frames 4096 :chanls 1})]
      (buf/buffer-fill buf :sine [1.0 0.5] :server s)
      (is (= [[0 [["/b_gen" 7 "sine1" 7 1.0 0.5]]]] @(:streams s)))
      (reset! (:streams s) [])
      (buf/buffer-fill buf :cheby [0.3] :server s :normalize false)
      (is (= [[0 [["/b_gen" 7 "cheby" 6 0.3]]]] @(:streams s))
          "flags = wavetable + clear, no normalize")
      (reset! (:streams s) [])
      (buf/buffer-send-sequence buf (range 2500))
      (is (= [0 0 0] (mapv first @(:streams s))))
      (is (= [1024 1024 452]
             (mapv #(nth (first (second %)) 3) @(:streams s)))
          "1024-frame chunks"))))

(deftest live-sequences
  (with-server
   (fn [s]
     (testing "alloc-sequence + to-list round-trip"
       (let [vs (mapv #(/ % 100.0) (range 64))
             b (buf/buffer-alloc-sequence vs :server s)]
         (is (= 64 (:frames b)))
         (let [back (buf/buffer-to-list b)]
           (is (= 64 (count back)))
           (is (every? true? (map #(< (Math/abs (- %1 (float %2))) 1e-4) vs back))))
         (testing "send-sequence + get-to-list agree"
           (buf/buffer-send-sequence b (mapv #(* % 2.0) vs))
           (let [back (buf/buffer-get-to-list b)]
             (is (every? true? (map #(< (Math/abs (- %1 (float %2))) 1e-4)
                                    (mapv #(* % 2.0) vs) back)))))
         (testing "fill sine reads back a sine"
           (buf/buffer-fill b :sine [1.0] :server s :wavetable false)
           (let [back (buf/buffer-get-to-list b :end 32)]
             (is (< (Math/abs (first back)) 0.05) "starts near zero")
             (is (< 0.9 (apply max (map #(Math/abs (double %)) back))) "peaks near 1")))
         (testing "set-sr message shape (NRT; scsynth 3.13 lacks /b_setSampleRate)"
           (let [nrt (server/make-nrt-server)
                 nb (assoc b :server nrt)]
             (buf/buffer-set-sr nb 22050)
             (is (= [[0 [["/b_setSampleRate" (float (:bufnum b)) 22050.0]]]
                     [0 [["/b_query" (:bufnum b)]]]]
                    @(:streams nrt)))))
         (buf/buffer-free b)))
     (testing "read-as-wavetable doubles next power-of-two"
       (let [src (buf/buffer-alloc-sequence (vec (repeat 100 0.25)) :server s)
             wav (str "/tmp/wt-" (System/currentTimeMillis) ".wav")]
         (try
           (buf/buffer-write src wav)
           (let [w (buf/buffer-read-as-wavetable wav :server s)]
             (is (= 256 (:frames w)) "100 frames -> pow2 128 -> 256-frame table")
             (buf/buffer-free w))
           (finally
             (buf/buffer-free src)
             (.delete (io/file wav)))))))))
