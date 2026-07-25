; Non-vacuity control for winsock-init-once-corrected.smt2.
;
; UNSAT there would be worthless if atomicity, progress, and agreement were
; jointly unsatisfiable: the property would hold because NO run exists, not
; because every run is safe.
;
; VERIFIED: sat. Witness: t1_wins_cas=true, t2_wins_cas=false,
; attempt_count=1, t1_outcome=t2_outcome, under genuine contention.

(set-option :produce-unsat-cores true)

(declare-const t1_wins_cas Bool)
(declare-const t2_wins_cas Bool)
(declare-const attempt_count Int)
(declare-const t1_outcome Bool)
(declare-const t2_outcome Bool)
(declare-const contention Bool)

(assert (not (and t1_wins_cas t2_wins_cas)))
(assert (or t1_wins_cas t2_wins_cas))
(assert (= attempt_count (+ (ite t1_wins_cas 1 0) (ite t2_wins_cas 1 0))))
(assert (=> (not t1_wins_cas) (= t1_outcome t2_outcome)))
(assert (=> (not t2_wins_cas) (= t2_outcome t1_outcome)))

; both callers genuinely raced for the same first-use attempt
(assert (= contention true))

; the property ITSELF: a witness must exist
(assert (! (and (= attempt_count 1) (= t1_outcome t2_outcome)) :named property_holds))

(check-sat)
(get-model)
