# Decisions

Record every non-trivial choice here. Newest on top.

## Open

_(none — all initial decisions locked 2026-10-02)_

  ## Decided
 ### 2026-11-08 — `panic!` (immediate all-silence)
 - `p/panic!` routes through `server/panic-all!` (live servers + registry-port spray; order spray-first is now moot). Kill command is `/g_deepFree 0`, not `/g_freeAll`: deepFree kills synths but leaves groups intact, so the group-1 rebuild + /sync is gone. `(server/panic-port! 57120)` for foreign-JVM servers; beyond that `killall scsynth`.
 - Status: decided
 ### 2026-11-07 — `thursday_afternoon` + `music_for_airports` reconstructions
 - `thursday_afternoon.clj`: G dom7 + C (article), voice/detail from pitch-detecting the article's own sample pack (8 DX7 sines G3-D5, 7 piano notes B3-D5, G-triad drone), loop times 16-19.3s with +-0.6s per-repeat jitter (the article's range; exact per-loop times unpublished). DX7 voice = single sine w/ 2.11s decay; piano = additive plink. Amps set by NRT stress (peak 0.28).
 - `music_for_airports.clj` (tracks `2/1`, `1/2`; `1/1` omitted - no reliable note data): `2/1` = 7 sung loops (Ab4 F4 C4 Eb4 Db4 Ab3 F3, pitch-detected from the article's Choir loops; times from teropa's independent reconstruction, whose octave choices differ - documented in-file), choir voice = layered sines + slow attack; `1/2` = 8 piano phrases from onset-segmented pitch detection of the article's Piano loops (D major w/ C naturals, Mixolydian colour as the article says). Peaks 0.40/0.48, no clipping.
 - Status: decided
 ### 2026-11-07 — bounce schedules its own gate-offs (NRT has no clock)
 - Long `discreet_music` bounces died with `CombN_Ctor: alloc failed` from ~t=141 and rendered a too-loud wash before that: the backend releases voices via `server/callback`, which needs a running scheduler clock - dead in NRT - so voices sustained forever, leaking RT memory per voice. `bounce` now `at`s an explicit `gate 0` per gated voice in score time (same `sus/bps` math as the backend). 180s re-render: zero alloc failures, peak 0.22, steady RMS throughout. Any future NRT path must do the same - `server/callback` is live-only.
 - Status: decided
 ### 2026-11-07 — `discreet_music bounce` + backend tempo fix
 - `discreet_music bounce SECS OUT` renders deterministically (no wall clock): pulls both loops via `play-take`, replays events at absolute 120bpm times through a backend inside `with-rendering`, loop synth with the same 10s fade-out. Pad doubles as the score cutoff, so it spans `secs + 12`. Verified: 30s bounce renders 42.0s, peak 0.31, fade-in swell then 20-30s fade to digital silence; `ffmpeg -y -i OUT -codec:a libmp3lame -qscale:a 2 OUT.mp3` (or macOS `afconvert -f MPG3 -d '.mp3'`) produces the mp3.
 - Found while building it: the pattern backend computed gate release from event `:sustain` (beats) assuming 60bpm (`:tempo` default 1, never set anywhere), so every non-60 rig released late (2x at 120, 1.5x at jazz's 90). `step-body!` now assocs the clock's beats/sec into each event. Affects all pattern playback for the better; suite green.
 - Status: decided
 ### 2026-11-07 — cleanup resets boot?; prune needs a live device; send-bundle guard
 - The re-run NPE survived the first prune: `server-quit` clears `boot?` only via the `/quit` reply, so a lost reply (easy on a wedged-Bluetooth box past the 5s poll) leaves `boot?` true on a device-less server, and `boot?`-only pruning keeps the corpse. Three layers: (1) `cleanup-server` now resets `boot?` false - the process is destroyed there in all paths, so true-after-cleanup was always a lie; (2) prune requires a booted server AND a `:running` OSC device; (3) `send-bundle` throws the same named "no OSC device" error `send-message` has instead of an `intCast` NPE. `clock-rig-test` simulates the lost reply (`boot?` forced true post-quit) and asserts the prune.
 - Status: decided
 ### 2026-11-07 — `discreet_music` in Ab major, teropa rhythms
 - Example `discreet_music.clj` implements the reverbmachine recipe (Synthi voice: sine + touch of saw, pitch-tracking vibrato, dark LPF, slow attack; 160ms Echoplex comb per voice; 5.65s Frippertronics loop w/ feedback + glue compander + room). Key is Ab major per the article (right loop outlines Ab-C-Eb + 6th F; left has the Db-vs-C rub + four-note closing motif); the two published sources disagree on absolute key (teropa's transcription sits in C-ish), so rhythms/loop lengths (68s/74s) follow teropa exactly while pitch classes follow the article. Half-speed lore is baked in - everything runs at final-record speed. NRT-verified peak 0.17, no clipping.
 - Status: decided
 ### 2026-11-07 — prune dead backends on rig setup (REPL re-run NPE)
 - Re-running a rig twice in one REPL NPEd every pattern step: `start-clock-loop` reuses the existing clock, so the dead first backend (device closed on quit → nil port → `intCast` NPE in `send-bundle`) ran before the live one and the `doseq` never reached it - total silence on run 2+. `prune-dead-backends!` now drops backends whose server has quit in both `setup-performance!` and `connect-server!` (server-less backends like DebugBackend are kept). Regression test `clock-rig-test` boots/quits/reboots and asserts a single backend bound to the live server.
 - Status: decided
 ### 2026-11-07 — boot failures carry scsynth's log; port pre-check
 - `server-boot` used to throw bare "server failed to boot" while the reason scrolled past in the drain thread. The drain now keeps a 40-line ring per server (`boot-logs`, cleared on cleanup); both failure throws append the last 10 lines as "scsynth said:" and put them in ex-data `:scsynth-log` (inspect via `(ex-data *e)`).
 - Found via a port-clash test: a second boot on a taken port "succeeded" (boot? true, no process) because the old server answers the new one's /sync (UDP cross-talk). `server-boot` now probes the port with a `DatagramSocket` first (skipped for `:just-connect?`) and throws "port N already in use"; a post-poll liveness check also rejects a process that died mid-boot. Residual hole (process dying after a slow init while cross-talk answers) is accepted, not papered over with boot-delaying sleeps.
 - Status: decided
 ### 2026-11-07 — scheduler error traces + guarded gate-0
 - `scheduler/run-tasks` printed only `.getMessage` (e.g. a bare NPE "in scheduler thread" with no origin). Now prints exception class + message + thread name + full stack trace. No tests depended on the one-line format.
 - `backend/play-event`'s scheduled gate-0 could hit an already-freed voice (`FAILURE IN SERVER /n_set Node N not found`, reproduced locally on the first voice). The callback now skips when `server/is-playing?` (node-watcher `/n_end`) is false; a residual check-then-send race stays loud. Verified: jazz re-run shows only the benign `late` line.
 - Status: decided
 ### 2026-11-07 — output-only scsynth by default (`-i 0`), `:input?` opt-in
 - `options/defaults` sets `:num-input-bus 0`: scsynth opens no input device, so rate-mismatched inputs (Bluetooth headset mics) can't fail boot. No in-repo SoundIn/hardware-input users (only internal-bus `in:ar`), so nothing breaks. Opt in with `{:num-input-bus 8}` at `make-external-server`, or `:input? true` on `setup-performance!`/`connect-server!` (both assoc 8 before boot).
 - Trap found en route: upstream's `:input-stream-enabled`/`:output-stream-enabled` (`-I`/`-O`) are NOT real scsynth flags (verified `scsynth -h`, 3.13.0) — emitting `-I 0` kills boot. The `-i` channel count is the mechanism. Fields stay for upstream parity but must remain nil.
 - Status: decided
 ### 2026-11-05 — `connect-server!` boot/clock/backend one-call
 - `patterns.clock/connect-server!` takes an existing server S: boots it, starts the clock at TEMPO (default 90), enables a `ClColliderBackend` on S, returns S for the binding form — `(binding [server/*s* (p/connect-server! s :tempo 90)] ...)`. Exported via the `pattern` facade. Takes S explicitly because the binding init-expr runs before `*s*` is bound. Explicit enable also suppresses `play`'s auto-backend (fires only with zero backends), so exactly one backend sounds. `examples/automatic_jazz.clj` is the first adopter (was 3 calls).
 - Status: decided
### 2026-10-23 — generic synthdef GUI + defsynth vector style
- All example `defsynth` vectors reflowed: first param shares the `[` line, one param per line after, last param shares the `]` line (single-param vectors unchanged).
- `examples/synth_gui.clj`: generic Seesaw panel for any synthdef — specs derived from `sdef/synthdef-metadata` (`:controls` pairs), range inference (positive d -> [0,2d], negative -> [2d,0], zero -> [-1,1], linear unless `:exp?` with lo > 0), per-key RANGES overrides, SKIP set (default #{:out}), PLAY/STOP lifecycle with fixed EXTRA-ARGS. `examples/drone_gui.clj` is the test seat: 15 sliders for `:drone-collider` (skips out/voiceid, exp attack/decay/fundamental/speed), verified live under xvfb.
- Status: decided

### 2026-10-23 — node-free prefers the node's own server + basic-waveforms example
- `node-free` resolved its server from `*s*`, which is unbound on threads like the Swing EDT: freeing a node from a Seesaw callback NPE'd inside `send-message` (nil server). Now `(or server (:server node) *s*)` — explicit arg still wins; plain ids/keywords fall through to `*s*` as before.
- `examples/basic_waveforms.clj`: Seesaw port of sculpt's Basic Waveforms Interface (macOS). Same synth (Select over off/sine/saw/pulse/tri under a retriggerable gate env), exp-mapped freq/amp/width sliders with text fields, waveform picker, LOAD/ON toggles, client-side preview canvas replacing the 12 plot buttons + scope + freqscope. Seesaw dep added to `deps.edn`.
- Status: decided

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
