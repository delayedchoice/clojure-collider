(ns clojure-collider.server.silent
  "Headless silence detection. Mac never silenced unless user opts in.
   Both fns take the server options map (or nil) first: an explicit
   :auto-silence value wins, nil falls back to auto-detect (true only
   on headless Linux, never on macOS)."
  (:require [clojure.string :as str]))

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

(defn auto-silence?
  "True when boot should be silent. Explicit (:auto-silence opts) wins;
   nil opts (or nil value) auto-detects: headless Linux only."
  [opts]
  (if (some? (:auto-silence opts))
    (:auto-silence opts)
    (and (not (macos?)) (headless-linux?))))

(defn silent-boot-args
  "Boot override map for silent servers, or nil. Takes the options map (or nil)."
  [opts]
  (when (auto-silence? opts)
    ;; jack dummy is started externally; scsynth just needs -H jack + low verbosity
    {:device "jack" :extra-args ["-V" "-1"] :mute true}))
