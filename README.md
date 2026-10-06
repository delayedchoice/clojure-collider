# clojure-collider

A Clojure port of [cl-collider](https://github.com/byulparan/cl-collider) — a SuperCollider (`scsynth`) client. Original is ~7.6 kLOC Common Lisp + ~3 kLOC UGen definitions; this port aims for idiomatic Clojure on the JVM (Clojure CLI / `deps.edn` + `tools.build`).

**Source being ported:** `../cl-collider` (commit `master` 2025-08-21, ASD `cl-collider 2025.8.21`, underlying `sc-osc`).

**Artifacts for the port live here** — `docs/`, `src/`, `test/`, `examples/`, `plans/` — so the upstream `../cl-collider` stays untouched and diff-able.

## Quick links

- [Plan — phased port roadmap](docs/PLAN.md) ← start here
- [Architecture — subsystems & Clojure mapping](docs/ARCHITECTURE.md)
- [CL → Clojure mapping — every file, symbol, decision](docs/MAPPING.md)
- [Decisions log](docs/DECISIONS.md) · [Risk register](docs/RISK-REGISTER.md)

## Status

v0.1.0. Full upstream port: `clojure -M:test` green (95 tests / 405 assertions), 11 CL-parity goldens, 846-fn UGen library (`clojure-collider.ugens`), live `scsynth` verified (play, proxy, NRT render, FFT chains, volume, buffer fill/sequences/wavetable). See `docs/PLAN.md` for the roadmap.

## Install

```clojure
;; deps.edn
bobby/clojure-collider {:mvn/version "0.1.0"}
```

Requires SuperCollider `scsynth` on `PATH` at runtime (same as upstream).

## Release

```bash
clojure -T:build jar   # -> target/clojure-collider-0.1.0.jar
# needs CLOJARS_USERNAME + CLOJARS_PASSWORD (or CLOJARS_DEPLOY_TOKEN):
clojure -M:deploy -m deps-deploy.deps-deploy deploy target/clojure-collider-0.1.0.jar
```

## Quick start

```clojure
(require '[clojure-collider.server.server :as server]
         '[clojure-collider.synth.def :as def]
         '[clojure-collider.ugens.osc :as osc])
(let [s (server/make-external-server "demo" :port 57110)]
  (server/server-boot s)
  (binding [server/*s* s]
    (let [n (def/play (osc/sin-osc:ar [440 441] 0 0.2 0))]
      (Thread/sleep 1500)
      (server/node-free s (:id n))))
  (server/server-quit s))
```

Or run the runnable scripts: `clojure -M examples/sine.clj`,
`examples/synthdef.clj`, `examples/melody.clj` (each boots its own server).

## API map

| Use | Namespace |
|---|---|
| Everything (facade) | `clojure-collider.core` (require as `sc`) |
| All 800+ UGen fns | `clojure-collider.ugens` |
| Per-file UGens | `clojure-collider.ugens.osc`, `.filter`, `.delays`, … (one ns per upstream file; `sc3plugins.*`, `extensions.*`, `quarks.*` for extras) |
| `defsynth`/`synth`/`play` | `clojure-collider.synth.def` |
| Server, nodes, sync | `clojure-collider.server.server` |
| Scheduler, clocks | `clojure-collider.server.scheduler` |
| Buffers, buses, volume | `clojure-collider.buffer`, `.bus`, `clojure-collider.volume` |

UGen naming: colon rate suffix (`sin-osc:ar`, `lpf:kr`, `sample-rate:ir`); `&optional` trailing args cascade, `&key` becomes `& {:keys :or}` (see `docs/DECISIONS.md`).

## Prereqs

- JDK 21 (you have 21.0.12), Clojure CLI 1.12.4, Babashka 1.12.217 (optional, for scripts)
- SuperCollider `scsynth` on `PATH` (same requirement as upstream)
- Linux/JACK optional — `jack-connect` will be a no-op on macOS/Windows

## Try the upstream first (sanity check)

```bash
# in ../cl-collider with SBCL + Quicklisp:
# (ql:quickload :cl-collider) (in-package :sc-user) (setf *s* (make-external-server "localhost" :port 57110)) (server-boot *s*) (play (sin-osc.ar [440 441] 0 0.2))
```
## Layout

```
clojure-collider/
  deps.edn / build.clj
  docs/           # all planning & design docs
  src/clojure_collider/
    core.clj                 # facade (require as sc)
    osc/{encode,transmit}.clj
    server/{options,scheduler,server,silent}.clj
    synth/{ugen,synthdef,control,convert,def,operators}.clj
    ugens/*.clj              # ~60 namespaces, one per upstream file
    buffer.clj  bus.clj  volume.clj
  test/clojure_collider/
  examples/
```

## Contributing / upstream parity

Track parity against `../cl-collider` via `docs/MAPPING.md`. Every upstream `.lisp` gets a row and a status. No behaviour change without a `DECISIONS.md` entry.
