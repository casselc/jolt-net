; Non-vacuity control for errno-capture-ordering-corrected.smt2.
;
; UNSAT there is meaningful only if the corrected model still describes a REAL
; scenario -- one where cleanup runs and genuinely would have clobbered a
; different code. Were the constraints contradictory for some unrelated reason,
; the proof would hold for free and prove nothing.
;
; VERIFIED: sat. Witness: fail_code=1, cleanup_code=2, errno_after_cleanup=2,
; reported=1 -- a real clobber occurred and the failure's code was still
; reported.

(set-option :produce-unsat-cores true)

(declare-const fail_code Int)
(declare-const cleanup_code Int)
(declare-const errno_after_fail Int)
(declare-const errno_after_cleanup Int)
(declare-const reported Int)

(assert (> fail_code 0))
(assert (> cleanup_code 0))
(assert (distinct fail_code cleanup_code))
(assert (= errno_after_fail fail_code))
(assert (= errno_after_cleanup cleanup_code))
(assert (= reported errno_after_fail))

; the property ITSELF, not its negation: a witness must exist
(assert (! (= reported fail_code) :named property_holds))
; and the clobber must be real, not hypothetical
(assert (! (distinct errno_after_cleanup errno_after_fail) :named clobber_is_real))

(check-sat)
(get-model)
