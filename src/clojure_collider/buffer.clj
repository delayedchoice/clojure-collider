(ns clojure-collider.buffer
  "Server sample buffers. Mirrors buffer.lisp: alloc/read/write/cue/
   close/free/zero/normalize/copy/get/set, consecutive allocation,
   set-sr, send/load sequences, fill (/b_gen sine+cheby), wavetable
   helpers, list/array download, read-as-wavetable.
   buffer-copy fixes an upstream literal bug (\"\\b_gen\"
   backspace escape -> \"/b_gen\")."
  (:require [clojure.string :as str]
            [clojure.java.io :as io]
            [clojure-collider.osc.encode :as enc]
            [clojure-collider.server.server :as server])
  (:import [java.util.concurrent.locks ReentrantLock]))
(defrecord Buffer [bufnum server frames chanls sr path])

(defmethod print-method Buffer [b ^java.io.Writer w]
  (.write w (str "#<buffer " (:bufnum b) " " (:frames b) "x" (:chanls b) ">")))

(defmacro ^:private with-server-lock [server & body]
  `(let [l# ^ReentrantLock (:lock ~server)]
     (.lock l#)
     (try ~@body (finally (.unlock l#)))))

(defn- first-nil [v]
  (first (keep-indexed (fn [i x] (when (nil? x) i)) v)))

(defn get-next-buffer [server & [bufnum]]
  (with-server-lock server
    (let [bufs @(:buffers server)
          n (or bufnum (first-nil bufs))]
      (when (or (nil? n) (< n 0) (>= n (count bufs)))
        (throw (ex-info "no free buffer available" {:bufnum bufnum})))
      (let [b (map->Buffer {:bufnum n :server server :frames nil :chanls nil
                            :sr nil :path nil})]
        (reset! (:buffers server) (assoc bufs n b))
        b))))

(defn- find-consecutive-buffers [bufs num-bufs seed]
  (loop [start (if (nil? seed) (first-nil bufs) (.indexOf ^java.util.List bufs seed))]
    (when (or (nil? start) (>= start (count bufs)))
      (throw (ex-info "unable to find consecutive buffers" {:n num-bufs})))
    (let [end (+ start num-bufs)]
      (cond (> end (count bufs))
            (throw (ex-info "unable to find consecutive buffers" {:n num-bufs}))
            (every? nil? (subvec bufs start end)) start
            :else (let [next-free (first-nil (subvec bufs (inc start)))]
                    (recur (when next-free (+ (inc start) next-free))))))))

(defn get-consecutive-buffers [server num-bufs & [bufnum]]
  (with-server-lock server
    (let [bufs @(:buffers server)
          start (find-consecutive-buffers bufs num-bufs bufnum)]
      (reset! (:buffers server)
              (reduce (fn [v i]
                        (assoc v i (map->Buffer {:bufnum i :server server :frames nil
                                                :chanls nil :sr nil :path nil})))
                      bufs (range start (+ start num-bufs))))
      (mapv #(nth @(:buffers server) %) (range start (+ start num-bufs))))))

(defn- query-blob [bufnum]
  ;; Raw message bytes only: sc-encode-data blob-wraps byte[] args itself,
  ;; so pre-wrapping here would double-blob (scsynth then drops the completion).
  (apply enc/encode-message "/b_query" [(int bufnum)]))
(defn- with-sync-or-call [server buffer reply-path complete-handler thunk]
  ;; Sync path returns the CURRENT vector entry: /b_info mutates the
  ;; registered copy (records are values; the caller's copy stays stale).
  (if complete-handler
    (do (server/await-buffer-reply
         server [reply-path (int (:bufnum buffer))]
         (fn [buf] (complete-handler buf)))
        (thunk)
        buffer)
    (do (thunk)
        (server/sync server)
        (or (nth @(:buffers server) (int (:bufnum buffer)) nil) buffer))))

(defn buffer-alloc
  [frames & {:keys [chanls sr bufnum server complete-handler]
             :or {chanls 1 server nil}}]
  (let [server (or server server/*s*)]
    (when (nil? server) (throw (ex-info "buffer-alloc needs a server" {})))
    (let [buf (get-next-buffer server bufnum)]
      (with-sync-or-call
       server buf "/b_alloc" complete-handler
       (fn []
         (server/send-message server "/b_alloc" (int (:bufnum buf))
                              (long (float frames)) (long (float chanls))
                              (query-blob (:bufnum buf))
                              (if sr (float sr) 0.0)))))))

(defn buffer-alloc-consecutive
  [num-bufs frames & {:keys [chanls sr bufnum server complete-handler]
                      :or {chanls 1}}]
  (let [server (or server server/*s*)
        bufs (get-consecutive-buffers server num-bufs bufnum)]
    (doseq [b bufs]
      (with-sync-or-call
       server b "/b_alloc" complete-handler
       (fn []
         (server/send-message server "/b_alloc" (int (:bufnum b))
                              (long (float frames)) (long (float chanls))
                              (query-blob (:bufnum b))
                              (if sr (float sr) 0.0)))))
    (mapv #(nth @(:buffers server) (int (:bufnum %)) %) bufs)))

(defn buffer-read
  [path & {:keys [bufnum server] :or {server nil}}]
  (let [server (or server server/*s*)
        buf (get-next-buffer server bufnum)]
    (server/send-message server "/b_allocRead" (int (:bufnum buf)) (str path)
                         (int 0) (int -1) (query-blob (:bufnum buf)))
    (server/sync server)
    (or (nth @(:buffers server) (int (:bufnum buf)) nil) buf)))

(defn buffer-read-channel
  [path channels & {:keys [bufnum server]}]
  (let [server (or server server/*s*)
        buf (get-next-buffer server bufnum)
        chs (if (sequential? channels) channels [channels])]
    (apply server/send-message server "/b_allocReadChannel" (int (:bufnum buf))
           (str path) (int 0) (int -1)
           (concat (map int chs) [(query-blob (:bufnum buf))]))
    (server/sync server)
    (or (nth @(:buffers server) (int (:bufnum buf)) nil) buf)))

(defn buffer-read-no-update
  [path & {:keys [bufnum server complete-handler]}]
  (let [server (or server server/*s*)
        buf (get-next-buffer server bufnum)]
    (with-sync-or-call
     server buf "/b_allocRead" complete-handler
     (fn []
       (server/send-message server "/b_allocRead" (int (:bufnum buf)) (str path)
                            (int 0) (int -1))))))

(defn buffer-cue-soundfile
  [path & {:keys [start-frame chanls frames bufnum server]
           :or {start-frame 0 chanls 2 frames 32768 server nil}}]
  (let [server (or server server/*s*)
        buf (buffer-alloc frames :chanls chanls :bufnum bufnum :server server)]
    (server/send-message server "/b_read" (int (:bufnum buf)) (str path)
                         (long start-frame) (long frames) (int 0) (int 1)
                         (query-blob (:bufnum buf)))
    (server/sync server)
    (or (nth @(:buffers server) (int (:bufnum buf)) nil) buf)))

(def ^:private write-formats {:int16 "int16" :int24 "int24" :float "float" :double "double"})

(defn buffer-write
  [buffer path & {:keys [header format frames start-frame leave-open server complete-handler]
                  :or {frames -1 start-frame 0 leave-open false}}]
  (let [server (or server (:server buffer))
        hdr (or header (last (str/split (str path) #"\.")))
        fmt (get write-formats format "float")]
    (with-sync-or-call
     server buffer "/b_write" complete-handler
     (fn []
       (server/send-message server "/b_write" (int (:bufnum buffer)) (str path)
                            (str hdr) fmt (long frames) (long start-frame)
                            (if leave-open 1 0))))))

(defn buffer-close [buffer & {:keys [server complete-handler]}]
  (let [server (or server (:server buffer))]
    (with-sync-or-call
     server buffer "/b_close" complete-handler
     (fn [] (server/send-message server "/b_close" (float (:bufnum buffer)) (int 0))))))

(defn buffer-free [bufnum-or-buffer & [server]]
  (let [server (or server
                   (when (instance? Buffer bufnum-or-buffer) (:server bufnum-or-buffer))
                   server/*s*)
        n (long (if (instance? Buffer bufnum-or-buffer)
                  (:bufnum bufnum-or-buffer) bufnum-or-buffer))
        freed (with-server-lock server
                (let [b (nth @(:buffers server) n nil)]
                  (when (nil? b)
                    (throw (ex-info "bufnum already free" {:bufnum n})))
                  (reset! (:buffers server) (assoc @(:buffers server) n nil))
                  b))]
    (server/send-message server "/b_free" (int n))
    (server/sync server)
    freed))

(defn buffer-zero [buffer & {:keys [server complete-handler]}]
  (let [server (or server (:server buffer))]
    (with-sync-or-call
     server buffer "/b_zero" complete-handler
     (fn [] (server/send-message server "/b_zero" (int (:bufnum buffer)) (int 0))))))

(defn buffer-normalize [buffer & {:keys [new-max wavetable? server complete-handler]
                                  :or {new-max 1.0}}]
  (let [server (or server (:server buffer))]
    (with-sync-or-call
     server buffer "/b_gen" complete-handler
     (fn [] (server/send-message server "/b_gen" (int (:bufnum buffer))
                                 (if wavetable? "wnormalize" "normalize")
                                 (float new-max))))))

(defn buffer-copy [dst src & {:keys [start-dst start-src nframes]
                              :or {start-dst 0 start-src 0 nframes -1}}]
  (server/send-message server/*s* "/b_gen" (int (:bufnum dst)) "copy"
                       (long start-dst) (int (:bufnum src)) (long start-src) (long nframes))
  (server/sync server/*s*)
  nil)

(defn buffer-set [buffer index value]
  (server/send-message (:server buffer) "/b_set"
                       (int (:bufnum buffer)) (long index) value)
  (server/sync (:server buffer))
  buffer)

(defn buffer-setn [buffer & pairs]
  (when (odd? (count pairs))
    (throw (ex-info "buffer-setn needs index/value pairs" {})))
  (let [args (mapcat (fn [[idx v]]
                       (let [vs (if (sequential? v) v [v])]
                         (into [(long idx) (count vs)] vs)))
                     (partition 2 pairs))]
    (apply server/send-message (:server buffer) "/b_setn" (int (:bufnum buffer)) args)
    (server/sync (:server buffer))
    buffer))

(defn buffer-get [buffer index & [action]]
  (let [server (:server buffer)
        n (int (:bufnum buffer))
        i (int index)]
    (if action
      (do (server/await-buffer-reply server ["/b_set" n i] (fn [v] (action v)))
          (server/send-message server "/b_get" n i)
          buffer)
      (let [p (promise)]
        (server/await-buffer-reply server ["/b_set" n i] (fn [v] (deliver p v)))
        (server/send-message server "/b_get" n i)
        (server/sync server)
        (let [v (deref p 10000 :timeout)]
          (when (= :timeout v)
            (throw (ex-info "buffer-get timed out" {:bufnum n :index i})))
          v)))))

(defn buffer-getn [buffer start end & [action]]
  (let [server (:server buffer)
        n (int (:bufnum buffer))
        s (int start)
        f (int (- end start))]
    (when (or (> end (* (:chanls buffer) (:frames buffer))) (<= end start) (> f 400))
      (throw (ex-info "buffer-getn range invalid" {:start start :end end})))
    (if action
      (do (server/await-buffer-reply server ["/b_setn" n s f] (fn [vs] (action vs)))
          (server/send-message server "/b_getn" n s f)
          buffer)
      (let [p (promise)]
        (server/await-buffer-reply server ["/b_setn" n s f] (fn [vs] (deliver p vs)))
        (server/send-message server "/b_getn" n s f)
        (server/sync server)
        (let [vs (deref p 10000 :timeout)]
          (when (= :timeout vs)
            (throw (ex-info "buffer-getn timed out" {:bufnum n :start s})))
          vs)))))

(defn buffer-dur [buffer]
  (/ (double (:frames buffer)) (double (:sr buffer))))

;; -- sequences / fill / wavetable (buffer.lisp ll.367-500) --------------------------

(defn- buffer-server [buffer] (or (:server buffer) server/*s*))

(defn buffer-send-sequence
  "Upload SEQUENCE via chunked /b_setn (1024/frame msgs, works remote).
   Warns when the sequence overruns the buffer (upstream l.367)."
  [buffer sequence & {:keys [start-frame] :or {start-frame 0}}]
  (let [server (buffer-server buffer)
        vs (vec sequence)]
    (when (> (+ start-frame (count vs)) (:frames buffer))
      (binding [*out* *err*]
        (println "WARNING: sequence larger than available number of frames")))
    (doseq [[i chunk] (map-indexed vector (partition-all 1024 vs))]
      (apply server/send-message server "/b_setn" (int (:bufnum buffer))
             (int (+ start-frame (* i 1024))) (int (count chunk)) chunk))
    (server/sync server)
    buffer))

(defn write-mono-fl32-wav
  "Write SEQUENCE as mono 32-bit-float WAV (little-endian RIFF).
   Mirrors write-mono-fl32-wav (util.lisp l.108). Returns PATH."
  [path sr sequence]
  (let [vs (vec sequence)
        n (count vs)
        bb (doto (java.nio.ByteBuffer/allocate (+ 44 (* 4 n)))
             (.order java.nio.ByteOrder/LITTLE_ENDIAN))]
    (.put bb (.getBytes "RIFF" "ASCII"))
    (.putInt bb (+ 36 (* 4 n)))
    (.put bb (.getBytes "WAVEfmt " "ASCII"))
    (.putInt bb 16)
    (.putShort bb (short 3)) (.putShort bb (short 1))
    (.putInt bb (int sr))
    (.putInt bb (int (* 4 sr)))
    (.putShort bb (short 4)) (.putShort bb (short 32))
    (.put bb (.getBytes "data" "ASCII"))
    (.putInt bb (* 4 n))
    (doseq [v vs] (.putFloat bb (float v)))
    (with-open [out (io/output-stream path)]
      (.write out (.array bb)))
    path))

(defn buffer-load-sequence
  "Upload SEQUENCE via temp WAV + /b_read (local servers only, fastest).
   Mirrors buffer-load-sequence (upstream l.386)."
  [buffer sequence & {:keys [start-frame] :or {start-frame 0}}]
  (let [server (buffer-server buffer)]
    (assert (server/is-local? server) "buffer-load-sequence needs a local server")
    (let [vs (vec sequence)]
      (when (> (+ start-frame (count vs)) (:frames buffer))
        (binding [*out* *err*]
          (println "WARNING: sequence larger than available number of frames")))
      (let [tmp (java.io.File/createTempFile "cl-collider" ".wav")]
        (try
          (write-mono-fl32-wav (.getAbsolutePath tmp)
                               (long (Math/floor (double (or @(:sample-rate server) 44100))))
                               vs)
          (server/send-message server "/b_read" (int (:bufnum buffer))
                               (.getAbsolutePath tmp) (int start-frame)
                               (int -1) (int 0) (int 0))
          (server/sync server)
          buffer
          (finally (.delete tmp)))))))

(defn buffer-load
  "Upload SEQUENCE via load-sequence (local) or send-sequence (remote).
   An explicit :set-function overrides the dispatch (upstream l.403)."
  [buffer sequence & {:keys [start-frame set-function] :or {start-frame 0}}]
  ((or set-function
        (if (server/is-local? (buffer-server buffer))
          buffer-load-sequence buffer-send-sequence))
   buffer sequence :start-frame start-frame))

(defn buffer-alloc-sequence
  "Allocate a buffer holding SEQUENCE: temp WAV + read (local) or
   alloc + upload (remote). Mirrors buffer-alloc-sequence (upstream l.98)."
  [sequence & {:keys [server sr]}]
  (let [server (or server server/*s*)
        vs (vec sequence)]
    (if (server/is-local? server)
      (let [tmp (java.io.File/createTempFile "cl-collider" ".wav")]
        (try
          (write-mono-fl32-wav (.getAbsolutePath tmp)
                               (long (if sr sr (Math/floor (double (or @(:sample-rate server) 44100)))))
                               vs)
          (buffer-read (.getAbsolutePath tmp) :server server)
          (finally (.delete tmp))))
      (let [buf (buffer-alloc (count vs) :server server :sr sr)]
        (buffer-load buf vs)
        buf))))

(defn buffer-set-sr
  "Set BUFFER's server-side sample rate (no resampling), then /b_query to
   refresh the language-side copy. nil/0 sr takes the server rate.
   Mirrors buffer-set-sr (upstream l.89)."
  [buffer sr]
  (let [server (buffer-server buffer)]
    (server/send-message server "/b_setSampleRate"
                         (float (:bufnum buffer)) (float (or sr 0.0)))
    (server/send-message server "/b_query" (int (:bufnum buffer)))
    (server/sync server)
    (or (nth @(:buffers server) (int (:bufnum buffer)) nil) buffer)))

(defn buffer-fill
  "Fill BUFFER with sine partials (:sine) or chebyshev polynomials (:cheby)
   via /b_gen. Flags pack normalize/wavetable/clear-first like upstream
   (sine1 = bare partials, sine2 = +frequencies, sine3 = +phases).
   Mirrors buffer-fill (upstream l.419)."
  [buffer wave amplitudes & {:keys [frequencies phases server normalize wavetable clear-first]
                             :or {normalize true wavetable true clear-first true}}]
  (let [server (or server (buffer-server buffer))
        f (fn [xs] (mapv #(if (ratio? %) (float %) %) xs))
        amplitudes (f amplitudes)
        frequencies (when frequencies (f frequencies))
        phases (when phases (f phases))
        cmd (case wave
              :cheby "cheby"
              :sine (cond phases "sine3" frequencies "sine2" :else "sine1"))
        flags (+ (if normalize 1 0) (if wavetable 2 0) (if clear-first 4 0))
        args (if (= wave :sine)
               (cond (and frequencies phases)
                     (mapcat vector frequencies amplitudes phases)
                     phases
                     (mapcat vector (iterate inc 1) amplitudes phases)
                     frequencies
                     (mapcat vector frequencies amplitudes)
                     :else amplitudes)
               amplitudes)]
    (apply server/send-message server "/b_gen" (int (:bufnum buffer)) cmd (int flags) args)
    (server/sync server)
    buffer))

(defn- nth-wrap [i vs] (nth vs (mod i (count vs))))

(defn vector-in-wavetable-format
  "Convert signal values to SuperCollider wavetable format (2N frames).
   See Wavetable docs, advanced notes (upstream l.458)."
  [sequence]
  (let [vs (vec sequence)]
    (vec (mapcat (fn [i] (let [a0 (nth-wrap i vs) a1 (nth-wrap (inc i) vs)]
                            [(- (* 2 a0) a1) (- a1 a0)]))
                 (range (count vs))))))

(def as-wavetable vector-in-wavetable-format)

(defn as-wavetable-no-wrap
  "Wavetable without wrap, for Shaper: N values -> 2(N-1) frames
   (upstream l.470)."
  [sequence]
  (let [vs (vec sequence)]
    (vec (mapcat (fn [i] (let [cur (nth vs i) nxt (nth vs (inc i))]
                            [(- (* 2 cur) nxt) (- nxt cur)]))
                 (range (dec (count vs)))))))

(defn linear-resample
  "Resample SEQUENCE to LENGTH values via linear interpolation
   (util.lisp l.86)."
  [sequence length]
  (let [vs (vec sequence)
        old (count vs)]
    (if (= old length)
      sequence
      (let [factor (/ (dec old) (max (dec length) 1))]
        (mapv (fn [i] (let [n (* i factor)
                            fl (long (Math/floor n))
                            ce (min (dec old) (inc fl))
                            d (- n fl)]
                        (+ (* (nth vs fl) (- 1 d)) (* (nth vs ce) d))))
              (range length))))))

(defn buffer-get-to-list
  "Whole buffer (or START..END samples) via chunked /b_getn x400.
   Unbounded by packet size, unlike buffer-getn (upstream l.249)."
  [buffer & {:keys [start end] :or {start 0}}]
  (let [end (or end (* (:chanls buffer) (:frames buffer)))]
    (assert (and (integer? start) (<= 0 start)) "start must be a natural")
    (assert (<= end (* (:chanls buffer) (:frames buffer))) "end out of range")
    (vec (mapcat (fn [s] (buffer-getn buffer s (min end (+ s 400))))
                 (range start end 400)))))

(defn buffer-load-to-list
  "Whole buffer via /b_write float32 dump + raw read (local only, fastest).
   Mirrors buffer-load-to-list (upstream l.264)."
  [buffer & {:keys [start end] :or {start 0}}]
  (let [server (buffer-server buffer)
        end (or end (* (:chanls buffer) (:frames buffer)))]
    (assert (server/is-local? server) "buffer-load-to-list needs a local server")
    (let [tmp (java.io.File/createTempFile "cl-collider" ".raw")]
      (try
        (buffer-write buffer (.getAbsolutePath tmp) :format :float
                      :frames (- end start) :start-frame start)
        (let [bytes (java.nio.file.Files/readAllBytes (.toPath tmp))
              ;; scsynth writes headerless dumps native-endian (LE on x86_64),
              ;; unlike the big-endian wire protocol
              bb (doto (java.nio.ByteBuffer/wrap bytes)
                   (.order java.nio.ByteOrder/LITTLE_ENDIAN))]
          (vec (repeatedly (/ (alength ^bytes bytes) 4) #(.getFloat bb))))
        (finally (.delete tmp))))))

(defn buffer-to-list
  "Flat interlaced frames START..END (samples). Local servers dump via file,
   remote servers chunk via OSC; :get-function overrides (upstream l.286)."
  [buffer & {:keys [start end get-function] :or {start 0}}]
  ((or get-function
        (if (server/is-local? (buffer-server buffer))
          buffer-load-to-list buffer-get-to-list))
   buffer :start start :end end))

(defn buffer-to-array
  "Deinterlaced channels over START..END (frames). CHANNELS is a channel
   index (flat vec) or seq of indices (vec of vecs); defaults to all.
   Mirrors buffer-to-array (upstream l.298), quirk included: END counts
   frames but is passed straight to the sample-based get-function."
  [buffer & {:keys [start end channels get-function] :or {start 0}}]
  (let [end (or end (:frames buffer))
        buf-ch (:chanls buffer)
        chan-list (vec (if (sequential? channels) channels [channels]))
        chan-list (if (seq chan-list) chan-list (vec (range buf-ch)))
        num-frames (- end start)
        frames ((or get-function
                    (if (server/is-local? (buffer-server buffer))
                      buffer-load-to-list buffer-get-to-list))
                buffer :start start :end end)
        empty (mapv (fn [_] (vec (repeat num-frames 0.0))) chan-list)
        grid (first
              (reduce (fn [[acc] [idx v]]
                        (let [chan (nth chan-list (mod idx buf-ch) nil)
                              fr (quot idx buf-ch)]
                          (if (and chan (< fr num-frames))
                            [(assoc-in acc [(.indexOf ^java.util.List chan-list chan) fr] v)]
                            [acc])))
                      [empty] (map-indexed vector frames)))]
    ;; upstream: channel list (incl. default all) -> 2-D, scalar -> 1-D
    (if (and (some? channels) (not (sequential? channels)))
      (first grid) grid)))

(defn buffer-get-to-array
  "buffer-to-array via chunked OSC (non-local safe). Upstream l.332."
  [buffer & {:keys [start end channels] :or {start 0}}]
  (buffer-to-array buffer :start start :end end :channels channels
                   :get-function buffer-get-to-list))

(defn buffer-load-to-array
  "buffer-to-array via file dump (local only, fastest). Upstream l.340."
  [buffer & {:keys [start end channels] :or {start 0}}]
  (buffer-to-array buffer :start start :end end :channels channels
                   :get-function buffer-load-to-list))

(defn buffer-read-as-wavetable
  "Read PATH as a wavetable: resample channel 0 to 2^N, convert, upload
   to a 2x buffer. Mirrors buffer-read-as-wavetable (upstream l.485)."
  [path & {:keys [bufnum server]}]
  (let [server (or server server/*s*)
        tmp (buffer-read path :server server)]
    (server/sync server)
    (let [full-path (:path tmp)
          file-frames (:frames tmp)
          pow2 (mapv #(bit-shift-left 1 %) (range 1 17))
          num-frames (nth pow2 (or (first (keep-indexed
                                           (fn [i p] (when (>= p file-frames) i))
                                           pow2))
                                   15))
          frames (try (buffer-to-array tmp :channels 0)
                      (finally (buffer-free tmp)))
          buf (buffer-alloc (* 2 num-frames) :bufnum bufnum :server server)]
      (buffer-load buf (as-wavetable (linear-resample (vec frames) num-frames)))
      (assoc buf :path full-path))))
