; Non-vacuity control for errno-capture-ordering-corrected.smt2.
;
; UNSAT there is meaningful only if the corrected model still describes a REAL
; scenario -- one where intervening return work and cleanup genuinely overwrite
; the slot after the foreign return. Were the constraints contradictory for an
; unrelated reason, the proof would hold for free and prove nothing.
;
; VERIFIED: sat. Witness: fail_code=1, reactivation_code=0,
; errno_after_reactivation=0, reported=1.

(set-option :produce-unsat-cores true)

(declare-const fail_code Int)
(declare-const reactivation_code Int)
(declare-const cleanup_code Int)
(declare-const errno_after_fail Int)
(declare-const captured_code Int)
(declare-const errno_after_reactivation Int)
(declare-const errno_after_cleanup Int)
(declare-const reported Int)

(assert (> fail_code 0))
(assert (= reactivation_code 0))
(assert (>= cleanup_code 0))
(assert (= errno_after_fail fail_code))
(assert (= errno_after_reactivation reactivation_code))
(assert (= errno_after_cleanup cleanup_code))
(assert (= captured_code errno_after_fail))
(assert (= reported captured_code))

; the property ITSELF, not its negation: a witness must exist
(assert (! (= reported fail_code) :named property_holds))
; and reactivation clobber must be real, not hypothetical
(assert (! (distinct errno_after_reactivation errno_after_fail)
           :named reactivation_clobber_is_real))

(check-sat)
(get-model)
