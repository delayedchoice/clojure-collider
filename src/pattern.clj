 (ns pattern
  "Short facade: every pattern primitive behind one require.
   Run: (:require [pattern :as p]) then p/pbind, p/pwhite, p/play, etc.
   Later namespaces win on the three shared names (all benign):
   scales/degree-midinote over conversions (concrete tables),
   util/eop + eop? over the delegating copies.
   No dynamic vars are copied here: all pattern state is explicit.
   Clocks carry their server in (:server clock); the global RNG lives
   in patterns.util/default-rng (seed via `seed-rng!`); the global
   scale lookup lives in patterns.conversions/scale-lookup.
   The var list lives on the patterns.* pages.")
(require 'patterns.util 'patterns.conversions 'patterns.scales 'patterns.event
         'patterns.patterns 'patterns.pbind 'patterns.backend 'patterns.clock)

(doseq [ns-sym '[patterns.conversions patterns.scales patterns.event
                 patterns.patterns patterns.pbind patterns.backend
                 patterns.clock patterns.util]]
  (doseq [[sym v] (ns-publics ns-sym)]
    (when-not (:dynamic (meta v))
      (let [dst (intern *ns* sym @v)]
        (alter-meta! dst merge (dissoc (meta v) :name :ns))))))
