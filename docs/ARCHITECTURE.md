# Architecture — clojure-collider

## 1. Upstream decomposition

```
cl-collider (ASDF)
├── sc-osc                osc/package, osc, transmit (+ ecl/lw extensions)
├── package               named-readtable :sc, (defpackage sc/sc-user), exports ~216 symbols
├── id-map                free-list allocator for sync ids
├── util                  thread-wait, full-pathname, cat, sc-program-run, [ ] reader macro
├── server-options        defstruct server-options, build-server-options
├── scheduler             unix-time (4 impls), sched-param SCHED_RR, Scheduler, TempoClock
├── server                Server ← RtServer ← ExternalServer / NrtServer, Node/Group/Synth, volume
├── ugen                  rate lattice, UGen base, multinew, topo helpers, write-def-ugen
├── synthdef              SynthDef, Control, with-controls, defsynth/play/proxy, encode-synthdef
├── operators             unary/binary operators → UnaryOpUGen/BinaryOpUGen
├── volume                volume/mute/unmute
├── buffer / bus          buffer alloc/IO, bus alloc (audio/control)
├── ugens/* (37 files)    defugen expansions → 600+ .ar/.kr fns
├── SC3plugins/*, Extensions/*, quarks/*   extra UGens
└── slynk/swank extensions editor glue
```

OSC → Server → SynthDef → UGen is the dependency order. Buffer/Bus and Scheduler are orthogonal.

## 2. Target namespace map

```
clojure-collider
├── core.clj                    re-exports public API (mirrors package.lisp export list)
├── util.clj                    string/path/thread helpers, no reader macro
├── id_map.clj                  (optional) IdMap record — otherwise atom counter
├── osc/
│   ├── encode.clj              <- osc/osc.lisp   (ByteBuffer, typetags, timetags, blobs)
│   └── transmit.clj            <- osc/transmit.lisp (DatagramSocket, responders, listener)
├── server/
│   ├── options.clj             <- server-options.lisp
│   ├── scheduler.clj           <- scheduler.lisp (Scheduler + TempoClock)
│   ├── server.clj              <- server.lisp (protocols IServer, ISend, INodeWatcher)
│   ├── external.clj            <- server.lisp ExternalServer
│   ├── nrt.clj                 <- server.lisp NrtServer + with-rendering
│   └── node.clj                <- server.lisp Node/Group/Synth ops (free, ctrl, map-bus)
├── synth/
│   ├── ugen.clj                <- ugen.lisp
│   ├── control.clj             <- synthdef.lisp Control/with-controls/named-control
│   ├── synthdef.clj            <- synthdef.lisp SynthDef, encode, build, topo sort
│   ├── convert.clj             <- synthdef.lisp convert-code + function table
│   ├── def.clj                 <- synthdef.lisp defsynth/play/proxy macros
│   └── operators.clj           <- operators.lisp
├── buffer.clj                  <- buffer.lisp
├── bus.clj                     <- bus.lisp
├── volume.clj                  <- volume.lisp
└── ugens/
    ├── core.clj                re-exports all ugens
    ├── filter.clj  osc.clj  env_gen.clj  …  (one per upstream file)
    └── sc3plugins/  extensions/  quarks/
```

One ns per CL file keeps `MAPPING.md` trivial and `git diff` against upstream readable. `core.clj` does `potemkin/import-vars` (or manual `def` aliases) to present flat `sc/*` API.

## 3. Key data structures

### 3.1 Rate lattice

```clojure
:scalar  0  :ir
:control 1  :kr
:audio   2  :ar
:demand  3  :dr
```

`rate-number` / `number-rate` are pure maps. `rate` is a protocol: `(rate x)` → keyword. Numbers → `:scalar`. Collections → max rate via `string` sort trick preserved for parity, but better: `(apply max-key rate-number (map rate xs))`.

### 3.2 UGen

```clojure
(defrecord UGen [name rate inputs special-index signal-range
                 synth-index antecedents descendants width-first-antecedents synthdef])
(defrecord ProxyOutput [source rate signal-range output-index])
```

`multinew` handles multi-channel expansion. Keep CL semantics: scalars expand via `cycle`, vectors/lists are channels. Result is unwrapped by `unbubble` when single-channel.

### 3.3 SynthDef

```clojure
(defrecord SynthDef [name controls control-names control-ugen-count
                     children constants max-local-bufs
                     available width-first-ugens rewrite-in-progress reblock resample])
```

Mutable during graph build (`children`, `available`) — use `atom` inside record or use `volatile!` for topo phase. Topo sort is Kahn's algorithm: `init-topo-sort` builds `antecedents`/`descendants`, `schedule` pops `available`.

Encoding: two versions. Default v2 uses 32-bit int for input/output counts. Write helpers `write-pstring`, `write-def-ugen-v1/v2`, `encode-synthdef`.

### 3.4 Server

```clojure
(defprotocol IServer
  (boot? [s])
  (send-msg [s & msg])
  (send-bundle [s ts & msgs])
  (install-responder [s cmd f])
  (remove-responder [s cmd]))

(defrecord Server [name server-options id group-id default-group
                   buffers audio-buses control-buses tempo-clock node-proxy-table lock])
(defrecord RtServer [ ... scheduler sc-thread boot? buffer-get-handlers control-get-handlers
                      sync-id-map node-watcher volume volume-control-synth on-free-handlers])
(defrecord ExternalServer [ ... osc-device sc-reply-thread osc-trace])
(defrecord NrtServer [ ... score])
```

`id` is `atom [999]` (or `[1000]` on SBCL compat note — not needed on JVM, use `[1000]`). `get-next-id` uses `swap!` + `inc`.

`sync` uses `CompletableFuture` or `Semaphore` stored in `sync-id-map` (`ConcurrentHashMap` id→future). `send-message "/sync" id` then `(.get future 5 TimeUnit/SECONDS)`.

### 3.5 Scheduler

```clojure
(defrecord SchedEvent [timestamp task]) ; timestamp double secs or beats
(defrecord Scheduler [name server in-queue mutex condition-var sched-thread sched-status timestamp-fn])
(defrecord TempoClock [name server bpm base-seconds base-beats beat-dur timing-offset sync-thread …])
```

`in-queue` is `PriorityBlockingQueue` ordered by `:timestamp`. Worker thread loops: wait on `Condition` until next event timestamp ≤ `(sched-time) - sched-ahead`, then pop and `future`/`invoke`. Same logic as CL's nested `loop while` with `condition-wait`.

`unix-time` → `System/currentTimeMillis` / `System/nanoTime` + offset. `monotonic-time` is `System/nanoTime` / 1e9.

Realtime priority: no-op. Document.

### 3.6 OSC

```
DatagramSocket
  send: ByteBuffer (BIG_ENDIAN) → byte[] → DatagramPacket
  recv: DatagramPacket → parse address / typetag / blobs → dispatch via ConcurrentHashMap
```

`encode-message` / `encode-bundle` produce `byte[]`. `osc-device` opens socket, optionally binds `local-port`, starts `listener-thread` via `Thread.` + `while running?` loop blocking on `receive`.

### 3.7 Buffers / Buses

```clojure
(defrecord Buffer [bufnum server frames chanls sr path])
(defrecord Bus [busnum type chanls server])
```

Server holds `buffers` as `Object[]` (size `num-sample-buffers`) and `audio-buses`/`control-buses` as arrays of `Bus|nil`. Access guarded by `ReentrantLock` (`server-lock`). `find-consecutive-nil` scans for free slots.

## 4. Macro layer

### 4.1 defugen

CL:
```lisp
(defugen (resonz "Resonz") (&optional (in 0.0) (freq 440.0) ...) ((:ar …) (:kr …)))
;; → defines RESONZ.AR and RESONZ.KR fns
```

Clojure (suffix strategy):
```clojure
(defmacro defugen [name-etc args rate-forms & opts]
  ;; opts: :check-fn, :signal-range
  ;; rate-forms: ((:ar body) (:kr body))
  ;; DECIDED colon: emits (defn resonz:ar [in freq …] …) + (defn resonz:kr …)
)
```

Codegen also emits docstring from `HelpSource/Classes/<UGen>.html` if present (mirrors `read-ugen-description`).

### 4.2 defsynth / with-controls / convert-code

`defsynth` expands to: create `SynthDef`, bind `*synthdef*`, call `with-controls` for params, walk body via `convert-code` (alias table only — overloaded `+` etc. dispatch, so arithmetic needs no `+`→`+~` rewrite), `build-synthdef`, then `recv/load-synthdef` + `sync`.

`convert-code` is a `clojure.walk/postwalk` variant that skips binding forms (`let`, `let*`, `loop`, `doseq`). Port table verbatim from `synthdef.lisp`.

`with-controls` is a macro that calls `make-control` at expansion time? In CL it runs at runtime to add `Control` UGens. In Clojure, keep runtime call: `(with-controls [[note 60] [amp 0.2 :ar]] …body…)`.

`play` and `proxy` are macros that build a throwaway synthdef with `*temp-synth-name*` and `out.ar`/`fade`. Preserve arities.

## 5. Build & test

- `deps.edn` aliases: `:dev` (nrepl, kondo), `:test` (cognitect/test-runner), `:build` (tools.build), `:bb` (babashka).
- Golden files under `test/resources/golden/*.scsyndef` generated by CL script.
- `test` ns per src ns; use `clojure.test` + `matcher-combinators` for graph assertions.
- `bb scripts/transpile-ugens.clj` for bulk UGen port.
- `clj-kondo` config to allow `defsynth`/`defugen` etc.

## 6. Not porting (Phase 0)

- `slynk-extensions`/`swank-extensions` — editor glue; replace later with `cider-nrepl` middleware if needed.
- LispWorks/ECL `cffi` extensions for `sched-param` — JVM equivalent is no-op.
- `precise-time` dep — replaced by `java.time`.

## 7. Sequence: booting scsynth

```
make-external-server → bootup-server-process
  → ProcessBuilder ["scsynth" "-u" port "-a" num-audio-bus … "-U" plugin-paths]
  → osc-device (DatagramSocket)
  → send "/sync" -1 loop until boot? flag
  → send "/notify" 1, "/status", sched-run, tempo-clock-run, server-free-all, alloc buses
```

Keep CL's 30×30 ms retry loop and `*server-boot-hooks*`/`*server-quit-hooks*` atoms.
