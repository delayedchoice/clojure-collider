(ns emit-ugens
  "One-shot transpiler: /tmp/ugen_ir.edn -> src/clojure_collider/ugens/*.clj.
   Only mechanical defugen variants are emitted; customs are reported.
   Usage: clojure -M scripts/emit_ugens.clj
   Writes new files directly; hand-file additions go to /tmp/gen-appendix-*.clj."
  (:require [clojure.string :as str]
            [clojure.edn :as edn]))

(def ir-file "/tmp/ugen_ir.edn")
(def src-dir "src/clojure_collider/ugens/")
(def hand-files #{"Osc.lisp" "Filter.lisp" "EnvGen.lisp" "InOut.lisp" "Line.lisp"})

(def mech-flavors #{"ugen" "pure-ugen" "multiout-ugen" "nooutput-ugen"
                    "poll-ugen" "width-first-ugen" "dyn-klang" "dyn-klank" "lf-gauss"
                    "pv-chain-ugen"})
(defn ported-fns []
  "Seed set: fns already hand-ported (only the 5 hand files count;
   generated files are fully rewritten each run)."
  (into #{}
         (mapcat (fn [f]
                   (map second (re-seq #"\(defn-? ([a-zA-Z0-9*!+_?<>=\-:.]+)"
                                       (slurp (str src-dir f ".clj")))))
                 ["osc" "filter" "env_gen" "in_out" "line"])))

(defn cl-fname [lisp-name rate]
  (str (str/lower-case lisp-name) ":" (name rate)))

(defn emit-default [d]
  (cond (or (number? d) (string? d) (keyword? d) (boolean? d) (nil? d)) (pr-str d)
        (symbol? d) (str d)
        (and (sequential? d) (= 'midicps (first d))) (str "(op/midicps " (pr-str (second d)) ")")
        :else nil))

(defn emit-input [i]
  (cond (re-matches #"-?\d+(\.\d+)?" i) i
        (re-matches #"[a-zA-Z*!+_?<>=\-][a-zA-Z0-9*!+_?<>=\-\[\]]*" i) i
        (= i "t") "true"
        :else (let [m-audio (re-matches #"\[as-audio-rate-input ([a-zA-Z0-9_\-]+)\]" i)
                    m-act (re-matches #"\[act ([a-zA-Z0-9_\-]+)\]" i)
                    m-if (re-matches #"\[if ([^ \[\]]+) ([^ \[\]]+) ([^ \[\]]+)\]" i)]
                (cond m-audio (str "(as-audio-rate-input " (second m-audio) ")")
                      m-act (str "(ugen/act " (second m-act) ")")
                      m-if (let [[_ a b c] m-if
                                 atom? #(or (re-matches #"-?\d+(\.\d+)?" %)
                                            (re-matches #"[a-zA-Z*!+_?<>=\-][a-zA-Z0-9*!+_?<>=\-\[\]]*" %))]
                             (when (every? atom? [a b c]) (str "(if " a " " b " " c ")")))
                      :else nil))))

(defn emit-check [c]
  (cond (= c :default) "no-check"
        (= c "fn:check-same-rate-as-first-input") "ugen/check-same-rate-as-first-input"
        (= c "fn:check-when-audio") "ugen/check-when-audio"
        (= c "fn:when-audio-check-first-input") "ugen/when-audio-check-first-input"
        (= c "fn:peak-check-fn") "ugen/peak-check-fn"
        (= c "fn:check-duty") "ugen/check-duty"
        :else (when (string? c)
                (let [m (re-matches #"lambda:\[check-n-inputs ugen (\d+)\]" c)]
                  (when m (str "(ugen/check-n-inputs " (second m) ")"))))))

(def rate-kw {:ar ":audio" :kr ":control" :ir ":scalar"})

(defn- input-triple
  "Raw IR input string -> {:bind sym :use expr :expand expr} or nil.
   bind is the ctor fn param; use is the ctor call arg; expand is the
   multinew arg (evaluated in defn scope)."
  [raw-i i]
  (cond (re-matches #"-?\d+(\.\d+)?" raw-i)
        {:bind (symbol (str "c" i)) :use raw-i :expand raw-i}
        (re-matches #"[a-zA-Z*!+_?<>=\-][a-zA-Z0-9*!+_?<>=\-\[\]]*" raw-i)
        {:bind (symbol raw-i) :use raw-i :expand raw-i}
        (= raw-i "t") {:bind (symbol (str "c" i)) :use "true" :expand "true"}
        :else (let [m-audio (re-matches #"\[as-audio-rate-input ([a-zA-Z0-9_\-]+)\]" raw-i)
                    m-act (re-matches #"\[act ([a-zA-Z0-9_\-]+)\]" raw-i)
                    m-if (re-matches #"\[if ([^ \[\]]+) ([^ \[\]]+) ([^ \[\]]+)\]" raw-i)
                    atom? #(or (re-matches #"-?\d+(\.\d+)?" %)
                               (re-matches #"[a-zA-Z*!+_?<>=\-][a-zA-Z0-9*!+_?<>=\-\[\]]*" %))]
                (cond m-audio {:bind (symbol (str "ain" i))
                               :use (str "ain" i)
                               :expand (str "(as-audio-rate-input " (second m-audio) ")")}
                      m-act {:bind (symbol (second m-act))
                             :use (str "(ugen/act " (second m-act) ")")
                             :expand (second m-act)}
                      (and m-if (let [[_ a b c] m-if] (every? atom? [a b c])))
                      (let [[_ a b c] m-if]
                        {:bind (symbol (str "iexpr" i)) :use (str "iexpr" i)
                         :expand (str "(if " a " " b " " c ")")})
                      :else nil))))

(defn emit-variant [d v]
  (let [raw (:raw v)
        madd? (:madd v)
        ;; classifier marks progn/sc::mul wrappers :custom; recover the
        ;; inner multinew flavor for the two mechanical shapes
        flavor (let [m (re-find #"^\[(?:(?:progn|sc::mul) \[multinew new|apply \(quote multinew\) new) \(quote ([a-z\-]+)\)" raw)
                     f0 (if (and (= ":custom" (:flavor v)) m) (second m) (:flavor v))
                     uw (when (string? f0) (second (re-matches #"\(quote ([a-z\-]+)\)" f0)))]
                 (or uw f0))
        check (emit-check (:check-fn d))
        sig (pr-str (:signal-range d))
        rate (get rate-kw (:rate v))
        triples (mapv input-triple
                      (if (= ":custom" (:flavor v))
                        (try (let [parsed (edn/read-string (str/replace raw "sc::" "sc-"))
                                   inner (first (filter #(and (sequential? %)
                                                              (= 'multinew (first %)))
                                                        (tree-seq sequential? seq parsed)))
                                   ;; tw-index: (apply 'multinew new 'ugen
                                   ;;   (cons in (cons normalize array)))
                                   applied (when (and (sequential? parsed)
                                                      (= 'apply (first parsed)))
                                             (nth parsed 4 nil))]
                               (if (and (sequential? applied) (= 'cons (first applied)))
                                 (loop [c applied acc []]
                                   (if (and (sequential? c) (= 'cons (first c)))
                                     (recur (nth c 2 nil) (conj acc (str (second c))))
                                     (conj acc (str c))))
                                 (mapv str (drop 3 inner))))
                             (catch Exception _ []))
                        (:inputs v))
                      (range))]
    (when (and check rate (mech-flavors flavor)
               (every? identity triples)
               (or (not (str/starts-with? raw "[progn"))
                   (boolean (re-find #"\[multinew new" raw)))
               (or (not (str/starts-with? raw "[sc::mul"))
                   (try (let [parsed (edn/read-string (str/replace raw "sc::" "sc-"))]
                          (boolean (re-matches #"[a-zA-Z0-9_.\-]+" (str (last parsed)))))
                     (catch Exception _ false))))
      (let [uses (mapv :use triples)
            binds (mapv :bind triples)
            ;; Clojure fns take max 20 params; wide ctors go variadic
            wide? (> (count binds) 18)
            ctor (if (= flavor "multiout-ugen")
                   (let [ch (first uses) rest-t (subvec triples 1)
                         rest-binds (mapv :bind rest-t)]
                     (if (re-matches #"-?\d+" ch)
                       (if (> (count rest-binds) 18)
                         {:fn-args '[_cls & args]
                          :call (str "(apply ugen/multiout-new \"" (:sc-name d) "\" " rate " " sig " " ch " args)")
                          :expand (mapv :expand rest-t)}
                         {:fn-args (vec (cons '_cls rest-binds))
                          :call (str "(ugen/multiout-new \"" (:sc-name d) "\" " rate " " sig " " ch
                                     (apply str (map #(str " " %) (mapv :use rest-t))) ")")
                          :expand (mapv :expand rest-t)})
                       (let [rest-names (mapv str (rest (:inputs v)))
                             ch-sym (first (:inputs v))
                             chbind (if (and (re-matches #"[a-zA-Z0-9_\-]+" ch-sym)
                                             (some #{ch-sym} rest-names))
                                      (symbol (str "ch-" ch-sym)) (:bind (first triples)))]
                         {:fn-args (vec (cons '_cls (cons chbind (mapv :bind (rest triples)))))
                          :call (str "(ugen/multiout-new \"" (:sc-name d) "\" " rate " " sig " "
                                     chbind " " (str/join " " (rest uses)) ")")
                          :expand (mapv :expand triples)})))
                   (if wide?
                     {:fn-args '[cls & args]
                      :call (str "(apply ugen/ugen-new \"" (:sc-name d) "\" " rate " cls " check " " sig " args)")
                      :expand (mapv :expand triples)}
                     {:fn-args (vec (cons 'cls binds))
                      :call (str "(ugen/ugen-new \"" (:sc-name d) "\" " rate " cls " check " " sig
                                 (apply str (map #(str " " %) uses)) ")")
                      :expand (mapv :expand triples)}))
            core (str "(ugen/multinew (fn " (:fn-args ctor) " " (:call ctor) ")\n"
                      "                           '" flavor
                      (apply str (map #(str " " %) (:expand ctor))) ")")
            madd-tail (get v :madd-tail 2)
            sc-extra (when (str/starts-with? raw "[sc::mul")
                       (try (let [parsed (edn/read-string (str/replace raw "sc::" "sc-"))
                                  extra (str (last parsed))]
                              (when (re-matches #"[a-zA-Z0-9_.\-]+" extra) extra))
                         (catch Exception _ nil)))
            wrapped (cond (and madd? (= 2 madd-tail)) (str "(op/madd " core "\n            mul add)")
                          (and madd? (= 1 madd-tail)) (str "(op/madd " core "\n            mul)")
                          sc-extra (str "(op/* " core " " sc-extra ")")
                          :else core)]
        (if (and (str/starts-with? raw "[progn [multinew")
                 (re-find #"\] ([\d.]+)\]$" raw))
          (let [n (second (re-find #"\] ([\d.]+)\]$" raw))]
            (str "   " core "\n   " n ")"))
          (str "   " wrapped ")"))))))

(defn emit-defn [d v ported]
  (let [fname (cl-fname (:lisp-name d) (:rate v))]
    (when-not (ported fname)
      (let [{:keys [req opt key]} (:args d)
            reqs (mapv str req)
            opts (mapv (comp str :name) opt)
            odefs (mapv (comp emit-default :default) opt)
            knames (mapv (comp str :name) key)
            kdefs (mapv (comp emit-default :default) key)]
        (when (and (every? identity odefs) (every? identity kdefs))
          (let [all (vec (concat reqs opts))
                ;; Clojure arities take max 20 params; beyond that the tail
                ;; becomes keys (documented in the docstring)
                overflow? (> (count all) 20)
                pos-all (if overflow? (subvec all 0 20) all)
                tail-names (if overflow? (subvec all 20) [])
                tail-defs (if overflow? (vec (take-last (count tail-names) odefs)) [])
                all-keys (vec (concat knames tail-names))
                all-kdefs (vec (concat kdefs tail-defs))
                full-args (if (or (seq knames) overflow?)
                            (str "[" (str/join " " pos-all) " & {:keys ["
                                 (str/join " " all-keys) "] :or {"
                                 (str/join " " (mapcat (fn [k df] [k df]) all-keys all-kdefs)) "}}]")
                            (str "[" (str/join " " all) "]"))
                body (emit-variant d v)]
            (when body
              {:fname fname
               :code (str "(defn " fname "\n"
                          "  \"Port of " (:file d) " (" (:lisp-name d) " \\\"" (:sc-name d) "\\\")."
                          (when overflow? " Params beyond 20 pass as keys.")
                          "\"\n"
                          (apply str
                                 (map (fn [n]
                                        (let [take-args (subvec pos-all 0 n)
                                              fill-pos (take (- (count pos-all) n)
                                                             (concat (drop (- n (count reqs)) odefs)
                                                                     (repeat "nil")))
                                              fill-keys (mapcat (fn [k df] [(str ":" k) df])
                                                                all-keys all-kdefs)]
                                          (str "  ([" (str/join " " take-args) "] (" fname " "
                                               (str/join " " (concat take-args fill-pos fill-keys))
                                               "))\n")))
                                      (range (count reqs) (if overflow? 21 (count all)))))
                          "  (" full-args "\n" body ")\n")})))))))
(def file-map
  "Upstream base name -> MAPPING.md snake_case target (ns uses dashes)."
  {"BEQSuite" "beq_suite" "BufIO" "buf_io" "Chaos" "chaos" "Compander" "compander"
   "Delays" "delays" "Demand" "demand" "DiskIO" "disk_io" "EnvGen" "env_gen"
   "FFT" "fft" "FFTUnpacking" "fft_unpacking" "Filter" "filter" "FreeVerb" "free_verb"
   "FSinOsc" "fsin_osc" "GVerb" "g_verb" "Gendyn" "gendyn" "GrainUGens" "grain_ugens"
   "Hilbert" "hilbert" "IEnvGen" "ienv_gen" "InOut" "in_out" "InfoUGens" "info_ugens"
   "Line" "line" "MacUGens" "mac_ugens" "MachineListening" "machine_listening"
   "MoogFF" "moog_ff" "Noise" "noise" "Osc" "osc" "PSinGrain" "psin_grain"
   "Pan" "pan" "PartConv" "part_conv" "PhysicalModel" "physical_model"
   "PitchShift" "pitch_shift" "Pluck" "pluck" "Poll" "poll" "SoundIn" "sound_in"
   "Splay" "splay" "TestUGens" "test_ugens" "Trig" "trig"
   "SC3plugins/BhobUGens" "sc3plugins/bhob_ugens"
   "SC3plugins/DEINDUGens" "sc3plugins/deind_ugens"
   "SC3plugins/DistortionPlugins" "sc3plugins/distortion_plugins"
   "SC3plugins/JoshUGens" "sc3plugins/josh_ugens"
   "SC3plugins/LadspaUGen" "sc3plugins/ladspa_ugen"
   "SC3plugins/MCLDUGens" "sc3plugins/mcld_ugens"
   "SC3plugins/MdaUGens" "sc3plugins/mda_ugens"
   "SC3plugins/NHUGens" "sc3plugins/nh_ugens"
   "SC3plugins/PitchDetection" "sc3plugins/pitch_detection"
   "SC3plugins/SLUGens" "sc3plugins/sl_ugens"
   "SC3plugins/TJUGens" "sc3plugins/tj_ugens"
   "Extensions/CDSkip" "extensions/cd_skip"
   "Extensions/DynGen" "extensions/dyn_gen"
   "Extensions/IBufWr" "extensions/i_buf_wr"
   "Extensions/PortedPlugins" "extensions/ported_plugins"
   "Extensions/f0plugins" "extensions/f0plugins"
   "Extensions/mi-UGens" "extensions/mi_ugens"
   "Extensions/safety-limiter" "extensions/safety_limiter"
   "Extensions/sc_faust" "extensions/sc_faust"
   "quarks/miSCellaneous_lib/WaveFolding" "quarks/wave_folding"
   "quarks/redSys/RedImpulse" "quarks/red_impulse"})


(defn file-ns [file]
  "snake_case target per MAPPING.md; ns form dashes it."
  (get file-map (str/replace file #"\.lisp$" "") (str/lower-case (str/replace file #"\.lisp$" ""))))

(def extra-requires
  {"Delays.lisp" ["[clojure-collider.ugens.line :as line]"]
   "Demand.lisp" ["[clojure-collider.ugens.line :as line]"]
   "Compander.lisp" ["[clojure-collider.ugens.delays :as delays]"]
   "Hilbert.lisp" ["[clojure-collider.ugens.delays :as delays]"
                   "[clojure-collider.ugens.fft :as fft]"]
   "BEQSuite.lisp" ["[clojure-collider.ugens.filter :as flt]"]})

(def preambles
  {"Delays.lisp"
   "(defn as-audio-rate-input\n  \"Port of Delays.lisp as-audio-rate-input: audio input passes through,\n   anything else is lifted through K2A (per-channel via multinew).\"\n  [input]\n  (ugen/unbubble\n   (ugen/multinew (fn [_cls in]\n                    (if (= :audio (ugen/rate in)) in (line/k2a:ar in)))\n                  'ugen input)))"})

(defn -main []
  (let [ir (map edn/read-string (str/split-lines (slurp ir-file)))
        dus (filter :lisp-name ir)
        ported (ported-fns)
        by-file (group-by :file dus)
        skipped (atom [])
        customs (atom [])]
    (doseq [[file defs] (sort-by first by-file)]
      (let [emitted (mapcat (fn [d] (keep (fn [v] (emit-defn d v ported)) (:variants d))) defs)
            have (into #{} (map :fname emitted))]
        (doseq [d defs v (:variants d)
                :let [f (cl-fname (:lisp-name d) (:rate v))]
                :when (and (not (have f)) (not (ported f)))]
          (swap! customs conj [file (:lisp-name d) (:rate v) (:flavor v)]))
        (when (seq emitted)
          (let [nsn (str "clojure-collider.ugens." (-> (file-ns file)
                                                      (str/replace "_" "-")
                                                      (str/replace "/" ".")))
                doc (str "Port of cl-collider ugens/" file " (mechanical defugen forms).\n"
                         "  Generated by scripts/emit_ugens.clj; customs hand-ported separately.")
                content (let [codes (map :code emitted)
                              body (str/join "\n" codes)
                              tail (when-not (hand-files file)
                                     (let [target (str src-dir (file-ns file) ".clj")
                                           hmarker ";; ---- hand customs (preserved across regeneration) ----"
                                           old (try (slurp target) (catch Exception _ nil))]
                                       (when (and old (str/includes? old hmarker))
                                         (subs old (str/index-of old hmarker)))))
                              uses-op? (or (boolean (re-find #"op/" body))
                                            (boolean (and tail (re-find #"op/" tail))))
                              uses-nc? (or (boolean (re-find #"no-check" body))
                                            (boolean (and tail (re-find #"no-check" tail))))]
                          (str "(ns " nsn "\n  \"" doc "\"\n"
                               "  (:require [clojure-collider.synth.ugen :as ugen]"
                               (when uses-op? "\n            [clojure-collider.synth.operators :as op]")
                               (apply str (map #(str "\n            " %) (get extra-requires file [])))
                               "))\n\n"
                               (when uses-nc? "(def ^:private no-check (fn [_] nil))\n\n")
                               (when-let [pre (get preambles file)] (str pre "\n\n"))
                               body))]
            (if (hand-files file)
              (let [target (str src-dir (file-ns file) ".clj")
                    marker ";; ---- generated mechanical forms (scripts/emit_ugens.clj) ----"
                    old (slurp target)]
                (if (str/includes? old marker)
                  (println "SKIP (already merged)" target)
                  (do (spit target (str old "\n\n" marker "\n\n"
                                        (str/join "\n" (map :code emitted))))
                      (println "MERGED" target (count emitted) "fns"))))
              (let [target (str src-dir (file-ns file) ".clj")
                    hmarker ";; ---- hand customs (preserved across regeneration) ----"
                    old (try (slurp target) (catch Exception _ nil))
                    tail (when (and old (str/includes? old hmarker))
                           (subs old (str/index-of old hmarker)))]
                (.mkdirs (.getParentFile (java.io.File. target)))
                (spit target (str content (when tail (str "\n\n" tail))))
                (println "WROTE" target (count emitted) "fns")))))))
    (println :hand-file-appendix @skipped)
    (println :custom-or-skipped (sort @customs))))

(-main)
