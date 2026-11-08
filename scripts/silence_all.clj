;; Find every live scsynth on this machine and silence it - no REPL state,
;; no bindings, no registry needed. Run while sound is playing:
;;   clojure -M scripts/silence_all.clj
;; Prints the responding ports, then /clearSched + /g_deepFree 0 to each.
;; Servers stay up (unlike killall); truly foreign boxes are out of reach.
(require '[clojure-collider.server.server :as server])

(let [live (server/find-live-servers)]
  (println :live-servers live)
  (doseq [p live]
    (try (server/panic-port! p)
         (catch Exception e (println :spray-failed p (.getMessage e)))))
  (println :silenced live)
  (shutdown-agents)
  (System/exit 0))
