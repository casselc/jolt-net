; Same invariant with the compare-and-set implementation jolt.net.handle uses:
;     (when (compare-and-set! state :open :closed) (close raw))
;
; CAS fuses the read and the write into one atomic step, so exactly one caller
; can observe :open and transition it. That atomicity is the added constraint.
;
; VERIFIED: unsat. unsat core: cas_atomicity, someone_closes, property_violated.

(declare-const t1_cas_wins Bool)
(declare-const t2_cas_wins Bool)
(declare-const close_count Int)

; ATOMICITY: at most one CAS succeeds on the same :open -> :closed transition
(assert (! (not (and t1_cas_wins t2_cas_wins)) :named cas_atomicity))
; PROGRESS: the handle does get closed
(assert (! (or t1_cas_wins t2_cas_wins) :named someone_closes))

(assert (= close_count (+ (ite t1_cas_wins 1 0) (ite t2_cas_wins 1 0))))

; negation of "close is issued exactly once"
(assert (! (not (= close_count 1)) :named property_violated))
