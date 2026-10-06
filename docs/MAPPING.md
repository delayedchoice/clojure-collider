# CL → Clojure File Mapping

Status legend: `○ planned` · `◐ in progress` · `● done` · `— not porting` · `? needs decision`

| # | Upstream `cl-collider/*.lisp` | Target `clojure-collider/*` | Status | Notes |
|---|---|---|---|---|
| 1 | `package.lisp` | `src/clojure_collider/core.clj` + `src/sc.clj` | ● | Full facade (970 publics via generated explicit import-vars); `sc.clj` alias pending |
| 2 | `cl-collider.asd` | `deps.edn` + `build.clj` + `bb.edn` | ● | v0.1.0 jar+pom verified; `:deploy` alias (slipset/deps-deploy) ready, needs Clojars creds |
| 3 | `osc/package.lisp` | `src/clojure_collider/osc/package.clj` (ns decl) | ○ |  |
| 4 | `osc/sc-osc.asd` | _(merged into deps.edn)_ | ○ |  |
| 5 | `osc/osc.lisp` | `src/clojure_collider/osc/encode.clj` | ● | ByteBuffer, typetags `,ifsb[]`, blob padding, timetag `:now` |
| 6 | `osc/transmit.lisp` | `src/clojure_collider/osc/transmit.clj` | ● | DatagramSocket, responder table, listener thread (always-on default) |
| 7 | `osc/ecl-extension.lisp` | — | — | ECL-only; JVM has ByteBuffer |
| 8 | `osc/lw-extension.lisp` | — | — | LispWorks-only |
| 15 | `synthdef.lisp` | `src/clojure_collider/synth/synthdef.clj` + `control.clj` + `convert.clj` + `def.clj` | ◐ | Graph/encode/controls/convert/defsynth/synth/play done; `proxy`/`with-rendering` (NRT) pending |
| 10 | `util.lisp` | `src/clojure_collider/util.clj` | ○ | `[ ]` reader macro dropped; `cat`, `full-pathname`, `blend-nth`, `as-keyword` ported |
| 11 | `server-options.lisp` | `src/clojure_collider/server/options.clj` | ● | `defrecord ServerOptions` + `build-server-options` |
| 12 | `scheduler.lisp` | `src/clojure_collider/server/scheduler.clj` | ● | `Scheduler` + `TempoClock`, PBQ, ReentrantLock/Condition |
| 13 | `server.lisp` | `src/clojure_collider/server/server.clj` (core+external+node+NRT, single file) | ● | Boot/quit/sync/responders/nodes/NRT live-tested (AIFF FORM verified) |
| 14 | `ugen.lisp` | `src/clojure_collider/synth/ugen.clj` | ● | Rate lattice, UGen records, multinew, topo helpers, v2 encode |
| 15 | `synthdef.lisp` | `src/clojure_collider/synth/synthdef.clj` + `control.clj` + `convert.clj` + `def.clj` | ● | Graph/encode/controls/convert/defsynth/synth/play/proxy/proxy-ctrl live-tested; Sum3/4 fusion ported; `madd`/`add-neg`/`sub` fusion pending |
| 16 | `operators.lisp` | `src/clojure_collider/synth/operators.clj` | ● | Overloaded arithmetic + sum3/sum4 (SBCL tie-reversed order); other optimize-surgery deferred |
| 17 | `volume.lisp` | `src/clojure_collider/volume.clj` | ● | set-volume!/volume/mute/unmute live-tested; cl-volume golden |
| 18 | `buffer.lisp` | `src/clojure_collider/buffer.clj` | ● | Full port incl. set-sr/fill/sequences/wavetable/list+array/read-as-wavetable, all live-tested |
| 19 | `bus.lisp` | `src/clojure_collider/bus.clj` | ● | 67 LOC, explicit-server (no `*s*` default) |
| 20 | `slynk-extensions.lisp` | — | — | Editor glue; maybe `nrepl` later |
| 21 | `swank-extensions.lisp` | — | — | Same |
| 22 | `ugens/BEQSuite.lisp` | `src/clojure_collider/ugens/beq_suite.clj` | ● | Transpiled + b-lowpass4/b-hipass4 via b-pass4-new |
| 23 | `ugens/BufIO.lisp` | `src/clojure_collider/ugens/buf_io.clj` | ● | Transpiled + buf-wr/record-buf/scope-out/tgrains/tap customs |
| 24 | `ugens/Chaos.lisp` | `src/clojure_collider/ugens/chaos.clj` | ● | Transpiled (&key chaos family) |
| 25 | `ugens/Compander.lisp` | `src/clojure_collider/ugens/compander.clj` | ● | Transpiled + compander-d:ar |
| 26 | `ugens/Delays.lisp` | `src/clojure_collider/ugens/delays.clj` | ● | Transpiled + as-audio-rate-input (K2A lift) |
| 27 | `ugens/Demand.lisp` | `src/clojure_collider/ugens/demand.clj` | ● | Transpiled + demand/demand-envgen:ar customs |
| 28 | `ugens/DiskIO.lisp` | `src/clojure_collider/ugens/disk_io.clj` | ● | Transpiled + disk-out:ar |
| 29 | `ugens/EnvGen.lisp` | `src/clojure_collider/ugens/env_gen.clj` | ● | Env record, shape table, array expansion, env-gen:ar/kr, linen, done/free helpers; env-circle/env-delay deferred |
| 30 | `ugens/FFT.lisp` | `src/clojure_collider/ugens/fft.clj` | ● | Transpiled + 35 PV via def-pv-chain-ugen + fft:kr/running-sum-rms |
| 31 | `ugens/FFTUnpacking.lisp` | `src/clojure_collider/ugens/fft_unpacking.clj` | ● | Hand port (pv width-first noted) |
| 32 | `ugens/FSinOsc.lisp` | `src/clojure_collider/ugens/fsin_osc.clj` | ● | Transpiled + klang/klank/dyn customs (Klang multi-out: see DECISIONS) |
| 33 | `ugens/Filter.lisp` | `src/clojure_collider/ugens/filter.clj` | ● | Transpiled (72) + hpz-1/changed/var-lag fidelity ports |
| 34 | `ugens/FreeVerb.lisp` | `src/clojure_collider/ugens/free_verb.clj` | ● | Transpiled |
| 35 | `ugens/GVerb.lisp` | `src/clojure_collider/ugens/g_verb.clj` | ● | Transpiled |
| 36 | `ugens/Gendyn.lisp` | `src/clojure_collider/ugens/gendyn.clj` | ● | Transpiled |
| 37 | `ugens/GrainUGens.lisp` | `src/clojure_collider/ugens/grain_ugens.clj` | ● | Transpiled (&key grain family) |
| 38 | `ugens/Hilbert.lisp` | `src/clojure_collider/ugens/hilbert.clj` | ● | Transpiled + hilbert-fir:ar |
| 39 | `ugens/IEnvGen.lisp` | `src/clojure_collider/ugens/ienv_gen.clj` | ● | Hand port |
| 40 | `ugens/InOut.lisp` | `src/clojure_collider/ugens/in_out.clj` | ● | in/out/x-out + replace/offset/local-out (+checks, splice, zero-return) |
| 41 | `ugens/InfoUGens.lisp` | `src/clojure_collider/ugens/info_ugens.clj` | ● | Transpiled + 17 macro-generated info ugens |
| 42 | `ugens/Line.lisp` | `src/clojure_collider/ugens/line.clj` | ● | Transpiled (12) + dc/silent/line/x-line/k2a/lin-lin |
| 43 | `ugens/MacUGens.lisp` | `src/clojure_collider/ugens/mac_ugens.clj` | ● | Transpiled + mouse-x/mouse-y warp mapping |
| 44 | `ugens/MachineListening.lisp` | `src/clojure_collider/ugens/machine_listening.clj` | ● | Transpiled + onsets:kr |
| 45 | `ugens/MoogFF.lisp` | `src/clojure_collider/ugens/moog_ff.clj` | ● | Transpiled |
| 46 | `ugens/Noise.lisp` | `src/clojure_collider/ugens/noise.clj` | ● | Transpiled + dup-pattern noise customs + send-trig/rand progn shapes |
| 47 | `ugens/Osc.lisp` | `src/clojure_collider/ugens/osc.clj` | ● | Transpiled (43) + select family/t-choose/pm-osc |
| 48 | `ugens/PSinGrain.lisp` | `src/clojure_collider/ugens/psin_grain.clj` | ● | Transpiled |
| 49 | `ugens/Pan.lisp` | `src/clojure_collider/ugens/pan.clj` | ● | Transpiled + lin-x-fade2 (sc::mul outside) |
| 50 | `ugens/PartConv.lisp` | `src/clojure_collider/ugens/part_conv.clj` | ● | Transpiled |
| 51 | `ugens/PhysicalModel.lisp` | `src/clojure_collider/ugens/physical_model.clj` | ● | Transpiled |
| 52 | `ugens/PitchShift.lisp` | `src/clojure_collider/ugens/pitch_shift.clj` | ● | Transpiled (&key mul/add) |
| 53 | `ugens/Pluck.lisp` | `src/clojure_collider/ugens/pluck.clj` | ● | Transpiled (&key mul/add) |
| 54 | `ugens/Poll.lisp` | `src/clojure_collider/ugens/poll.clj` | ● | Hand port (impulse coercion, char-code labels) |
| 55 | `ugens/SoundIn.lisp` | `src/clojure_collider/ugens/sound_in.clj` | ● | Hand port (uses info-ugens num-output-buses) |
| 56 | `ugens/Splay.lisp` | `src/clojure_collider/ugens/splay.clj` | ● | Hand port (pan2 composition) |
| 57 | `ugens/TestUGens.lisp` | `src/clojure_collider/ugens/test_ugens.clj` | ● | Transpiled |
| 58 | `ugens/Trig.lisp` | `src/clojure_collider/ugens/trig.clj` | ● | Transpiled + fold/clip/wrap:ir + send-reply/send-peak-rms |
| 59 | `ugens/SC3plugins/BhobUGens.lisp` | `src/clojure_collider/ugens/sc3plugins/bhob_ugens.clj` | ● | Transpiled + tgrains2/3 customs |
| 60 | `ugens/SC3plugins/DEINDUGens.lisp` | `src/clojure_collider/ugens/sc3plugins/deind_ugens.clj` | ● | Hand port (greyhole/jpverb stereo pairs) |
| 61 | `ugens/SC3plugins/DistortionPlugins.lisp` | `src/clojure_collider/ugens/sc3plugins/distortion_plugins.clj` | ● | Transpiled |
| 62 | `ugens/SC3plugins/JoshUGens.lisp` | `src/clojure_collider/ugens/sc3plugins/josh_ugens.clj` | ● | Transpiled |
| 63 | `ugens/SC3plugins/LadspaUGen.lisp` | `src/clojure_collider/ugens/sc3plugins/ladspa_ugen.clj` | ● | Hand port |
| 64 | `ugens/SC3plugins/MCLDUGens.lisp` | `src/clojure_collider/ugens/sc3plugins/mcld_ugens.clj` | ● | Transpiled + array-max/min |
| 65 | `ugens/SC3plugins/MdaUGens.lisp` | `src/clojure_collider/ugens/sc3plugins/mda_ugens.clj` | ● | Transpiled |
| 66 | `ugens/SC3plugins/PitchDetection.lisp` | `src/clojure_collider/ugens/sc3plugins/pitch_detection.clj` | ● | Transpiled |
| 67 | `ugens/SC3plugins/SLUGens.lisp` | `src/clojure_collider/ugens/sc3plugins/sl_ugens.clj` | ● | Transpiled |
| 68 | `ugens/SC3plugins/NHUGens.lisp` | `src/clojure_collider/ugens/sc3plugins/nh_ugens.clj` | ● | Hand port (nhhall elt inputs) |
| 69 | `ugens/SC3plugins/TJUGens.lisp` | `src/clojure_collider/ugens/sc3plugins/tj_ugens.clj` | ● | Transpiled |
| 70 | `ugens/quarks/miSCellaneous_lib/WaveFolding.lisp` | `src/clojure_collider/ugens/quarks/wave_folding.clj` | ● | Hand port (smooth-clip/fold family) |
| 71 | `ugens/quarks/redSys/RedImpulse.lisp` | `src/clojure_collider/ugens/quarks/red_impulse.clj` | ● | Hand port |
| 72 | `ugens/Extensions/mi-UGens.lisp` | `src/clojure_collider/ugens/extensions/mi_ugens.clj` | ● | Transpiled (>20-param shapes) + mi-clouds/mi-verb checks |
| 73 | `ugens/Extensions/f0plugins.lisp` | `src/clojure_collider/ugens/extensions/f0plugins.clj` | ● | Transpiled (26-param sid6581f) + nes2-square/pokey helpers |
| 74 | `ugens/Extensions/DynGen.lisp` | `src/clojure_collider/ugens/extensions/dyn_gen.clj` | ● | Hand port (registry; server-delivery helpers deferred) |
| 75 | `ugens/Extensions/IBufWr.lisp` | `src/clojure_collider/ugens/extensions/i_buf_wr.clj` | ● | Hand port |
| 76 | `ugens/Extensions/PortedPlugins.lisp` | `src/clojure_collider/ugens/extensions/ported_plugins.clj` | ● | Transpiled + chen/lpg/rongs checks + harmonic-osc |
| 77 | `ugens/Extensions/safety-limiter.lisp` | `src/clojure_collider/ugens/extensions/safety_limiter.clj` | ● | Hand port |
| 78 | `ugens/Extensions/sc_faust.lisp` | `src/clojure_collider/ugens/extensions/sc_faust.clj` | ● | Hand port (registry; delivery helpers deferred) |
| 79 | `ugens/Extensions/CDSkip.lisp` | `src/clojure_collider/ugens/extensions/cd_skip.clj` | ● | Hand port (t2a/k2a trigger lifts) |

## Export list to preserve

From `package.lisp` (216 symbols): `*s*`, `*sc-synth-program*`, `*sc-plugin-paths*`, `*sc-synthdefs-path*`, `+inf+`, `sched-ahead`/`latency`, `make-server-options`, `*synth-definition-mode*`, `defsynth`/`synth`/`get-synthdef-metadata`/`with-controls`/`named-control`/`reblock`/`resample`/`kr`/`play`/`proxy`, `with-rendering`, `make-external-server`/`server-boot`/`server-quit`/`boot-p`/`send-message`/`send-bundle`/`osc-trace`, `control-get`/`control-set`/`add-reply-responder`/`with-async`/`sync`, `volume`/`mute`/`unmute`, `at`/`free`/`release`/`ctrl`/`map-bus`/`is-playing-p`/`on-free`/`move-node`/`make-group`/`stop`/`server-status`, `now`/`callback`/`quant`/`clock-*`/`beat-dur`, `bufnum`/`buffer-*`/`local-buf`, `bus-audio`/`bus-control`, operators (`neg` … `exp-range`), `env`/`fft` etc. All must appear in `core.clj` re-exports.

## Test resources to generate

- `test/resources/golden/cl-*.scsyndef` — CL-generated (`scripts/gen_goldens.lisp` on SBCL): `sine reorder ctl groups diamond dsine varlag playtail volume select demand`. Bytes assert `encode-synthdef` parity. (`klang` intentionally NOT golden: upstream emits 1 output, ours voices 2 — see DECISIONS.)
- `test/resources/osc/*.bin` — hand-captured OSC messages (`/s_new`, `/n_set`, `/b_alloc`, bundles) for encode tests.
