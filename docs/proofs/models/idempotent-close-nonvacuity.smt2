; Non-vacuity control for idempotent-close-corrected.smt2.
;
; UNSAT there would be worthless if atomicity and progress were jointly
; unsatisfiable: the property would hold because NO run exists, not because
; every run is safe.
;
; VERIFIED: sat. Witness: t1_cas_wins=true, t2_cas_wins=false, close_count=1,
; under genuine contention.

(declare-const t1_cas_wins Bool)
(declare-const t2_cas_wins Bool)
(declare-const close_count Int)
(declare-const contention Bool)

(assert (not (and t1_cas_wins t2_cas_wins)))
(assert (or t1_cas_wins t2_cas_wins))
(assert (= close_count (+ (ite t1_cas_wins 1 0) (ite t2_cas_wins 1 0))))

; both threads genuinely raced for the same handle
(assert (= contention true))

; the property ITSELF: a witness must exist
(assert (! (= close_count 1) :named property_holds))
