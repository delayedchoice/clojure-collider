# clojure-collider — Port Plan

> Source: `../cl-collider` (Common Lisp, `cl-collider.asd` 2025.8.21, 7 621 LOC + 110 files).  
> Target: Clojure (JVM) in this directory.  
> Principle: keep `../cl-collider` pristine; all new work lives under `clojure-collider/`.  
> Toolchain: Clojure CLI + `deps.edn` + `tools.build` (no Leiningen). JDK 21 / Clojure 1.12.4 verified on this host.

---

## 0. How to use this plan

This is a **collaborative waterfall with verified milestones** — we review and sign off each phase before committing the next. Phases are sized so one `clojure -M:test` run or one manual `scsynth` check proves parity.

1. Read `docs/ARCHITECTURE.md` then this file.
2. For each Phase, the **Done criteria** must be green before moving on (CI + manual SC check).
3. Open questions are in `docs/DECISIONS.md`; add yours there.
4. Progress is tracked in `docs/MAPPING.md` (one row per upstream file).

**Next action:** v0.1.0 RELEASE READY (`make check` green: 95/405, kondo 0/0; jar+pom built; CHANGELOG + CI stub + `:deploy` alias done). Only remaining: `clojure -M:deploy ...` push with Clojars creds, and optional nREPL middleware (deferred — see DECISIONS).

---

## 1. Inventory of what we're porting

| # | Upstream file(s) | LOC | Role | Hard part |
|---|---|---|---|---|
| A | `osc/{osc,transmit,package,sc-osc.asd}` | 341 + 137 | OSC encode/decode + UDP device + responder table + listener thread | Binary compat with SC (`/b_alloc`, bundles, timetags). Replaces `usocket` + `ieee-floats` with `java.nio.ByteBuffer` |
| B | `util.lisp` + `package.lisp` + `id-map.lisp` | 124+223+61 | Reader macro `[ ]`, helpers (`cat`, `blend-nth`, `as-keyword`), free-list ID allocator, `*s*` dynamic vars, `thread-wait` | Reader macro unnecessary in Clojure (vectors native); ID map → `atom` free-list or `java.util.concurrent` |
| C | `server-options.lisp` | 66 | `defstruct server-options` + `build-server-options` CLI arg builder | Trivial → `defrecord` + pure fn |
| D | `scheduler.lisp` | 403 | `unix-time` per-impl, `sched-param` realtime priority, `scheduler` (heap+mutex+condvar+thread via `pileup`), `tempo-clock` (BPM, beats↔secs, quant, resync thread) | Replace `pileup` with `PriorityBlockingQueue` or `data.priority-map`; remove `pthread_setschedparam`; use `java.time`/`System/nanoTime` |
| E | `server.lisp` | 869 | `server`/`rt-server`/`external-server`/`nrt-server`, `node`/`group`/`synth`, `sync` with per-thread semaphores, responders (`/done`, `/status.reply`, `/n_go`, `/n_end`, `/b_info`, …), `volume`/`mute`, `jack-connect`, `control-get/set` | Core impure subsystem; needs UDP lifecycle, `core.async` or `manifold` vs plain threads; semaphore map keyed by thread |
| F | `ugen.lisp` | 357 | `+inf+`, `rate`/`rate-number`/`number-rate`, `ugen` hierarchy, `multinew`/`lst-operation` (multi-channel expansion), topo helpers (`add-to-synth`, `init-topo-sort`, `schedule`, `input-spec`, `write-def-ugen-*`) | Multi-channel expansion shape must be preserved exactly; rate lattice `:ar > :kr > :ir > :dr` |
| G | `synthdef.lisp` | 671 | `synthdef` class, graph optimize + topo sort, `encode-synthdef` (version 1/2 binary), `control`/`with-controls`/`named-control`, `defsynth` + `play` + `proxy` macros, `with-rendering`, `convert-code` rewrite table | Heart of DSL. Macros expand differently in Clojure; binary encoding must be byte-identical |
| H | `operators.lisp` | 565 | `unary-operator`/`binary-operator`, `def-unary-op`/`def-binary-op`, `madd`, `mix`, `bubble`/`unbubble`, `range`/`lin-lin` etc. | Operators overload `+ - * / < > <= >=` (DECISIONS.md 2026-10-04); same table needed for `convert-code` aliases |
| I | `buffer.lisp` + `bus.lisp` | 500+67 | `buffer` alloc/read/write/cue/zero, consecutive bufs, wavetable; `bus` audio/control alloc/free, `bus-string` | State held on `server.buffers`/`audio-buses` arrays; needs atom + lock |
| J | `volume.lisp` | 40 | Master-volume synth (`x-out`+`lag`) | Depends on G |
| K | `ugens/*.lisp` (37 files) + `ugens/SC3plugins/*` + `ugens/Extensions/*` + `ugens/quarks/*` | 3 061 | ~600 `defugen` forms creating `.ar`/`.kr`/`.ir` fns (e.g. `sin-osc.ar`, `resonz`, `env-gen`, `out.ar`) | Bulk port; candidates for auto-transpilation |
| L | `slynk-extensions.lisp` / `swank-extensions.lisp` | — | Editor glue | Drop in Phase 0; re-add as nREPL middleware later if desired |

Total upstream: ~7.6 kLOC core + OSC; re-measured 110 files.

---

## 2. Clojure design choices (decisions you must confirm)

### 2.1 Build & runtime

- **Deps:** `deps.edn` + `tools.build` (`build.clj`). No Leiningen (not installed; CLI is). Babashka for scripts (`bb.edn`).
- **Naming:** `clojure-collider` project, root ns `clojure-collider.*` (kebab) → file `clojure_collider/*`. Alias `sc` kept via `(ns sc … (:require [clojure-collider.core :as sc]))` for user nostalgia.
- **Dynamic vars:** keep `*s*`, `*sc-synth-program*`, `*sc-plugin-paths*`, `*sc-synthdefs-path*`, `*synthdef*`, `*synth-definition-mode*`, `*synthdef-metadata*` as `^:dynamic` vars. NRT vs RT path already branches on `*s*` type.
- **Error handling:** `ex-info` with `{:op :sclang :code …}`; upstream `error`/`warn` → `throw`/`log/warn`.

### 2.2 UGen naming — the one bikeshed that blocks codegen

CL allows `sin-osc.ar` as a single symbol (dot is valid). Clojure does **not**: `foo.bar` is field access, `foo/bar` needs `defn` simple-symbol, and `defn foo/bar` is invalid.

Tested on this host: `defn sin-osc.ar` → `ClassNotFoundException`; `defn sin-osc/ar` → `simple-symbol?` failure.

**Three viable idioms (pick one, we record in `DECISIONS.md`):**

| Option | Example call | Codegen | Pros / cons |
|---|---|---|---|
| **A. Suffix dash** | `(sin-osc-ar freq)` | trivial | Works but user rejected — less visual than colon |
| **— Single fn + rate kw** (deferred alias) | `(sin-osc :ar freq)` | one fn per UGen | Could add `(sin-osc :ar …)` dispatcher later; not primary |
| **B. Colon (DECIDED)** | `(sin-osc:ar freq)` / `(sin-osc:kr freq)` | `defn sin-osc:ar` → `user$sin_osc_COLON_ar`, verified ok | Keeps CL dot visual, passes `defn` spec (unlike `sin-osc.ar`→ClassNotFound, `sin-osc/ar`→simple-symbol? fail) |
| **C. Ns-per-UGen** | `(sin-osc/ar freq)` where `ar` is var in ns `clojure-collider.ugens.sin-osc` | `create-ns` + `intern` | Mirrors CL dot syntax; but `defn` with slash not allowed → must `intern` at runtime, tooling suffers |

> **Decided: B colon `sin-osc:ar` (verified).** Generate `sin-osc:ar`, `sin-osc:kr`, `sin-osc:ir`. Require as `(require '[clojure-collider.ugens.osc :refer [sin-osc:ar]])`. Optional `(sin-osc :ar …)` dispatcher may be added later.

**Status: decided colon.** Single-fn dispatcher deferred.

### 2.3 Types: records vs maps vs deftype

| CL | Clojure port |
|---|---|
| `defclass server/rt-server/external-server` | `defrecord Server`, `defrecord RtServer`, protocol `IServer` (`boot?`, `send-msg`, `send-bundle`, `install-responder`, …). Use `defrecord` so `assoc` works in tests. |
| `defclass ugen / pure-ugen / multiout-ugen` | `defrecord UGen {:name :rate :inputs :special-index :signal-range :synth-index :antecedents …}` + protocol `IUGen`. Keep `:rate` as keyword `:audio/:control/:scalar/:demand` internally, `rate-number` maps to `0/1/2/3` only at encode time. |
| `defclass synthdef` | `defrecord SynthDef {:name :controls :control-names :children :constants :width-first-ugens …}` with `atom` fields for mutable `children`/`available` during topo sort, or use transient + pure functional topo sort. |
| `defstruct server-options` | `defrecord ServerOptions` + `defn ->server-options` + `build-server-options` pure fn |
| `defstruct id-map` | Not needed for most allocators (use `atom` counter + `java.util.concurrent.ConcurrentHashMap` for semaphores). Keep a small `IdMap` record for `/sync` id ↔ semaphore. |

We avoid `deftype` unless we need Java interop fields. Prefer `defrecord` for printability.

### 2.4 Concurrency

- Upstream: `bordeaux-threads` + `bt:make-lock` + `bt:condition-variable` + `pileup:heap` + per-thread semaphores (`ccl:make-semaphore`/`sb-thread:make-semaphore`/`mp:make-semaphore`).
- Port: `java.util.concurrent.locks.ReentrantLock` + `Condition`, `PriorityBlockingQueue` or `clojure.data.priority-map`, `java.util.concurrent.Semaphore` (1 permit) or `CompletableFuture` for `/sync`. Realtime `pthread_setschedparam` → no-op (JVM cannot set `SCHED_RR`; document and provide `Thread/setPriority` best-effort on scheduler threads).
- `scheduler` + `tempo-clock` each own a `Thread` (not `future`) so we can `join`. Expose `sched-add`, `sched-clear`, `sched-stop`, `tempo-clock-*` with identical arities.

### 2.5 OSC

- Replace `usocket` (UDP) with `java.net.DatagramSocket` or `java.nio.channels.DatagramChannel`. Replace `osc` lib's `cat`/`encode-int32`/`encode-float32` with `java.nio.ByteBuffer` (order `BIG_ENDIAN`). Timetags: SC uses NTP 64-bit (seconds since 1900 + fraction); map `:now` → `0 0 0 0 0 0 0 1` as upstream does.
- Typetags: `,ifsb[]` — keep encoder faithful. `encode-message`/`encode-bundle`/`send-message`/`send-bundle` keep same signatures as CL (`(send-message server "/s_new" name id …)`).
- Listener thread parses datagrams, dispatches via `reply-handle-table` (now `ConcurrentHashMap`).

### 2.6 Vectors & multichannel expansion

- CL installs a `named-readtable :sc` so `[1 2 3]` → `(list 1 2 3)`. **Clojure vectors already are `[1 2 3]`** — drop the readtable entirely. `lst-operation`/`multinew` must treat Clojure vectors and lists uniformly as "seqable channels". Proposal:

```clojure
(defn multinew [ctor cls & inputs]
  (let [inputs (map #(if (sequential? %) % [%]) inputs)   ;; treat scalar as 1-elem
        max-n  (apply max (map count inputs))]
    (if (= max-n 1) (apply ctor cls (map first inputs))
        (mapv #(apply ctor cls %) (apply map vector (map #(take max-n (cycle %)) inputs))))))
```

Behavior matches CL's `nth-wrap`/`cycle` semantics; verify with `sin-osc:ar [440 441]`.

### 2.7 SynthDef binary encoding

Must be byte-identical to CL's `encode-synthdef` (version 2 default). Use `DataOutputStream` over `ByteArrayOutputStream` + explicit `writeShort`/`writeInt`/`writeFloat`. Test strategy: generate same synth in CL and Clojure, compare `sha256` of `.scsyndef` bytes.

---

## 3. Phased roadmap (with Done criteria)

### Phase 0 — Scaffolding & toolchain (½ day)

**Goal:** `clojure -M:test` green on empty project; `scsynth --version` check; upstream git submodule reference frozen.

- `deps.edn` (`org.clojure/clojure 1.12.0`, `org.clojure/tools.build`, `org.clojure/data.priority-map`, `aleph` or raw `DatagramSocket` — choose after spike, `com.taoensso/timbre` for logging).
- `build.clj`, `bb.edn`, `Makefile` with `make check` (kondo + test).
- CI stub (GitHub Actions `clojure -M:test` only).
- `docs/MAPPING.md` initial table.

**Done:** `clojure -M:build` produces jar; `bb check` passes; README badge green.

### Phase 1 — OSC layer (`sc-osc` → `clojure-collider.osc.*`)

**Goal:** Send `/status` to a live `scsynth` and receive `/status.reply`.

- `osc.encode` (typetags, blobs, timetags, `encode-message`, `encode-bundle`, `encode-bundle` with `*immediate-timetag*`).
- `osc.transmit` (`OscDevice` record, `osc-device`, `send-message`, `send-bundle`, `add-osc-responder`, `make-listening-thread`, `close-device`).
- Tests: unit (byte-level vectors from CL's `osc.lisp` comments + SC book examples) + integration (boot external `scsynth` on random port, round-trip `/sync`).

**Done:** `test osc.encode-test` byte-equal to CL fixtures; manual `nc -u` trace shows identical datagrams.

### Phase 2 — UGen core & SynthDef graph (`ugen.lisp` + `synthdef.lisp` core)

**Goal:** Build and topo-sort a synth graph, encode it, write `.scsyndef`.

- `synth.ugen` (records, `rate`, `rate-number`, `act`, `multinew`, `add-to-synth`, `collect-constants`, topo `init/available/schedule`).
- `synth.synthdef` (`SynthDef` record, `add-ugen`/`replace-ugen`, `optimize-graph`, `build-synthdef`, `index-ugens`, `encode-synthdef` v1+v2, `write-synthdef-file`, `load-synthdef`/`recv-synthdef` skeleton without server).
- Unit tests must use golden files: generate `sine-wave.scsyndef` in CL (via script that loads `cl-collider` and dumps bytes), store under `test/resources/golden/`, assert SHA equal.

**Done:** Golden file tests pass; `(make-instance SynthDef …)` → `.scsyndef` byte-identical.

### Phase 3 — Controls & operators

**Goal:** `with-controls`, `named-control`, and math operators (colon naming `sin-osc:ar`).

- `synth.control` (`control` UGen, `add-controls`, `make-control`, `with-controls` macro, `named-control`).
- `synth.operators` (`def-unary-op`/`def-binary-op` → `defn neg`, `reciprocal`, … overloaded `+`, `*`, `==`, `if*`, `madd`, `mix`, `range`, `lin-lin`, `env` helpers).
- `convert-code` table (`*synthdef-function-table*` → `synthdef-equivalent-function`) ported as `clojure-collider.synth.convert` with `walk` over forms.
- Tests: operator purity (numeric fallback vs UGen path), `with-controls` expands to correct `Control` ugens.

**Done:** Example `(defsynth sine-wave [note 60] (let [freq (midicps note)] (out:ar 0 (sin-osc:ar [freq (+ freq 2)] 0 0.2))))` (and CL compat `((note 60))`) encodes correctly.

### Phase 4 — Server skeleton, scheduler, buffers/buses

**Goal:** Boot `scsynth` from Clojure, `sync`, schedule callbacks.

- `server.options` (`ServerOptions`, `build-server-options`).
- `scheduler` (`Scheduler`, `TempoClock`, `sched-add`/`sched-run`/`sched-stop`, `clock-*`, `at`/`callback`/`quant`/`now`/`beat-dur`).
- `server.server` (`Server`, `RtServer`, `ExternalServer`, `NrtServer`, `make-external-server`, `server-boot`/`server-quit`, `sync`/`with-async`, `all-running-servers`, `add-reply-responder`, responder table).
- `buffer` + `bus` (alloc/free, `buffer-alloc`/`buffer-read`/`buffer-write`, `bus-audio`/`bus-control`/`bus-free`, `floatfy` protocol).
- `volume` (`volume`/`mute`/`unmute` synth).

**Done:** `(def s (make-external-server "test")) (server-boot s) (sync s) (server-quit s)` works against real `scsynth`; buffer alloc/free round-trips `/b_info`.

### Phase 5 — High-level DSL (`defsynth`/`play`/`proxy`/`with-rendering` + nodes/groups)

**Goal:** End-to-end musical examples from README work.

- `synth.def` (`defsynth`, `play`, `proxy`/`proxy-ctrl`, `with-rendering` for NRT file render).
- `server.node` (`node`, `group`, `synth`, `ctrl`, `map-bus`, `move-node`, `free`/`release`/`is-playing-p`, `on-free`, `group-free-all`/`server-free-all`/`stop`).
- Tests: NRT render (`with-rendering` writes `foo.aiff`, assert non-zero file + header `FORM`).

**Done:** All four README snippets (sine, synthdef, proxy, melody, NRT, disk_writer) run unmodified modulo naming (`sin-osc:ar` vs CL `sin-osc.ar`).

### Phase 6 — UGen library bulk port

**Goal:** All 37 `ugens/*.lisp` + `SC3plugins/*` + `Extensions/*` available.

- Auto-transpile via `bb scripts/transpile-ugens.clj` that parses `defugen` forms (using `edamame` or `parcera`) and emits `defn` stubs. Manual fix-ups only for `EnvGen`/`Demand`/`FFT` which have custom `process-env` logic.
- Each UGen file becomes `src/clojure_collider/ugens/*.clj` with its own ns (`clojure-collider.ugens.filter` etc.), re-exported from `clojure-collider.ugens`.
- Tests: per-file `(load-file …)` smoke + spec for arities (CL `&optional` defaults preserved).

**Done:** `(require '[clojure-collider.ugens :as u]) (u/sin-osc:ar 440)` returns a UGen; `(count (filter var? (ns-publics 'clojure-collider.ugens)))` ≥ upstream export count (216).

### Phase 7 — Polish, parity, docs

- `clj-kondo` config, `codox`/`cljdoc`, `examples/` ported from `cl-collider` README + `cl-patterns` interop note.
- Performance: topo sort micro-benchmarks; scheduler jitter measurement.
- Fallback for `slynk`/`swank` → `nrepl.middleware` (optional).
- Release: `v0.1.0` jar to Clojars, `LICENCE` (Public Domain / 0-clause MIT same as upstream).

---

## 4. Risks & mitigations (summary — see `RISK-REGISTER.md`)

| Risk | Impact | Mitigation |
|---|---|---|
| Binary `encode-synthdef` off-by-one | SC rejects synthdef | Golden-file SHA tests from Phase 2; fuzz against CL |
| Rate lattice mismatch (`check-same-rate-as-first-input` etc.) | Silent wrong audio rate | Port `check-fn` verbatim; property tests for rate propagation |
| Scheduler drift vs SC `sched-ahead` | Timing jitter | Use `System/nanoTime` monotonic source same as CL `monotonic-time`; measure jitter in CI |
| UDP fragmentation for large synthdefs (`/d_recv` vs `/d_load`) | `scsynth` drop | Keep CL fallback logic (`> max-datagram` → write file + `/d_load`) |
| `defugen` naming break | Interop with existing CL snippets | Provide both suffix and rate-kw API + alias map; `DECISIONS.md` records choice |

---

## 5. Decisions — all locked 2026-10-02

1. **UGen naming** — ✅ `sin-osc:ar` (colon)
2. **OSC lib** — ✅ hand-roll `ByteBuffer` (not `clj-osc`)
3. **defsynth params** — ✅ Overtone vector `[freq 440]` primary, CL `((freq 440))` compat
4. **Scheduler** — ✅ `PriorityBlockingQueue` + `priority-map` for tests
5. **Module boundaries** — ✅ one ns per CL file

See `DECISIONS.md` for rationale. Ready to start Phase 0 on your go.
