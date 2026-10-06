# True goldens: byte output of upstream cl-collider on SBCL.
# Regen: `sbcl --non-interactive --load ../../../scripts/gen_goldens.lisp`
# (needs ~/quicklisp + cl-collider linked in local-projects).
# Our side: `clojure -M ../../../scripts/dump_ours.clj` (paths in script).
# Tests assert our bytes equal these files; any divergence is a parity bug.
