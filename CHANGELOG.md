# Changelog

## v0.1.0 — 2026-10-06

Initial release: full port of `cl-collider` (2025.8.21) to idiomatic Clojure (JVM).

- OSC: hand-rolled `ByteBuffer` codec (typetags `,ifsb[]`, blobs, `:now` timetags) + `DatagramSocket` device with responder table and listener thread.
- UGen core: rate lattice, records, `multinew` multichannel expansion, topo sort, byte-identical SynthDef v2 encoding (11 CL-generated golden files).
- DSL: `defsynth` (Overtone vector + CL compat params), `synth`, `play`, `proxy`/`proxy-ctrl`, `with-controls`, `convert-code`, overloaded arithmetic (`sin-osc:ar` colon naming).
- Server: external boot/quit/sync/responders/nodes/groups, scheduler + tempo clock, `NrtServer` + `with-rendering` offline AIFF render.
- Library: 846 UGen fns across ~60 namespaces (37 core files + SC3plugins + Extensions + quarks), re-exported from `clojure-collider.core` (970 publics).
- Buffers/buses/volume: full buffer surface (alloc/fill/sequences/wavetable/list+array), audio+control buses, master volume.
- Verified: 95 tests / 405 assertions green, clj-kondo 0/0, live `scsynth` 3.13.

Known divergences (see `docs/DECISIONS.md`): `/b_setSampleRate` is not implemented by scsynth 3.13 (sent faithfully, no effect); `klang` voices 2 outputs vs upstream 1; empty `-U` plugin flag is omitted (it hides core UGens); headerless `/b_write` dumps read little-endian.
