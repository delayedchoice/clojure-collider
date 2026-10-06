(ns clojure-collider.osc.encode
  "Hand-rolled OSC 1.0 encode/decode for SuperCollider.
   Mirrors ../cl-collider/osc/osc.lisp sc-encode-* but via java.nio.ByteBuffer
   (BIG_ENDIAN) instead of cl-osc/ieee-floats/usocket helpers.
   Typetags: ,ifsb[]  Timetags: NTP 64-bit, :now -> 0 0 0 0 0 0 0 1"
  (:import [java.nio ByteBuffer ByteOrder]))

(def immediate-timetag-bytes (byte-array [0 0 0 0 0 0 0 1]))
(def bundle-header-bytes (byte-array [35 98 117 110 100 108 101 0])) ; "#bundle\0"

;; --- low level ---

(defn pad-length [len]
  (let [m (mod len 4)]
    (if (zero? m) 0 (- 4 m))))

(defn padded-length [len]
  (+ len (pad-length len)))

(defn- bb [cap]
  (doto (ByteBuffer/allocate cap)
    (.order ByteOrder/BIG_ENDIAN)))

(defn encode-int32 [i]
  (let [b (bb 4)] (.putInt b (int i)) (.array b)))

(defn encode-int64 [l]
  (let [b (bb 8)] (.putLong b (long l)) (.array b)))

(defn encode-float32 [f]
  (let [b (bb 4)] (.putFloat b (float f)) (.array b)))

(defn encode-float64 [d]
  (let [b (bb 8)] (.putDouble b (double d)) (.array b)))

(defn encode-int16 [i]
  ;; matches CL (subseq (encode-int32 i) 2) -> low 2 bytes big-endian
  (let [full (encode-int32 i)]
    (byte-array [(aget full 2) (aget full 3)])))

(defn osc-string-bytes
  "OSC string: utf8 bytes + \\0 + pad to 4 bytes. Matches osc::encode-string / string-padding."
  [^String s]
  (let [raw (.getBytes s "UTF-8")
        with-null (byte-array (inc (alength raw)))]
    (System/arraycopy raw 0 with-null 0 (alength raw))
    (aset with-null (alength raw) (byte 0))
    (let [padded (padded-length (alength with-null))
          out (byte-array padded)]
      (System/arraycopy with-null 0 out 0 (alength with-null))
      out)))

(defn sc-encode-blob
  "Blob: int32 length + bytes + pad to 4. Takes byte[] or seq of bytes."
  [blob]
  (let [b (if (bytes? blob) blob (byte-array blob))
        len (alength b)
        pad (pad-length len)
        out-len (+ 4 len pad)
        bb (bb out-len)]
    (.putInt bb (int len))
    (.put bb b)
    (dotimes [_ pad] (.put bb (byte 0)))
    (.array bb)))

(defn sc-encode-address
  "Address is string -> osc-string, integer -> int32 (per CL method)"
  [addr]
  (if (integer? addr) (encode-int32 addr) (osc-string-bytes (str addr))))

;; --- type tags ---

(defn- type-char [x]
  (cond
    (integer? x) \i
    (instance? Double x) \d
    (float? x) \f
    (string? x) \s
    (bytes? x) \b
    (instance? (Class/forName "[B") x) \b
    :else \b))

(defn sc-make-type-tags
  "Recursively produce tag chars, wrapping sequential collections other than string/bytes in [].
   Mirrors CL sc-make-type-tags : list args produce [ ] brackets."
  [datas]
  (mapcat (fn [x]
            (if (and (sequential? x) (not (string? x)) (not (bytes? x)))
              (concat [\[] (sc-make-type-tags x) [\]])
              [(type-char x)]))
          datas))

(defn sc-encode-typetags
  [data]
  (let [tags (sc-make-type-tags data)
        s (apply str "," tags)
        raw (.getBytes s "UTF-8")
        with-null-len (inc (count raw)) ; add \0
        padded (padded-length with-null-len)
        out (byte-array padded)]
    (System/arraycopy raw 0 out 0 (count raw))
    out))

(defn- flatten-osc [data]
  ;; alexandria:flatten semantics: recursively flatten sequential collections
  (mapcat (fn [x]
            (if (and (sequential? x) (not (string? x)) (not (bytes? x)))
              (flatten-osc x)
              [x]))
          data))

(defn sc-encode-data
  [data]
  (let [flat (flatten-osc data)
        parts (map (fn [x]
                     (cond
                       (integer? x) (encode-int32 x)
                       (instance? Double x) (encode-float64 (double x))
                       (float? x) (encode-float32 (float x))
                       (string? x) (osc-string-bytes x)
                       :else (sc-encode-blob (if (bytes? x) x (byte-array (map byte x))))))
                   flat)
        total (reduce + 0 (map alength parts))
        out (byte-array total)]
    (loop [pos 0 ps parts]
      (if (empty? ps)
        out
        (let [p (first ps)]
          (System/arraycopy p 0 out pos (alength p))
          (recur (+ pos (alength p)) (rest ps)))))))

;; --- high level ---

(defn cat-bytes
  "Concatenate byte arrays (CL cat helper)."
  [& arrs]
  (let [total (reduce + 0 (map alength arrs))
        out (byte-array total)]
    (loop [pos 0 xs arrs]
      (if (empty? xs)
        out
        (let [a (first xs)]
          (System/arraycopy a 0 out pos (alength a))
          (recur (+ pos (alength a)) (rest xs)))))))

(defn encode-message
  "Mirrors CL (encode-message address &rest data). Data is a seq of args (ints/floats/strings/blobs or nested seq for arrays)."
  [address & data]
  (cat-bytes (sc-encode-address address)
             (sc-encode-typetags data)
             (sc-encode-data data)))

(defn sc-encode-timetag [timetag]
  (cond
    (= timetag :now) (aclone immediate-timetag-bytes)
    (integer? timetag) (encode-int64 timetag)
    (bytes? timetag) timetag
    :else (throw (ex-info "timetag must be :now or 64-bit int" {:got timetag}))))

(defn encode-bundle
  "Mirrors CL (encode-bundle data &optional timetag).
   data is either a single message vector [addr & args] or a seq of such vectors.
   timetag is :now or int64 (default :now)."
  ([data] (encode-bundle data :now))
  ([data timetag]
   (let [tt (sc-encode-timetag (or timetag :now))
         encode-elt (fn [elt]
                      (let [msg (apply encode-message elt)
                            len (encode-int32 (alength msg))]
                        (cat-bytes len msg)))
         body (if (and (sequential? data) (sequential? (first data)) (string? (ffirst data)))
                ;; list of messages? heuristic: data = ([addr ...] [addr ...])
                ;; if first element is itself a message (first is string addr), treat as bundle of many
                ;; else treat as single message
                ;; CL checks (listp (car data)) -> if true then multiple, else single
                (if (sequential? (first data))
                  (apply cat-bytes (map encode-elt data))
                  (encode-elt data))
                (encode-elt data))]
     (cat-bytes (aclone bundle-header-bytes) tt body))))

;; --- decode helpers (minimal for /status.reply etc) ---

(defn decode-int32 [^bytes b offset]
  (let [bb (ByteBuffer/wrap b offset 4)]
    (.order bb ByteOrder/BIG_ENDIAN)
    (.getInt bb)))

(defn decode-float32 [^bytes b offset]
  (let [bb (ByteBuffer/wrap b offset 4)]
    (.order bb ByteOrder/BIG_ENDIAN)
    (.getFloat bb)))

(defn decode-float64 [^bytes b offset]
  (let [bb (ByteBuffer/wrap b offset 8)]
    (.order bb ByteOrder/BIG_ENDIAN)
    (.getDouble bb)))

(defn decode-osc-string
  "Returns [string next-offset] reading null-terminated padded string from bytes at offset."
  [^bytes b offset]
  (let [len (alength b)
        end (loop [i offset] (if (or (>= i len) (zero? (aget b i))) i (recur (inc i))))
        s (String. b offset (- end offset) "UTF-8")
        next (+ offset (padded-length (inc (- end offset))))]
    [s next]))

(defn decode-message
  "Decode single OSC message bytes -> [address typetags-vals]. Address is string, vals are vec."
  [^bytes b]
  (let [[addr pos] (decode-osc-string b 0)
        [tag-str pos2] (decode-osc-string b pos)
        tags (subs tag-str 1) ; drop leading ,
        vals (loop [i 0 offset pos2 acc []]
               (if (>= i (count tags))
                 acc
                 (let [t (nth tags i)]
                   (case t
                     \i (let [v (decode-int32 b offset)] (recur (inc i) (+ offset 4) (conj acc v)))
                     \f (let [v (decode-float32 b offset)] (recur (inc i) (+ offset 4) (conj acc v)))
                     \d (let [v (decode-float64 b offset)] (recur (inc i) (+ offset 8) (conj acc v)))
                     \s (let [[s nxt] (decode-osc-string b offset)] (recur (inc i) nxt (conj acc s)))
                     \b (let [sz (decode-int32 b offset)
                              start (+ offset 4)
                              blob (byte-array sz)]
                          (System/arraycopy b start blob 0 sz)
                          (recur (inc i) (+ offset 4 (padded-length sz)) (conj acc blob)))
                     ;; brackets and unknown: skip
                     (recur (inc i) offset acc)))))]
    [addr tags vals]))

(defn bundle? [^bytes b]
  (and (>= (alength b) 8) (= (aget b 0) (byte 35)))) ; '#'

(defn decode-bundle
  "Decode bundle or single message -> {:timetag bytes :messages [[addr vals] ...]}"
  [^bytes b]
  (if (bundle? b)
    (let [tt (byte-array 8)
          _ (System/arraycopy b 8 tt 0 8)
          msgs (loop [offset 16 acc []]
                 (if (>= offset (alength b)) acc
                     (let [sz (decode-int32 b offset)
                           start (+ offset 4)
                           msg-bytes (byte-array sz)]
                       (System/arraycopy b start msg-bytes 0 sz)
                       (let [[addr _tags vals] (decode-message msg-bytes)]
                         (recur (+ start sz) (conj acc [addr vals]))))))]
      {:timetag tt :messages msgs})
    (let [[addr _tags vals] (decode-message b)]
      {:timetag immediate-timetag-bytes :messages [[addr vals]]})))
