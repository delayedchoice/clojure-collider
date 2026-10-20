;; Short facade: every pattern primitive behind one require.
;; Run: (:require [pattern :as p]) then p/pbind, p/pwhite, p/play, etc.
;;
;; Later namespaces win on the three shared names (all benign):
;; scales/degree-midinote over conversions (concrete tables),
;; util/eop + eop? over the delegating copies.
;; Dynamic vars (*rng* *event* *clock* *scale-lookup*) are NOT copied:
;; a copy would not propagate binding. `seed-rng!` covers seeding;
;; bind the home vars directly for custom instances.
(ns pattern)

(require 'patterns.util 'patterns.conversions 'patterns.scales 'patterns.event
         'patterns.patterns 'patterns.pbind 'patterns.backend 'patterns.clock)

(doseq [ns-sym '[patterns.conversions patterns.scales patterns.event
                 patterns.patterns patterns.pbind patterns.backend
                 patterns.clock patterns.util]]
  (doseq [[sym v] (ns-publics ns-sym)]
    (when-not (:dynamic (meta v))
      (let [dst (intern *ns* sym @v)]
        (alter-meta! dst merge (dissoc (meta v) :name :ns))))))
