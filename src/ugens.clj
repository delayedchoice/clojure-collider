;; Short facade: every public UGen fn behind one require.
;; Run: (:require [ugens :as u]) then u/sin-osc:ar etc.
;; Implemented by copying the publics of clojure-collider.ugens (which
;; already de-duplicates the per-file namespaces) at load.
(ns ugens)

(require 'clojure-collider.ugens)

(doseq [[sym v] (ns-publics 'clojure-collider.ugens)]
  (let [dst (intern *ns* sym @v)]
    (alter-meta! dst merge (dissoc (meta v) :name :ns))))
