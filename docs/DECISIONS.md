# Decisions

Record every non-trivial choice here. Newest on top.

## Open

_(none — all initial decisions locked 2026-10-02)_

## Decided
### 2026-10-20 — clip/fold/wrap rate-dispatch methods (upstream defmethods)
- Upstream `clip`/`fold`/`wrap` dispatch on combined input rate (`:scalar` folds to numbers via `:ir` op graphs, else the unit at `:audio`/`:control`). Ported to `trig.clj`; facades re-export; drone voice uses `clip` (was forced `clip:ar`/`clip:kr`).
- Why now, not just parity: an audio-rate `Clip` whose inputs are all scalars/consts segfaults scsynth 3.13 (NRT + silent live node) when a `Control` unit is present. Bytes are structurally valid; sibling `Fold` is immune. Forcing `:ar` (as the voice did for control proxies) emits exactly that shape. Dispatch never does: scalars fold, controls go `:kr` (verified renders).
- `op/if*` picks eagerly on boolean conditions (number comparisons yield booleans). Previously any boolean condition threw in `multinew` (no rate), so no working graph changes shape; this un-breaks scalar `fold:ir`.
- Status: decided

### 2026-10-20 — backend synth-control filtering (amends patterns port)
- Only controls reach `/s_new`: reserved set grew to pitch keys (`:freq :midinote :note :degree :octave :root :scale :base-freq :base-note`, all consumed into resolved `:freq`) plus `:out-bus` (consumed into `:out`). Rationale: a keyword control (`:scale :major`) crashed the OSC encoder (`seq` on keyword), and vector pitch keys don't belong on the wire.
- OSC encoder maps keywords/symbols to `s` strings (sclang parity) instead of throwing — backstop for any future non-numeric control.
- Gate-0 release only for synths exposing a `gate` control (`gated-synth?`); percussive voices self-free.
- Status: decided

### 2026-10-20 — cl-patterns MVP+scales port (src/patterns)
- Source: `defaultxr/cl-patterns` v0.7 MIT, sibling checkout `../cl-patterns` (reference only, never committed). Scope: MVP slice (utility-RNG/helpers, conversions, event, value patterns + pbind/pb/pmono/pdef, backend + cl-collider backend, clock + play/stop) PLUS scales/chords/tunings. Deferred: track/cycles/pmeta/bjorklund/batch, render, MIDI/alsa/incudine backends, formats.
- Model: CLOS classes become plain data maps `{:ptype ::patterns/pwhite ...}` + ONE generic `Stream` record `{:state (atom {...}) :step (fn [state] -> [v state'] | ::eop)}`. `pnext!` runs step, swaps state. `as-pstream`: Stream identity; pattern map -> fresh Stream; plain value -> infinite const stream; sequential -> infinite pseq. Pattern-valued length/repeats bounds NOT supported (MVP): number or `:inf` only.
- End-of-pattern: namespaced keyword `:clojure-collider.patterns.util/eop` (alias `u/eop`). `pnext!` returns it; `next-n`/`peek-n` stop at it (never include it).
- RNG: `(def ^:dynamic *rng* (java.util.Random.))` in patterns.util; `(seed-rng! long)`; helpers `rand-range [lo hi)` uniform double, `rand-exp [lo hi]` = `lo*(hi/lo)^rand` (lo/hi nonzero same-sign, else assert), `rand-gauss [mean dev]`, `rand-int-range`. Tests bind fixed seeds; goldens assert exact seeded sequences for pwhite/pexprand/pseries/pseq.
- Event: plain map. `(event & kvs)`. `event-value [ev k]` resolves MVP keys with defaults: type :note, instrument :default, group 1, out 0, amp 0.5 (db cross-converts via conversions), pan 0, tempo (clock or 1), beat, delta := dur, dur 1, duration (dur/tempo), sustain := legato*dur (default legato 0.8), freq 440 <- midinote 69 <- note 0 / degree 5 + root 0 + octave 5 + scale :major (via scales). Vector values multichannel-expand (max length, wrap). `combine-events` (later wins, eop poisons, nil-valued key aborts to nil).
- Backend: protocol `Backend (play-event [b ev beat-sec])`. cl-collider backend record `{:server}`: strips reserved keys `#{:type :dur :delta :duration :sustain :legato :quant :play-quant :beat :tempo :timing-offset :group :out :backend}`; resolves :freq; remaining keys -> synth control args; `:instrument` (keyword/string/symbol) -> synth name via sdef/synth at beat; sustain present -> `(server/ctrl node :gate 0)` scheduled sustain-beats later (synths must expose gate; else sound plays full envelope — documented).
- Clock: reuse our TempoClock (scheduler.clj). `start-clock-loop [& {:keys [tempo]}]` ensures it runs; `play [x]` (pattern/event/pdef/keyword-name) creates task `{:stream :next-beat :loop?}` scheduled via server/at chain: pull value, eop -> drop (or reset stream if loop), event -> play-event on enabled backends + schedule next at beat+delta. `stop`/`end`/`play-or-stop` per upstream. Quant: number -> next multiple via server/quant.
- Namespaces (one ns per upstream file): patterns.util, patterns.conversions, patterns.scales, patterns.event, patterns.patterns, patterns.backend, patterns.clock. Tests mirror: test/patterns/*_test.clj. Golden source: upstream t/*.lisp deterministic assertions + our seeded RNG goldens.
- Status: decided
### 2026-10-06 — NRT int-address + Sum fusion bugs (ascent homage)
- Integer OSC command ids (`9`=/s_new, `15`=/n_set) resolve over UDP and inside `/d_recv` completions but NOT as top-level NRT score messages (`Command not found`). Score writer rewrites via `nrt-cmd-names`; unknown ints throw loudly.
- `fuse-sums` rewrote refs by object identity; each fixpoint pass copies consumers, so chains fusing 2+ times left the outer consumer pointing at a dropped add. scsynth: `buffer coloring error ... output: 11 LPF`. Refs now match by `:synth-index`; `rewrite-inputs` throws on unresolvable inputs instead of emitting bad bytes. Regression test `sum-fusion-keeps-consumers` fails pre-fix.
- Homage `examples/ascent.clj`: Eb-area drone (7 detuned saws under the track's measured RMS arc). Render check: arc correlation 0.85, centroid 717 vs 781Hz.
- Status: decided
### 2026-10-06 — v0.1.0 release + middleware deferral
- Upstream `slynk/swank-extensions` are each ONE method (enriched `(synth ...)` arglist from synthdef metadata). No nREPL middleware ported: it would need nrepl+cider-nrepl deps and a live nREPL to verify, for editor-only value. CIDER users already get static arglists (all fns are real defns). Revisit if someone needs dynamic `synth` control display.
- `:deploy` alias resolves (slipset/deps-deploy verified); actual Clojars push needs `CLOJARS_USERNAME`/`CLOJARS_PASSWORD` env (absent on this host).
- Status: decided
### 2026-10-06 — buffer fill/sequences/wavetable
- scsynth headerless `/b_write` dumps are native-endian (LE on x86_64), NOT big-endian like the wire protocol. First hexdump misread as BE; garbage denormals exposed it. `buffer-load-to-list` reads LE (platform note in code).
- `/b_setSampleRate` does not exist in scsynth 3.13 (`Command not found` for if/ii/fi encodings) — upstream sends it but it can't work. Ported faithfully (forwards-compatible) with NRT shape-test only.
- `buffer-to-array` scalar channel returns 1-D, channel seq/nil-default returns 2-D (upstream dims rule); initial port over-nested the scalar case.
- Status: decided
### 2026-10-06 — proxy + NRT (with-rendering)
- `NrtServer` is a separate defrecord (not ExternalServer with nil device): send-message/send-bundle/sync branch on `instance?`. NRT ticks are `round(time * 2^32)` with NO latency/NTP epoch (score times relative, per upstream).
- `sync` on NRT returns immediately (no `/sync` round-trip); `boot?` is true so defsynth/play inside `with-rendering` emit `/d_recv`.
- Empty `-U ""` overrides scsynth's default plugin path and hides CORE UGens (`SinOsc not installed`); `-U` omitted when `*sc-plugin-paths*` empty. Gateless `play` renders ~0 frames — tests use `:pad` for duration.
- Macro hygiene: `proxy` needs `(:refer-clojure :exclude [proxy])` (clobbers clojure.core/proxy); macro-called private helpers (`nrt-formats`, `proxy-key-name`) must be public; `^bytes` hints can't appear in syntax-quote (ns-qualified to clojure.core/bytes) — use fully-qualified hints.
- `proxy` nil-body frees without dissoc (upstream-faithful); release ramp takes ~1s so tests poll `is-playing?`.
- Status: decided
### 2026-10-06 — Phase 7 polish (kondo, examples, benchmarks, facade)
- kondo: 601 findings → 0 errors, 0 warnings. Custom hooks (`.clj-kondo/hooks/`): `import-vars`/`def-unary-op`/`def-binary-op` (analyzed as variadic-fn defs), `with-controls` (both arg shapes), `with-node`. Facade switched from `import-all-vars` macro magic to generated explicit `import-vars` (`scripts/gen_facade.clj`) so every re-export resolves statically. kondo caught a REAL bug: `pokey-square:ar` 9/10/11-arg arities passed 14 fillers (ArityException at runtime).
- convert-code kept vectors as data: `[320 321]` was rewritten to the call `(320 321)` (ClassCastException in play bodies). Multichannel vectors now survive conversion.
- `defsynth` keyword names: `(str :kw)` produced `":kw"` defs the server can't find; normalized via `clojure.core/name`.
- Scheduler callbacks now use `bound-fn`: `*s*` was nil on worker threads (NPE in melody example). Matches CL global semantics.
- `node-free` explicit server arg was silently discarded (with-node rebind); now threads through. Found by review agent; no callers passed it.
- Benchmarks (`scripts/bench.clj`): topo build 501 units ~9ms; scheduler 100×10ms grid max deviation 1.79ms (200ms lookahead by design, same as upstream sched-ahead).
- Status: decided
### 2026-10-06 — Phase 6 bulk UGen port (transpiler + 9 hand batches)
- Pipeline: `scripts/extract_ugens.clj` (mini s-expr reader, no deps) → `/tmp/ugen_ir.edn` → `scripts/emit_ugens.clj` → 26 files (~240 fns) + 5 hand-file merges. 304 defugens + 17 macro-generated info ugens + 35 PV + extras = 532 variants + PV, all with Clojure homes (800 aggregator publics, zero collisions).
- Fn names LOWERCASED (`Delay-1`→`delay-1:ar`, `Schmidt`→`schmidt:ar`, `Decay`→`decay:ar`); SC strings untouched. `fft` (plain defun, control rate) → `fft:kr`.
- Emitter special shapes: `(progn (multinew...) N)` build-and-return-scalar (send-trig/rand-seed), `(sc::mul U X)` outside-multiply (lin-x-fade2), `(apply multinew ... (cons ...))` splice (tw-index), `(act X)`→`(ugen/act X)`, `(if a b c)` inputs (stepper/Delay-1), `as-audio-rate-input` (K2A lift preamble), 2-arg `(madd U mul)`, >20-param defns (variadic ctor + tail-as-keys, e.g. sid6581f/nes2/mi-elements), `&key` → `& {:keys :or}`.
- DIVERGENCE (documented, kept): upstream `klang`/`klank` inherit `num-outputs` 1 from plain `ugen` — a 2-partial Klang def wires 1 channel (first partial only on SC). Ours voices N channels (SC semantics) via multiout-new. No CL golden for klang; structural test pins 8 inputs / 2 channels.
- `pv-chain-ugen`/`dugen`/etc. construct as plain UGen; width-first topo ordering NOT yet enforced (local-buf/pv feedback chains sort by construction order — RISK row).
- Generated files are emitter-owned: hand customs live after `;; ---- hand customs ----` (preserved on regen); `extra-requires`/`preambles` maps reproduce ns forms/helpers.
- Status: decided
### 2026-10-05 — Volume + In/Out correctness (multiout dispatch, splice, upstream bit-rot)
- `construct` matched `:multiout` but callers pass upstream class `'multiout-ugen` — In/DC built as plain UGen (1 output declared, N consumed) and the corrupt def **killed scsynth on start**. Dispatch widened; 8ch volume now byte-identical.
- Out/XOut splice channels into ONE unit (upstream `apply`-multinew), not paired units. `out:ar` with a 2ch sig is `[Control SinOsc SinOsc Out]` per CL. `x-out`/`out` return scalar 0 (play keys its fade tail off scalar).
- Upstream `volume.lisp` is bit-rotted: CL `*` on the In proxy list throws. Ours uses `ops/*` (`*~` semantics); golden replicates with `*~`. Upstream left pristine per principle.
- Status: decided
### 2026-10-05 — Golden gap closed: rate checks, Sum3/4 fusion, SBCL sort ties
- New goldens `cl-dsine`/`cl-varlag`/`cl-playtail` (8/8 graphs byte-identical now).
- Oracle caught 3 divergences: (1) our filters skipped `check-same-rate-as-first-input` — scalar into HPZ1/VarLag must throw at build, both sides now; (2) nested `+` must fuse to Sum3/Sum4 (`optimize-to-sum3/sum4` ported as `fuse-sums` in `build-synthdef`; `madd`/`add-neg`/`sub` fusion stays deferred); (3) `Impulse` is `:unipolar`, `hpz-1` takes mul/add.
- Sum input order: upstream sorts via CL `sort` (unstable) — SBCL reverses equal-rate keys (verified n=2..5, full + partial ties). Ours: `ugen/sort-by-rate-desc` (rate desc, ties reversed). Pinned to observed SBCL 2.2.9; other CLs may differ (upstream itself isn't stable here).
- Fusion skips zero-valued number inputs (upstream folds those through `add`; construction normally simplifies them first).
- Status: decided
### 2026-10-05 — Phase 5 defsynth/play + VarLag fidelity + int-address fix
- `synth/def.clj`: `defsynth` (vector + CL params, metadata at expansion), `synth`, `play` with upstream fade-EnvGen tail. `proxy`/`with-rendering` deferred (no NRT server yet).
- `var-lag` ported honestly: default warp builds the `Changed`/`Impulse`/`EnvGen` graph like `var-lag-new`, not a bare `VarLag` unit (only `:lin` warp emits the unit). Requires `Env` builder + `make-env-array-from-env` in `ugens/env_gen.clj`; arrays are an opaque `EnvArray` record so `multinew` won't expand them (CL arrays are atoms to `lst-operation`).
- `process-env` splice must happen BEFORE `add-to-synth`: our records are values, upstream mutates in place (stale-copy bug caught live).
- `tx/send-message` stringified integer command ids (`9` → `"9"`, SC `/fail Command not found`). Now passes addr raw; `sc-encode-address` already handles ints. Regression test `int-address-reaches-wire`.
- Status: decided

### 2026-10-04 — DCE + true unit inputs (SBCL goldens)
- Upstream `defugen` wraps units in `madd` (real `SinOsc` has 2 inputs, `Saw` 1); our hand-written ugens stuffed mul/add as unit inputs (invalid 4-input `SinOsc`). Fixed to `madd` semantics via `mul-add`.
- `build-synthdef` now dead-code-eliminates unconsumed `pure-ugen`/`unary`/`binary` nodes (fixpoint = upstream recursion); plain/control/output kept. Verified: 5/5 graphs byte-identical to SBCL output (`sine reorder ctl groups diamond`).
- Goldens are CL-generated (`scripts/gen_goldens.lisp`); self-generated `sine.scsyndef` retired.
- Status: decided
### 2026-10-04 — Phase 4 server divergences (verified live vs scsynth)
- `sync`: per-call `CompletableFuture` + 5s warn-timeout, not per-thread semaphores (R8: no deadlock; upstream blocks forever). Sync-id map per-server, not CLASS-shared.
- Handler-map keys normalized to `int` (`Integer` vs `Long` hash-miss on `[path bufnum]` vectors).
- Alloc fns return the post-`/b_info` vector entry (records are values; upstream mutates in place).
- Completion blobs must be raw message bytes — `sc-encode-data` blob-wraps `byte[]` itself (double-blob silently drops the completion server-side).
- `buffer-copy` sends `"/b_gen"`: upstream literal `"\b_gen"` is a backspace escape (real bug, fixed).
- Single `server.clj` (not the 4-file split): avoids a server↔node require cycle, mirrors `server.lisp`.
- Deferred: NRT/`with-rendering` (Phase 5), `volume.clj` + `lag.*` wrapping (needs Phase-6 Lag/XOut), buffer fill/sequences/wavetable, handler `/done` async paths untested live.
- Status: decided
### 2026-10-04 — Operator `~` family — overload core arithmetic
- Options: A star suffix (`+*`, `abs*`), B overload `+ - * / < > <= >=` (+ dispatching `min max abs mod inc dec`), C plain words only
- Pick: **B overload** per user
- Why: `~` is the Clojure unquote reader macro — `+~` reads as `+` + `(unquote …)`, proven at read time ("Unmatched delimiter"). No spelling workaround exists (digit-leading `1+~` was already `inc`/`dec`-bound). Overloads read like sclang and dissolve the convert-code arithmetic table; overtone precedent (ugen-collide).
- Details: all-numbers take clojure.core paths with identical values/arities (`(+)→0`, `(*)→1`); any-ugen builds Unary/BinaryOpUGen. Free names drop the tilde (`sin floor expt ash round trunc`); `== << >> <!` were legal; `if~`→`if*` (`if` is special). `convert-code` table ships empty (mechanism kept for aliases).
- Affects: `synth/operators.clj`, `synth/convert.clj`, `ARCHITECTURE.md` §4.2, `PLAN.md` §1-H/§3
- Status: decided
### 2026-10-02 — UGen `.ar/.kr` naming — colon suffix
- Options: A dash `sin-osc-ar`, B colon `sin-osc:ar`, C single-fn `sin-osc :ar`, D ns-per-ugen `sin-osc/ar`
- Pick: **B `sin-osc:ar`** per user
- Why: verified `(defn sin-osc:ar [x] x)` → `user$sin_osc_COLON_ar` works; `sin-osc.ar`→ClassNotFound, `sin-osc/ar`→simple-symbol? fail. Keeps CL dot-visual.
- Affects: `synth/ugen.clj`, all `ugens/*.clj`, `MAPPING.md` 22–79, `PLAN.md` §2.2
- Status: decided

### 2026-10-02 — OSC implementation — hand-roll ByteBuffer
- Options: A hand-roll `ByteBuffer` (~300 LOC), B dep `clj-osc` (`rosejn/osc-clj`/`overtone/osc-clj` 0.1.1, OSC 1.0 UDP, 2013)
- Pick: **A hand-roll** per user (Q2)
- Why: byte-identical parity for golden tests, no stale dep, verifiable vs SC spec.
- Affects: `osc/encode.clj`, `osc/transmit.clj`
- Status: decided

### 2026-10-02 — defsynth param shape — Overtone vector (primary) + CL compat
- Options: A CL positional `((freq 440))`, B Overtone vector `[freq 440]`, C map `{:freq 440}`
- Pick: **B Overtone vector as primary, A kept as compat**
- Why: per user Q3 — `(defsynth name [freq 440 amp 0.5] …)` gives names+defaults at creation and runtime `ctl`, as in `overtone/sc/synth.clj:607` `parse-params`/`mapify-params`/`control-proxies`. Supports `[freq [440 :ar]]` and `[freq {:default 440 :rate :ar}]` for rates. CL positional still parsed for verbatim `../cl-collider` examples.
- Forms: `(defsynth foo [freq 440] (out:ar 0 (sin-osc:ar freq)))`, `(defsynth foo "doc" [freq 440] …)`, `(defsynth foo ((freq 440)) …)` — dispatched by `(vector? first-arg)` vs `(seq? first-arg)`.
- Affects: `synth/control.clj`, `synth/def.clj`, `PLAN.md` §3 Phase 3/5
- Status: decided

### 2026-10-02 — Scheduler queue — PriorityBlockingQueue
- Options: A `PriorityBlockingQueue`, B `data.priority-map` + lock
- Pick: **A for runtime (PBQ), B for pure tests** — per user Q4 rec
- Why: PBQ is thread-safe like CL `pileup:heap`; priority-map for deterministic ordering tests without threads.
- Affects: `server/scheduler.clj`
- Status: decided

### 2026-10-02 — Module boundaries — one ns per CL file
- Options: A one ns per CL file (faithful), B merged `synth` ns (idiomatic)
- Pick: **A one ns per CL file**, re-export via `core.clj`
- Why: keeps `MAPPING.md` 1:1 and diff vs `../cl-collider` readable.
- Affects: `ARCHITECTURE.md` §2, `MAPPING.md`
- Status: decided (flag if you want B)

### 2026-10-02 — Auto-silence — opt-in headless only
- Options: A always silent, B never silent, C auto-detect headless Linux
- Pick: **C + `*auto-silence?*` var (nil = auto, false/true = override)**
- Why: per user — Mac must never be muted unless explicitly requested; Linux headless (aplay no soundcards) auto-silences via jack dummy + mute. `(binding [*auto-silence?* false] ...)` forces audible on Linux, `(binding [*auto-silence?* true] ...)` forces silent on Mac for CI.
- Impl: `server/silent.clj` — `macos?` → never auto, `headless-linux?` checks `aplay -l`, `silent-boot-args` returns `{:device "jack" :mute true}` only when needed.
- Affects: `server/silent.clj`, `server/external.clj`, `PLAN.md` §4
- Status: decided
