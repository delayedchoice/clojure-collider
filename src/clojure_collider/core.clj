(ns clojure-collider.core
  "Public re-export — mirrors the sc package export list.
   Consumers can (:require [clojure-collider.core :as sc]).

   Dynamic vars (*s*, *synthdef*, *synth-definition-mode*,
   *temp-synth-name*) are NOT copied here: binding a copy would not affect
   the home namespace. Require the owning ns to bind them."
  (:refer-clojure :exclude [* + - / < <= > >= abs dec inc min max mod sync range proxy])
  (:require [clojure-collider.server.server :as server]
            [clojure-collider.server.scheduler :as sched]
            [clojure-collider.server.options :as opts]
            [clojure-collider.buffer :as buffer]
            [clojure-collider.bus :as bus]
            [clojure-collider.synth.ugen :as ugen]
            [clojure-collider.synth.synthdef :as synthdef]
            [clojure-collider.synth.control :as ctl]
            [clojure-collider.synth.convert :as conv]
            [clojure-collider.synth.sdef :as sdef]
            [clojure-collider.synth.operators :as ops]
            [clojure-collider.ugens.osc :as uosc]
            [clojure-collider.ugens.in-out :as uio]
            [clojure-collider.ugens.env-gen :as uenv]
            [clojure-collider.ugens.filter :as ufilt]
            [clojure-collider.ugens.line :as uline]
            [clojure-collider.ugens]
            [clojure-collider.volume :as vol]))

(def version "1.0")

(defmacro import-vars
  "Copy public vars (fns and macros) from another ns, preserving metadata."
  [from-ns & syms]
  `(do ~@(map (fn [s#]
                `(let [src# (var ~(symbol (str from-ns "/" s#)))
                       dst# (intern *ns* '~s# @src#)]
                   (alter-meta! dst# merge (dissoc (meta src#) :name :ns))
                   dst#))
              syms)))

;; -- server ---------------------------------------------------------------
(import-vars clojure-collider.server.server
  make-external-server server-boot server-quit boot? is-local? sync
  send-message send-bundle get-next-id make-synth-msg message-distribute
  node-to-pos install-reply-responder add-reply-responder
  remove-reply-responder all-running-servers
  make-group group-free-all server-free-all node-free release ctrl map-bus
  control-get control-set callback at now quant beat-dur jack-connect
  make-nrt-server with-rendering ->NrtServer map->NrtServer nrt-formats)

;; -- scheduler / options ---------------------------------------------------
(import-vars clojure-collider.server.scheduler
  make-scheduler sched-add sched-clear sched-run sched-stop sched-status
  sched-time sched-quant make-tempo-clock tempo-clock-run tempo-clock-stop
  tempo-clock-beats tempo-clock-bpm tempo-clock-set-bpm tempo-clock-quant
  tempo-clock-add tempo-clock-clear)
(import-vars clojure-collider.server.options
  make-server-options build-server-options)

;; -- buffer / bus -----------------------------------------------------------
(import-vars clojure-collider.buffer
  buffer-alloc buffer-alloc-consecutive buffer-free buffer-read buffer-write
  buffer-set buffer-get buffer-setn buffer-getn buffer-copy buffer-zero
  buffer-close buffer-cue-soundfile buffer-read-channel buffer-read-no-update
  buffer-normalize buffer-dur get-next-buffer buffer-set-sr
  buffer-alloc-sequence buffer-send-sequence buffer-load-sequence buffer-load
  buffer-fill linear-resample write-mono-fl32-wav
  vector-in-wavetable-format as-wavetable as-wavetable-no-wrap
  buffer-get-to-list buffer-load-to-list buffer-to-list
  buffer-to-array buffer-get-to-array buffer-load-to-array
  buffer-read-as-wavetable)
(import-vars clojure-collider.bus
  bus-audio bus-control bus-free bus-string bus? get-next-bus)

;; -- synth graph -------------------------------------------------------------
(import-vars clojure-collider.synth.ugen rate act floatfy)
(import-vars clojure-collider.synth.synthdef
  make-synthdef build-synthdef encode-synthdef write-synthdef-file)
(import-vars clojure-collider.synth.control
  with-controls named-control kr make-control normalize-params)
(import-vars clojure-collider.synth.convert
  convert-code synthdef-equivalent-function set-synthdef-equivalent!)
(import-vars clojure-collider.synth.sdef
  defsynth play synth synthdef-metadata set-synthdef-metadata!
  recv-synthdef load-synthdef proxy proxy-ctrl get-controls-list proxy-key-name)

;; -- operators (shadow clojure.core numerics, like upstream sc::) ------------
(import-vars clojure-collider.synth.operators
  * + - / < <= > >= abs min max mod dec inc
  madd mul-add sum sum3 sum4 mix range if* bubble)

;; -- ugens (whole library; regenerate with scripts/gen_facade.clj) --------------
(import-vars clojure-collider.ugens
  ->Env ->EnvArray ->SpecArray a2k:kr allpass-c:ar allpass-c:kr
  allpass-l:ar allpass-l:kr allpass-n:ar allpass-n:kr amp-comp-a:ar amp-comp-a:ir
  amp-comp-a:kr amp-comp:ar amp-comp:ir amp-comp:kr amplitude:ar amplitude:kr
  analog-bass-drum:ar analog-degrade:ar analog-fold-osc:ar analog-loss:ar analog-phaser-mod:ar analog-phaser-mod:kr
  analog-phaser:ar analog-snare-drum:ar analog-tape:ar analog-vintage-distortion:ar apf:ar apf:kr
  array-max:ar array-max:kr array-min:ar array-min:kr as-array-for-interpolation as-audio-rate-input
  astrocade:ar atari2600:ar audio-in:ar ay8910-square:ar ay8910:ar b-allpass:ar
  b-bandpass:ar b-bandstop:ar b-hipass4:ar b-hipass:ar b-hishelf:ar b-lowpass4:ar
  b-lowpass:ar b-lowshelf:ar b-peak-eq:ar balance2:ar balance2:kr ball:ar
  ball:kr beat-track2:kr beat-track:kr beep:ar bi-pan-b2:ar bi-pan-b2:kr
  bin-data:ar bin-data:kr bl-osc:ar bl-osc:kr blip:ar blip:kr
  bpf:ar bpf:kr bpz-2:ar bpz-2:kr breakcore:ar brf:ar
  brf:kr brown-noise:ar brown-noise:kr brusselator:ar brz-2:ar brz-2:kr
  buf-allpass-c:ar buf-allpass-l:ar buf-allpass-n:ar buf-channels:ir buf-channels:kr buf-comb-c:ar
  buf-comb-l:ar buf-comb-n:ar buf-delay-c:ar buf-delay-c:kr buf-delay-l:ar buf-delay-l:kr
  buf-delay-n:ar buf-delay-n:kr buf-dur:ir buf-dur:kr buf-frames:ir buf-frames:kr
  buf-info-ugen-base:ir buf-info-ugen-base:kr buf-rate-scale:ir buf-rate-scale:kr buf-rd:ar buf-rd:kr
  buf-sample-rate:ir buf-sample-rate:kr buf-samples:ir buf-samples:kr buf-wr:ar buf-wr:kr
  c-osc:ar c-osc:kr cd-skip:ar changed:ar changed:kr check-bad-values:ar
  check-bad-values:kr chen:ar chen:kr clip clip-noise:ar clip-noise:kr clip:ar
  clip:ir clip:kr coin-gate:ar coin-gate:kr comb-c:ar comb-c:kr
  comb-l:ar comb-l:kr comb-n:ar comb-n:kr compander-d:ar compander:ar
  control-dur:ir control-rate:ir convolution2-l:ar convolution2:ar convolution3:ar convolution3:kr
  convolution:ar coyote:kr crackle:ar crackle:kr crossover-distortion:ar cusp-l:ar
  cusp-n:ar d-compressor:ar dc:ar dc:kr decay2:ar decay2:kr
  decay:ar decay:kr decimator:ar decode-b2:ar decode-b2:kr degree-to-key:ar
  degree-to-key:kr delay-1:ar delay-1:kr delay-2:ar delay-2:kr delay-c:ar
  delay-c:kr delay-l:ar delay-l:kr delay-n:ar delay-n:kr deltap-rd:ar
  deltap-rd:kr deltap-wr:ar deltap-wr:kr demand-envgen:ar demand-envgen:kr demand:ar
  demand:kr detect-index:ar detect-index:kr detect-silence:ar detect-silence:kr dfm-1:ar
  disintegrator:ar disk-in:ar disk-out:ar done:kr double-nested-allpass-c:ar double-nested-allpass-l:ar
  double-nested-allpass-n:ar double-well2:ar double-well3:ar double-well:ar dust2:ar dust2:kr
  dust:ar dust:kr duty:ar duty:kr dyn-gen-all dyn-gen:ar
  dyn-klang:ar dyn-klang:kr dyn-klank:ar dyn-klank:kr env env-adsr
  env-asr env-dadsr env-detect:ar env-follow:ar env-follow:kr env-gen:ar
  env-gen:kr env-linen env-perc env-shape-number env-sine env-triangle
  exp-rand:ir f-sin-osc:ar f-sin-osc:kr faust-all faust:ar fbsine-c:ar
  fbsine-l:ar fbsine-n:ar fft-trigger:kr fft:kr fitz-hugh-nagumo:ar fold fold:ar
  fold:ir fold:kr formant:ar formlet:ar formlet:kr fos:ar
  fos:kr free-self-when-done:kr free-self:kr free:kr freeverb2:ar freeverb:ar
  freq-shift:ar g-verb:ar gate:ar gate:kr gauss-trig:ar gauss-trig:kr
  gbman-l:ar gbman-n:ar gendy1:ar gendy1:kr gendy2:ar gendy2:kr
  gendy3:ar gendy3:kr gendy4:ar gendy4:kr gendy5:ar gendy5:kr
  grain-buf:ar grain-fm:ar grain-in:ar grain-sin:ar gravity-grid2:ar gravity-grid:ar
  gray-noise:ar gray-noise:kr greyhole:ar harmonic-osc:ar harmonic-osc:kr hasher:ar
  hasher:kr henon-c:ar henon-l:ar henon-n:ar hilbert-fir:ar hilbert:ar
  hpf:ar hpf:kr hpz-1:ar hpz-1:kr hpz-2:ar hpz-2:kr
  i-env-gen:ar i-env-gen:kr i-rand:ir ibuf-wr:ar ibuf-wr:kr ifft:ar
  ifft:kr impulse:ar impulse:kr in-feedback:ar in-range:ar in-range:kr
  in-trig:kr in:ar in:kr index-in-between:ar index-in-between:kr index-l:ar
  index-l:kr index:ar index:kr info-ugen-base:ir instruction:ar integrator:ar
  integrator:kr jpverb:ar k2a:ar key-state:kr keytrack:kr klang:ar
  klank:ar kmeans-to-bpset1:ar ladspa:ar lag-2ud:ar lag-2ud:kr lag-3ud:ar
  lag-3ud:kr lag-in:kr lag-ud:ar lag-ud:kr lag2:ar lag2:kr
  lag3:ar lag3:kr lag:ar lag:kr last-value:ar last-value:kr
  latch:ar latch:kr latoocarfian-c:ar latoocarfian-l:ar latoocarfian-n:ar leak-dc:ar
  leak-dc:kr least-change:ar least-change:kr lf-brown-noise0:ar lf-brown-noise0:kr lf-brown-noise1:ar
  lf-brown-noise1:kr lf-brown-noise2:ar lf-brown-noise2:kr lf-clip-noise:ar lf-clip-noise:kr lf-cub:ar
  lf-cub:kr lf-gauss:ar lf-gauss:kr lf-noise0:ar lf-noise0:kr lf-noise1:ar
  lf-noise1:kr lf-noise2:ar lf-noise2:kr lf-par:ar lf-par:kr lf-pulse:ar
  lf-pulse:kr lf-saw:ar lf-saw:kr lf-tri:ar lf-tri:kr lfd-clip-noise:ar
  lfd-clip-noise:kr lfd-noise0:ar lfd-noise0:kr lfd-noise1:ar lfd-noise1:kr lfd-noise3:ar
  lfd-noise3:kr limiter:ar lin-exp:ar lin-exp:kr lin-lin lin-lin:ar
  lin-lin:kr lin-pan2:ar lin-pan2:kr lin-rand:ir lin-select-x:ar lin-select-x:kr
  lin-x-fade2:ar lin-x-fade2:kr lincong-c:ar lincong-l:ar lincong-n:ar line:ar
  line:kr linen:kr local-in:ar local-in:kr local-out:ar local-out:kr
  lockhart-wavefolder:ar lockhart-wavefolder:kr logistic:ar logistic:kr lorenz-l:ar loudness:kr
  lpc-error:ar lpf:ar lpf:kr lpg:ar lpz-1:ar lpz-1:kr
  lpz-2:ar lpz-2:kr lti:ar make-env-array-from-env mantissa-mask:ar mantissa-mask:kr
  map->Env map->EnvArray map->SpecArray max:kr mda-piano:ar median:ar
  median:kr mfcc:kr mi-braids:ar mi-clouds:ar mi-elements:ar mi-grids:ar
  mi-mu:ar mi-omi:ar mi-plaits:ar mi-rings:ar mi-ripples:ar mi-tides:ar
  mi-verb:ar mi-warps:ar mid-eq:ar mid-eq:kr moog-ff:ar moog-ff:kr
  moog-ladder:ar moog-ladder:kr moog-vcf:ar most-change:ar most-change:kr mouse-button:kr
  mouse-x:kr mouse-y:kr mz-pokey:ar n-rand:ir neo-formant:ar neo-formant:kr
  neo-var-saw-osc:ar neo-var-saw-osc:kr nes2-dmc:ar nes2-noise:ar nes2-square:ar nes2-triangle:ar
  nes2:ar nested-allpass-c:ar nested-allpass-l:ar nested-allpass-n:ar nhhall:ar nl2:ar
  nl:ar nlfilt-c:ar nlfilt-c:kr nlfilt-l:ar nlfilt-l:kr nlfilt-n:ar
  nlfilt-n:kr nonlinear-filter:ar nonlinear-filter:kr normalizer:ar num-audio-buses:ir num-buffers:ir
  num-control-buses:ir num-input-buses:ir num-output-buses:ir num-running-synths:kr offset-out:ar offset-out:kr
  one-pole:ar one-pole:kr one-zero:ar one-zero:kr onsets:kr oregonator:ar
  osc-bank:ar osc-bank:kr osc-n:ar osc-n:kr osc:ar osc:kr
  out:ar out:kr p-sin-grain:ar pack-fft pan-az:ar pan-az:kr
  pan-b2:ar pan-b2:kr pan-b:ar pan-b:kr pan2:ar pan2:kr
  pan4:ar pan4:kr part-conv:ar pause-self-when-done:kr pause-self:kr pause:kr
  peak-follower:ar peak-follower:kr peak:ar peak:kr phasor-modal:ar phasor:ar
  phasor:kr pink-noise:ar pink-noise:kr pitch-shift:ar pitch:kr play-buf:ar
  play-buf:kr pluck:ar pm-osc:ar pm-osc:kr pokey-square:ar pokey:ar
  poll:ar poll:kr print-val:kr process-env puase:kr pulse-count:ar
  pulse-count:kr pulse-divider:ar pulse-divider:kr pulse:ar pulse:kr pv-add:kr
  pv-bin-buf-rd:kr pv-bin-delay:kr pv-bin-filter:kr pv-bin-play-buf:kr pv-bin-scramble:kr pv-bin-shift:kr
  pv-bin-wipe:kr pv-brick-wall:kr pv-buf-rd:kr pv-calc pv-calc2 pv-collect
  pv-common-mag:kr pv-common-mul:kr pv-compander:kr pv-conformal-map:kr pv-conj:kr pv-copy-phase:kr
  pv-copy:kr pv-cutoff:kr pv-diffuser:kr pv-div:kr pv-even-bin:kr pv-freeze:kr
  pv-freq-buffer:kr pv-hainsworth-foote:kr pv-invert:kr pv-jensen-andersen:kr pv-local-max:kr pv-mag-above:kr
  pv-mag-below:kr pv-mag-buffer:kr pv-mag-clip:kr pv-mag-div:kr pv-mag-freeze:kr pv-mag-gate:kr
  pv-mag-map:kr pv-mag-minus:kr pv-mag-mul:kr pv-mag-noise:kr pv-mag-scale:kr pv-mag-shift:kr
  pv-mag-smear:kr pv-mag-squared:kr pv-max-mag-n:kr pv-max:kr pv-min-mag-n:kr pv-min:kr
  pv-morph:kr pv-mul:kr pv-noise-synth-f:kr pv-noise-synth-p:kr pv-odd-bin:kr pv-partial-synth-f:kr
  pv-partial-synth-p:kr pv-phase-shift270:kr pv-phase-shift90:kr pv-phase-shift:kr pv-play-buf:kr pv-rand-comb:kr
  pv-rand-wipe:kr pv-record-buf:kr pv-rect-comb2:kr pv-rect-comb:kr pv-soft-wipe:kr pv-spectral-enhance:kr
  pv-spectral-map:kr pv-whiten:kr pv-xfade:kr qitch:kr quad-c:ar quad-l:ar
  quad-n:ar radians-per-sample:ir ramp:ar ramp:kr rand-id:ir rand-id:kr
  rand-seed:ar rand-seed:ir rand-seed:kr rand:ir record-buf:ar record-buf:kr
  red-dpcm-decode:ar red-dpcm-decode:kr red-dpcm-encode:ar red-dpcm-encode:kr red-impulse:ar red-impulse:kr
  red-lbyl:ar red-lbyl:kr red-noise:ar red-noise:kr red-phasor2:ar red-phasor2:kr
  red-phasor:ar red-phasor:kr replace-out:ar replace-out:kr resonator:ar resonz:ar
  resonz:kr rhpf:ar rhpf:kr ringz:ar ringz:kr rlpf:ar
  rlpf:kr rlpfd:ar rlpfd:kr rongs:ar rotate2:ar rotate2:kr
  running-max:ar running-max:kr running-min:ar running-min:kr running-sum-rms running-sum:ar
  running-sum:kr safety-limiter:ar sample-dur:ir sample-rate:ir sanitize:ar sanitize:kr
  saw-dpw:ar saw-dpw:kr saw:ar saw:kr schmidt:ar schmidt:kr
  scope-out2:ar scope-out2:kr scope-out:ar scope-out:kr select-x-focus:ar select-x-focus:kr
  select-x:ar select-x:kr select:ar select:kr send-peak-rms:ar send-peak-rms:kr
  send-reply:ar send-reply:kr send-trig:ar send-trig:kr set-reset-ff:ar set-reset-ff:kr
  shape-table shaper:ar shaper:kr sid6581f:ar sieve1:ar sieve1:kr
  silent:ar sin-osc-fb:ar sin-osc-fb:kr sin-osc:ar sin-osc:kr sine-shaper:ar
  sl-onset:ar slew:ar slew:kr slope:ar slope:kr slub:ar
  slub:kr smooth-clip-q:ar smooth-clip-q:kr smooth-clip-s:ar smooth-clip-s:kr smooth-decimator:ar
  smooth-fold-q2:ar smooth-fold-q2:kr smooth-fold-q:ar smooth-fold-q:kr smooth-fold-s2:ar smooth-fold-s2:kr
  smooth-fold-s:ar smooth-fold-s:kr sn76489:ar sort-buf:ar sos:ar sos:kr
  sound-in:ar spec-centroid:kr spec-flatness:kr spec-pcile:kr splay-fill splay:ar
  splay:kr spring:ar spring:kr spruce-budworm:ar standard-l:ar standard-n:ar
  stepper:ar stepper:kr stereo-convolution2-l:ar streson:ar streson:kr string-voice:ar
  subsample-offset:ir svf:ar svf:kr sweep:ar sweep:kr sync-saw:ar sync-saw:kr t-ball:ar
  t-ball:kr t-beta-rand:ar t-beta-rand:kr t-bjorklund:ar t-bjorklund:kr t-brown-rand:ar
  t-brown-rand:kr t-choose:ar t-choose:kr t-delay:ar t-delay:kr t-duty:ar
  t-duty:kr t-exp-rand:ar t-exp-rand:kr t-gauss-rand:ar t-gauss-rand:kr t-rand:ar
  t-rand:kr t2a:ar t2k:kr tap:ar tartini:kr terman-wang:ar
  tgrains2:ar tgrains3:ar tgrains:ar ti-rand:ar ti-rand:kr timer:ar
  timer:kr toggle-ff:ar toggle-ff:kr trig-1:ar trig-1:kr trig:ar
  trig:kr tw-choose:ar tw-choose:kr tw-index:ar tw-index:kr two-pole:ar
  two-pole:kr two-tube:ar two-zero:ar two-zero:kr unpack-1-fft unpack-fft
  v-disk-in:ar v-osc:ar v-osc:kr va-diode-filter:ar va-diode-filter:kr va-korg35:ar
  va-korg35:kr va-ladder:ar va-ladder:kr va-sem12:ar va-sem12:kr vadim-filter:ar
  var-lag:ar var-lag:kr var-saw:ar var-saw:kr var-shape-osc:ar var-shape-osc:kr
  vibrato:ar vibrato:kr vm-scan2d:ar vosim-osc:ar vosim-osc:kr warp1:ar
  warpz:ar wave-terrain:ar waveset-repeater:ar waveset-repeater:kr weakly-nonlinear2:ar weakly-nonlinear:ar
  white-noise:ar white-noise:kr wrap wrap-index:ar wrap-index:kr wrap:ar wrap:ir
  wrap:kr x-fade2:ar x-fade2:kr x-line:ar x-line:kr x-out:ar
  x-out:kr z-osc:ar z-osc:kr zero-crossing:ar zero-crossing:kr)
(import-vars clojure-collider.volume volume set-volume! mute unmute)
