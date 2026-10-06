(ns clojure-collider.server.silent
  "Headless silence detection. Mac never silenced unless user opts in."
  (:require [clojure.string :as str]))

(def ^:dynamic *auto-silence?*
  "When true, force silent boot (jack dummy + mute).
   When nil (default), auto-detect: true only on headless Linux (no soundcards).
   On macOS always false unless explicitly set true."
  nil)

(defn macos? []
  (str/starts-with? (str/lower-case (System/getProperty "os.name")) "mac"))

(defn linux? []
  (str/starts-with? (str/lower-case (System/getProperty "os.name")) "linux"))

(defn headless-linux? []
  (and (linux?)
       (try
         (let [proc (.exec (Runtime/getRuntime) (into-array String ["aplay" "-l"]))
               out (slurp (.getInputStream proc))]
           (.waitFor proc)
           (or (str/includes? out "no soundcards found")
               (str/blank? out)))
         (catch Exception _ false))))

(defn auto-silence? []
  (if (some? *auto-silence?*)
    *auto-silence?*
    (and (not (macos?)) (headless-linux?))))

(defn silent-boot-args []
  (when (auto-silence?)
    ;; jack dummy is started externally; scsynth just needs -H jack + low verbosity
    {:device "jack" :extra-args ["-V" "-1"] :mute true}))
