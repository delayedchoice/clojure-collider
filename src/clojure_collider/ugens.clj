(ns clojure-collider.ugens
  "UGen library facade. Re-exports every public UGen fn from the per-file
   namespaces (one ns per upstream ugens/*.lisp), so a single require
   exposes the whole library. Phase 6 Done criterion: ns-publics >= 216."
  (:require
            [clojure-collider.ugens.beq-suite]
            [clojure-collider.ugens.buf-io]
            [clojure-collider.ugens.chaos]
            [clojure-collider.ugens.compander]
            [clojure-collider.ugens.delays]
            [clojure-collider.ugens.demand]
            [clojure-collider.ugens.disk-io]
            [clojure-collider.ugens.env-gen]
            [clojure-collider.ugens.extensions.cd-skip]
            [clojure-collider.ugens.extensions.dyn-gen]
            [clojure-collider.ugens.extensions.f0plugins]
            [clojure-collider.ugens.extensions.i-buf-wr]
            [clojure-collider.ugens.extensions.mi-ugens]
            [clojure-collider.ugens.extensions.ported-plugins]
            [clojure-collider.ugens.extensions.safety-limiter]
            [clojure-collider.ugens.extensions.sc-faust]
            [clojure-collider.ugens.fft-unpacking]
            [clojure-collider.ugens.fft]
            [clojure-collider.ugens.filter]
            [clojure-collider.ugens.free-verb]
            [clojure-collider.ugens.fsin-osc]
            [clojure-collider.ugens.g-verb]
            [clojure-collider.ugens.gendyn]
            [clojure-collider.ugens.grain-ugens]
            [clojure-collider.ugens.hilbert]
            [clojure-collider.ugens.ienv-gen]
            [clojure-collider.ugens.in-out]
            [clojure-collider.ugens.info-ugens]
            [clojure-collider.ugens.line]
            [clojure-collider.ugens.mac-ugens]
            [clojure-collider.ugens.machine-listening]
            [clojure-collider.ugens.moog-ff]
            [clojure-collider.ugens.noise]
            [clojure-collider.ugens.osc]
            [clojure-collider.ugens.pan]
            [clojure-collider.ugens.part-conv]
            [clojure-collider.ugens.physical-model]
            [clojure-collider.ugens.pitch-shift]
            [clojure-collider.ugens.pluck]
            [clojure-collider.ugens.poll]
            [clojure-collider.ugens.psin-grain]
            [clojure-collider.ugens.quarks.red-impulse]
            [clojure-collider.ugens.quarks.wave-folding]
            [clojure-collider.ugens.sc3plugins.bhob-ugens]
            [clojure-collider.ugens.sc3plugins.deind-ugens]
            [clojure-collider.ugens.sc3plugins.distortion-plugins]
            [clojure-collider.ugens.sc3plugins.josh-ugens]
            [clojure-collider.ugens.sc3plugins.ladspa-ugen]
            [clojure-collider.ugens.sc3plugins.mcld-ugens]
            [clojure-collider.ugens.sc3plugins.mda-ugens]
            [clojure-collider.ugens.sc3plugins.nh-ugens]
            [clojure-collider.ugens.sc3plugins.pitch-detection]
            [clojure-collider.ugens.sc3plugins.sl-ugens]
            [clojure-collider.ugens.sc3plugins.tj-ugens]
            [clojure-collider.ugens.sound-in]
            [clojure-collider.ugens.splay]
            [clojure-collider.ugens.test-ugens]
            [clojure-collider.ugens.trig]
))

(defmacro import-vars
  "Copy public vars (fns) from another ns, preserving metadata."
  [from-ns & syms]
  `(do ~@(map (fn [s#]
                `(let [src# (var ~(symbol (str from-ns "/" s#)))
                       dst# (intern *ns* '~s# @src#)]
                   (alter-meta! dst# merge (dissoc (meta src#) :name :ns))
                   dst#))
              syms)))

;; Explicit re-exports (regenerate the body with scripts/gen_facade.clj).
(import-vars clojure-collider.ugens.beq-suite
  b-allpass:ar b-bandpass:ar b-bandstop:ar b-hipass4:ar b-hipass:ar b-hishelf:ar b-lowpass4:ar b-lowpass:ar
  b-lowshelf:ar b-peak-eq:ar)
(import-vars clojure-collider.ugens.buf-io
  buf-rd:ar buf-rd:kr buf-wr:ar buf-wr:kr play-buf:ar play-buf:kr record-buf:ar record-buf:kr
  scope-out2:ar scope-out2:kr scope-out:ar scope-out:kr tap:ar tgrains:ar)
(import-vars clojure-collider.ugens.chaos
  cusp-l:ar cusp-n:ar fbsine-c:ar fbsine-l:ar fbsine-n:ar gbman-l:ar gbman-n:ar henon-c:ar
  henon-l:ar henon-n:ar latoocarfian-c:ar latoocarfian-l:ar latoocarfian-n:ar lincong-c:ar lincong-l:ar lincong-n:ar
  lorenz-l:ar quad-c:ar quad-l:ar quad-n:ar standard-l:ar standard-n:ar)
(import-vars clojure-collider.ugens.compander
  amplitude:ar amplitude:kr compander-d:ar compander:ar limiter:ar normalizer:ar)
(import-vars clojure-collider.ugens.delays
  allpass-c:ar allpass-c:kr allpass-l:ar allpass-l:kr allpass-n:ar allpass-n:kr as-audio-rate-input buf-allpass-c:ar
  buf-allpass-l:ar buf-allpass-n:ar buf-comb-c:ar buf-comb-l:ar buf-comb-n:ar buf-delay-c:ar buf-delay-c:kr buf-delay-l:ar
  buf-delay-l:kr buf-delay-n:ar buf-delay-n:kr comb-c:ar comb-c:kr comb-l:ar comb-l:kr comb-n:ar
  comb-n:kr delay-1:ar delay-1:kr delay-2:ar delay-2:kr delay-c:ar delay-c:kr delay-l:ar
  delay-l:kr delay-n:ar delay-n:kr deltap-rd:ar deltap-rd:kr deltap-wr:ar deltap-wr:kr)
(import-vars clojure-collider.ugens.demand
  demand-envgen:ar demand-envgen:kr demand:ar demand:kr duty:ar duty:kr t-duty:ar t-duty:kr)
(import-vars clojure-collider.ugens.disk-io
  disk-in:ar disk-out:ar v-disk-in:ar)
(import-vars clojure-collider.ugens.env-gen
  ->Env ->EnvArray done:kr env env-adsr env-asr env-dadsr env-gen:ar
  env-gen:kr env-linen env-perc env-shape-number env-sine env-triangle free-self-when-done:kr free-self:kr
  free:kr linen:kr make-env-array-from-env map->Env map->EnvArray pause-self-when-done:kr pause-self:kr pause:kr
  process-env puase:kr shape-table)
(import-vars clojure-collider.ugens.extensions.cd-skip
  cd-skip:ar)
(import-vars clojure-collider.ugens.extensions.dyn-gen
  dyn-gen-all dyn-gen:ar)
(import-vars clojure-collider.ugens.extensions.f0plugins
  astrocade:ar atari2600:ar ay8910-square:ar ay8910:ar beep:ar mz-pokey:ar nes2-dmc:ar nes2-noise:ar
  nes2-square:ar nes2-triangle:ar nes2:ar pokey-square:ar pokey:ar red-dpcm-decode:ar red-dpcm-decode:kr red-dpcm-encode:ar
  red-dpcm-encode:kr red-lbyl:ar red-lbyl:kr red-noise:ar red-noise:kr red-phasor2:ar red-phasor2:kr red-phasor:ar
  red-phasor:kr sid6581f:ar slub:ar slub:kr sn76489:ar t-bjorklund:ar t-bjorklund:kr waveset-repeater:ar
  waveset-repeater:kr)
(import-vars clojure-collider.ugens.extensions.i-buf-wr
  ibuf-wr:ar ibuf-wr:kr)
(import-vars clojure-collider.ugens.extensions.mi-ugens
  mi-braids:ar mi-clouds:ar mi-elements:ar mi-grids:ar mi-mu:ar mi-omi:ar mi-plaits:ar mi-rings:ar
  mi-ripples:ar mi-tides:ar mi-verb:ar mi-warps:ar)
(import-vars clojure-collider.ugens.extensions.ported-plugins
  analog-bass-drum:ar analog-degrade:ar analog-fold-osc:ar analog-loss:ar analog-phaser-mod:ar analog-phaser-mod:kr analog-phaser:ar analog-snare-drum:ar
  analog-tape:ar analog-vintage-distortion:ar bl-osc:ar bl-osc:kr chen:ar chen:kr d-compressor:ar harmonic-osc:ar
  harmonic-osc:kr lockhart-wavefolder:ar lockhart-wavefolder:kr lpg:ar neo-formant:ar neo-formant:kr neo-var-saw-osc:ar neo-var-saw-osc:kr
  nonlinear-filter:ar nonlinear-filter:kr osc-bank:ar osc-bank:kr phasor-modal:ar resonator:ar rongs:ar string-voice:ar
  va-diode-filter:ar va-diode-filter:kr va-korg35:ar va-korg35:kr va-ladder:ar va-ladder:kr va-sem12:ar va-sem12:kr
  vadim-filter:ar var-shape-osc:ar var-shape-osc:kr vosim-osc:ar vosim-osc:kr z-osc:ar z-osc:kr)
(import-vars clojure-collider.ugens.extensions.safety-limiter
  safety-limiter:ar)
(import-vars clojure-collider.ugens.extensions.sc-faust
  faust-all faust:ar)
(import-vars clojure-collider.ugens.fft
  convolution2-l:ar convolution2:ar convolution3:ar convolution3:kr convolution:ar fft-trigger:kr fft:kr ifft:ar
  ifft:kr pv-add:kr pv-bin-scramble:kr pv-bin-shift:kr pv-bin-wipe:kr pv-brick-wall:kr pv-conformal-map:kr pv-conj:kr
  pv-copy-phase:kr pv-copy:kr pv-diffuser:kr pv-div:kr pv-hainsworth-foote:kr pv-jensen-andersen:kr pv-local-max:kr pv-mag-above:kr
  pv-mag-below:kr pv-mag-clip:kr pv-mag-div:kr pv-mag-freeze:kr pv-mag-mul:kr pv-mag-noise:kr pv-mag-shift:kr pv-mag-smear:kr
  pv-mag-squared:kr pv-max:kr pv-min:kr pv-mul:kr pv-phase-shift270:kr pv-phase-shift90:kr pv-phase-shift:kr pv-rand-comb:kr
  pv-rand-wipe:kr pv-rect-comb2:kr pv-rect-comb:kr running-sum-rms running-sum:ar running-sum:kr stereo-convolution2-l:ar)
(import-vars clojure-collider.ugens.fft-unpacking
  pack-fft pv-calc pv-calc2 pv-collect unpack-1-fft unpack-fft)
(import-vars clojure-collider.ugens.filter
  apf:ar apf:kr bpf:ar bpf:kr bpz-2:ar bpz-2:kr brf:ar brf:kr
  brz-2:ar brz-2:kr changed:ar changed:kr decay2:ar decay2:kr decay:ar decay:kr
  detect-silence:ar detect-silence:kr formlet:ar formlet:kr fos:ar fos:kr hpf:ar hpf:kr
  hpz-1:ar hpz-1:kr hpz-2:ar hpz-2:kr integrator:ar integrator:kr lag-2ud:ar lag-2ud:kr
  lag-3ud:ar lag-3ud:kr lag-ud:ar lag-ud:kr lag2:ar lag2:kr lag3:ar lag3:kr
  lag:ar lag:kr leak-dc:ar leak-dc:kr lpf:ar lpf:kr lpz-1:ar lpz-1:kr
  lpz-2:ar lpz-2:kr median:ar median:kr mid-eq:ar mid-eq:kr one-pole:ar one-pole:kr
  one-zero:ar one-zero:kr ramp:ar ramp:kr resonz:ar resonz:kr rhpf:ar rhpf:kr
  ringz:ar ringz:kr rlpf:ar rlpf:kr slew:ar slew:kr slope:ar slope:kr
  sos:ar sos:kr two-pole:ar two-pole:kr two-zero:ar two-zero:kr var-lag:ar var-lag:kr)
(import-vars clojure-collider.ugens.free-verb
  freeverb2:ar freeverb:ar)
(import-vars clojure-collider.ugens.fsin-osc
  ->SpecArray blip:ar blip:kr dyn-klang:ar dyn-klang:kr dyn-klank:ar dyn-klank:kr f-sin-osc:ar
  f-sin-osc:kr klang:ar klank:ar map->SpecArray pulse:ar pulse:kr saw:kr)
(import-vars clojure-collider.ugens.g-verb
  g-verb:ar)
(import-vars clojure-collider.ugens.gendyn
  gendy1:ar gendy1:kr gendy2:ar gendy2:kr gendy3:ar gendy3:kr)
(import-vars clojure-collider.ugens.grain-ugens
  grain-buf:ar grain-fm:ar grain-in:ar grain-sin:ar warp1:ar warpz:ar)
(import-vars clojure-collider.ugens.hilbert
  freq-shift:ar hilbert-fir:ar hilbert:ar)
(import-vars clojure-collider.ugens.ienv-gen
  as-array-for-interpolation i-env-gen:ar i-env-gen:kr)
(import-vars clojure-collider.ugens.in-out
  in-feedback:ar in-trig:kr in:ar in:kr lag-in:kr local-in:ar local-in:kr local-out:ar
  local-out:kr offset-out:ar offset-out:kr out:ar out:kr replace-out:ar replace-out:kr x-out:ar
  x-out:kr)
(import-vars clojure-collider.ugens.info-ugens
  buf-channels:ir buf-channels:kr buf-dur:ir buf-dur:kr buf-frames:ir buf-frames:kr buf-info-ugen-base:ir buf-info-ugen-base:kr
  buf-rate-scale:ir buf-rate-scale:kr buf-sample-rate:ir buf-sample-rate:kr buf-samples:ir buf-samples:kr control-dur:ir control-rate:ir
  info-ugen-base:ir num-audio-buses:ir num-buffers:ir num-control-buses:ir num-input-buses:ir num-output-buses:ir num-running-synths:kr radians-per-sample:ir
  sample-dur:ir sample-rate:ir subsample-offset:ir)
(import-vars clojure-collider.ugens.line
  a2k:kr amp-comp-a:ar amp-comp-a:ir amp-comp-a:kr amp-comp:ar amp-comp:ir amp-comp:kr dc:ar
  dc:kr k2a:ar lin-exp:ar lin-exp:kr lin-lin lin-lin:ar lin-lin:kr line:ar
  line:kr silent:ar t2a:ar t2k:kr x-line:ar x-line:kr)
(import-vars clojure-collider.ugens.mac-ugens
  key-state:kr mouse-button:kr mouse-x:kr mouse-y:kr)
(import-vars clojure-collider.ugens.machine-listening
  beat-track2:kr beat-track:kr keytrack:kr loudness:kr mfcc:kr onsets:kr spec-centroid:kr spec-flatness:kr
  spec-pcile:kr)
(import-vars clojure-collider.ugens.moog-ff
  moog-ff:ar moog-ff:kr)
(import-vars clojure-collider.ugens.noise
  brown-noise:ar brown-noise:kr clip-noise:ar clip-noise:kr coin-gate:ar coin-gate:kr crackle:ar crackle:kr
  dust2:ar dust2:kr dust:ar dust:kr exp-rand:ir gray-noise:ar gray-noise:kr hasher:ar
  hasher:kr i-rand:ir lf-clip-noise:ar lf-clip-noise:kr lf-noise0:ar lf-noise0:kr lf-noise1:ar lf-noise1:kr
  lf-noise2:ar lf-noise2:kr lfd-clip-noise:ar lfd-clip-noise:kr lfd-noise0:ar lfd-noise0:kr lfd-noise1:ar lfd-noise1:kr
  lfd-noise3:ar lfd-noise3:kr lin-rand:ir logistic:ar logistic:kr mantissa-mask:ar mantissa-mask:kr n-rand:ir
  pink-noise:ar pink-noise:kr rand-id:ir rand-id:kr rand-seed:ar rand-seed:ir rand-seed:kr rand:ir
  t-exp-rand:ar t-exp-rand:kr t-rand:ar t-rand:kr ti-rand:ar ti-rand:kr tw-index:ar tw-index:kr
  white-noise:ar white-noise:kr)
(import-vars clojure-collider.ugens.osc
  c-osc:ar c-osc:kr degree-to-key:ar degree-to-key:kr detect-index:ar detect-index:kr formant:ar impulse:ar
  impulse:kr index-in-between:ar index-in-between:kr index-l:ar index-l:kr index:ar index:kr lf-cub:ar
  lf-cub:kr lf-gauss:ar lf-gauss:kr lf-par:ar lf-par:kr lf-pulse:ar lf-pulse:kr lf-saw:ar
  lf-saw:kr lf-tri:ar lf-tri:kr lin-select-x:ar lin-select-x:kr osc-n:ar osc-n:kr osc:ar
  osc:kr pm-osc:ar pm-osc:kr saw:ar select-x-focus:ar select-x-focus:kr select-x:ar select-x:kr
  select:ar select:kr shaper:ar shaper:kr sin-osc-fb:ar sin-osc-fb:kr sin-osc:ar sin-osc:kr
  sync-saw:ar sync-saw:kr t-choose:ar t-choose:kr tw-choose:ar tw-choose:kr v-osc:ar v-osc:kr
  var-saw:ar var-saw:kr vibrato:ar vibrato:kr wrap-index:ar wrap-index:kr)
(import-vars clojure-collider.ugens.pan
  balance2:ar balance2:kr bi-pan-b2:ar bi-pan-b2:kr decode-b2:ar decode-b2:kr lin-pan2:ar lin-pan2:kr
  lin-x-fade2:ar lin-x-fade2:kr pan-az:ar pan-az:kr pan-b2:ar pan-b2:kr pan-b:ar pan-b:kr
  pan2:ar pan2:kr pan4:ar pan4:kr rotate2:ar rotate2:kr x-fade2:ar x-fade2:kr)
(import-vars clojure-collider.ugens.part-conv
  part-conv:ar)
(import-vars clojure-collider.ugens.physical-model
  ball:ar ball:kr spring:ar spring:kr t-ball:ar t-ball:kr)
(import-vars clojure-collider.ugens.pitch-shift
  pitch-shift:ar)
(import-vars clojure-collider.ugens.pluck
  pluck:ar)
(import-vars clojure-collider.ugens.poll
  poll:ar poll:kr)
(import-vars clojure-collider.ugens.psin-grain
  p-sin-grain:ar)
(import-vars clojure-collider.ugens.quarks.red-impulse
  red-impulse:ar red-impulse:kr)
(import-vars clojure-collider.ugens.quarks.wave-folding
  smooth-clip-q:ar smooth-clip-q:kr smooth-clip-s:ar smooth-clip-s:kr smooth-fold-q2:ar smooth-fold-q2:kr smooth-fold-q:ar smooth-fold-q:kr
  smooth-fold-s2:ar smooth-fold-s2:kr smooth-fold-s:ar smooth-fold-s:kr)
(import-vars clojure-collider.ugens.sc3plugins.bhob-ugens
  double-nested-allpass-c:ar double-nested-allpass-l:ar double-nested-allpass-n:ar gauss-trig:ar gauss-trig:kr gendy4:ar gendy4:kr gendy5:ar
  gendy5:kr lf-brown-noise0:ar lf-brown-noise0:kr lf-brown-noise1:ar lf-brown-noise1:kr lf-brown-noise2:ar lf-brown-noise2:kr moog-ladder:ar
  moog-ladder:kr nested-allpass-c:ar nested-allpass-l:ar nested-allpass-n:ar nlfilt-c:ar nlfilt-c:kr nlfilt-l:ar nlfilt-l:kr
  nlfilt-n:ar nlfilt-n:kr pv-common-mag:kr pv-common-mul:kr pv-compander:kr pv-cutoff:kr pv-mag-gate:kr pv-mag-minus:kr
  pv-mag-scale:kr pv-morph:kr pv-soft-wipe:kr pv-xfade:kr rlpfd:ar rlpfd:kr streson:ar streson:kr
  t-beta-rand:ar t-beta-rand:kr t-brown-rand:ar t-brown-rand:kr t-gauss-rand:ar t-gauss-rand:kr tgrains2:ar tgrains3:ar)
(import-vars clojure-collider.ugens.sc3plugins.deind-ugens
  greyhole:ar jpverb:ar)
(import-vars clojure-collider.ugens.sc3plugins.distortion-plugins
  crossover-distortion:ar decimator:ar disintegrator:ar sine-shaper:ar smooth-decimator:ar)
(import-vars clojure-collider.ugens.sc3plugins.josh-ugens
  bin-data:ar bin-data:kr moog-vcf:ar pv-bin-buf-rd:kr pv-bin-delay:kr pv-bin-filter:kr pv-bin-play-buf:kr pv-buf-rd:kr
  pv-even-bin:kr pv-freeze:kr pv-freq-buffer:kr pv-invert:kr pv-mag-buffer:kr pv-mag-map:kr pv-max-mag-n:kr pv-min-mag-n:kr
  pv-noise-synth-f:kr pv-noise-synth-p:kr pv-odd-bin:kr pv-partial-synth-f:kr pv-partial-synth-p:kr pv-play-buf:kr pv-record-buf:kr pv-spectral-enhance:kr
  pv-spectral-map:kr)
(import-vars clojure-collider.ugens.sc3plugins.ladspa-ugen
  ladspa:ar)
(import-vars clojure-collider.ugens.sc3plugins.mcld-ugens
  array-max:ar array-max:kr array-min:ar array-min:kr pv-whiten:kr saw-dpw:ar saw-dpw:kr)
(import-vars clojure-collider.ugens.sc3plugins.mda-ugens
  mda-piano:ar)
(import-vars clojure-collider.ugens.sc3plugins.nh-ugens
  nhhall:ar)
(import-vars clojure-collider.ugens.sc3plugins.pitch-detection
  qitch:kr tartini:kr)
(import-vars clojure-collider.ugens.sc3plugins.sl-ugens
  breakcore:ar brusselator:ar double-well2:ar double-well3:ar double-well:ar env-detect:ar env-follow:ar env-follow:kr
  fitz-hugh-nagumo:ar gravity-grid2:ar gravity-grid:ar instruction:ar kmeans-to-bpset1:ar lpc-error:ar lti:ar max:kr
  nl2:ar nl:ar oregonator:ar print-val:kr sieve1:ar sieve1:kr sl-onset:ar sort-buf:ar
  spruce-budworm:ar terman-wang:ar two-tube:ar vm-scan2d:ar wave-terrain:ar weakly-nonlinear2:ar weakly-nonlinear:ar)
(import-vars clojure-collider.ugens.sc3plugins.tj-ugens
  dfm-1:ar)
(import-vars clojure-collider.ugens.sound-in
  audio-in:ar sound-in:ar)
(import-vars clojure-collider.ugens.splay
  splay-fill splay:ar splay:kr)
(import-vars clojure-collider.ugens.test-ugens
  check-bad-values:ar check-bad-values:kr sanitize:ar sanitize:kr)
(import-vars clojure-collider.ugens.trig
  clip clip:ar clip:ir clip:kr coyote:kr fold fold:ar fold:ir fold:kr gate:ar
  gate:kr in-range:ar in-range:kr last-value:ar last-value:kr latch:ar latch:kr least-change:ar
  least-change:kr most-change:ar most-change:kr peak-follower:ar peak-follower:kr peak:ar peak:kr phasor:ar
  phasor:kr pitch:kr pulse-count:ar pulse-count:kr pulse-divider:ar pulse-divider:kr running-max:ar running-max:kr
  running-min:ar running-min:kr schmidt:ar schmidt:kr send-peak-rms:ar send-peak-rms:kr send-reply:ar send-reply:kr
  send-trig:ar send-trig:kr set-reset-ff:ar set-reset-ff:kr stepper:ar stepper:kr sweep:ar sweep:kr
  t-delay:ar t-delay:kr timer:ar timer:kr toggle-ff:ar toggle-ff:kr trig-1:ar trig-1:kr
  trig:ar trig:kr wrap wrap:ar wrap:ir wrap:kr zero-crossing:ar zero-crossing:kr)
