(ns clojure-collider.server.options
  "ServerOptions record + scsynth CLI builder. Mirrors server-options.lisp."
  (:require [clojure.string :as str])
  (:import [java.io File]))

(def default-plugin-paths
  "Existing plugin dirs, Linux-first like upstream."
  (filterv #(.isDirectory (File. ^String %))
           ["/usr/local/lib/SuperCollider/plugins/"
            "/usr/lib/SuperCollider/plugins/"
            "/usr/local/share/SuperCollider/Extensions/"
            "/usr/share/SuperCollider/Extensions/"]))

(def defaults
  {:num-control-bus 16384
   :num-audio-bus 1024
   :num-input-bus 8
   :num-output-bus 8
   :block-size 64
   :hardware-buffer-size nil
   :hardware-samplerate nil
   :num-sample-buffers 1024
   :max-num-nodes 1024
   :max-num-synthdefs 1024
   :realtime-mem-size 8192
   :num-wire-buffers 64
   :num-random-seeds 64
   :load-synthdefs-p 1
   :publish-to-rendezvous-p 1
   :max-logins 64
   :verbosity 0
   :ugen-plugins-path default-plugin-paths
   :device nil
   :input-stream-enabled nil
   :output-stream-enabled nil})

(defrecord ServerOptions [num-control-bus num-audio-bus num-input-bus num-output-bus
                          block-size hardware-buffer-size hardware-samplerate
                          num-sample-buffers max-num-nodes max-num-synthdefs
                          realtime-mem-size num-wire-buffers num-random-seeds
                          load-synthdefs-p publish-to-rendezvous-p max-logins verbosity
                          ugen-plugins-path device input-stream-enabled output-stream-enabled])

(defn make-server-options
  "Defaults merged with `overrides` map."
  ([] (map->ServerOptions defaults))
  ([overrides] (map->ServerOptions (merge defaults overrides))))

(def ^:private cli-table
  "Upstream build-server-options order: [flag field]."
  [["-c" :num-control-bus]
   ["-a" :num-audio-bus]
   ["-i" :num-input-bus]
   ["-o" :num-output-bus]
   ["-z" :block-size]
   ["-Z" :hardware-buffer-size]
   ["-S" :hardware-samplerate]
   ["-b" :num-sample-buffers]
   ["-n" :max-num-nodes]
   ["-d" :max-num-synthdefs]
   ["-m" :realtime-mem-size]
   ["-w" :num-wire-buffers]
   ["-r" :num-random-seeds]
   ["-D" :load-synthdefs-p]
   ["-R" :publish-to-rendezvous-p]
   ["-l" :max-logins]
   ["-V" :verbosity]
   ["-H" :device]
   ["-I" :input-stream-enabled]
   ["-O" :output-stream-enabled]])

(defn- words [v]
  (mapv str (if (sequential? v) v [v])))

(defn build-server-options
  "Pure CLI arg vector. Nil/false (and hardware-buffer 0, upstream rule)
   options are omitted; plugin paths join into one -U entry."
  [opts]
  (let [m (into {} opts)
        args (into []
                   (mapcat (fn [[flag field]]
                             (let [v (get m field)]
                               (when (and v (not (and (= field :hardware-buffer-size)
                                                      (== 0 v))))
                                 (into [flag] (words v))))))
                   cli-table)
        paths (seq (:ugen-plugins-path m))]
    (cond-> args
      paths (conj "-U" (str/join ":" (map str paths))))))
