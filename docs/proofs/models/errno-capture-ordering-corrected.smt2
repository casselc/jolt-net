; Same invariant with the ordering enforced by
; {:capture-native-error true}: the native result and error are paired in the
; foreign return transition, before later runtime/FFI work or rollback can
; overwrite the thread's slot.
;
; VERIFIED: unsat. Expected core:
;   failing_call_sets_errno, capture_in_foreign_return, reported_from_pair,
;   property_violated
; The core is the useful part -- it shows the property follows from the capture
; boundary alone and does not depend on reactivation or cleanup's code.

(set-option :produce-unsat-cores true)

(declare-const fail_code Int)
(declare-const reactivation_code Int)
(declare-const cleanup_code Int)
(declare-const errno_after_fail Int)
(declare-const captured_code Int)
(declare-const errno_after_reactivation Int)
(declare-const errno_after_cleanup Int)
(declare-const reported Int)

(assert (! (> fail_code 0) :named fail_is_error))
(assert (! (>= reactivation_code 0) :named reactivation_slot_is_numeric))
(assert (! (>= cleanup_code 0) :named cleanup_slot_is_numeric))
(assert (! (distinct fail_code reactivation_code) :named reactivation_clobbers))

(assert (! (= errno_after_fail fail_code) :named failing_call_sets_errno))
(assert (! (= errno_after_reactivation reactivation_code)
           :named reactivation_overwrites_errno))
(assert (! (= errno_after_cleanup cleanup_code) :named cleanup_overwrites_errno))

; CORRECTED BOUNDARY: the captured element is formed from the failure slot
; before source-level Scheme resumes. Reporting consumes the pair, not the
; later thread-local slot.
(assert (! (= captured_code errno_after_fail) :named capture_in_foreign_return))
(assert (! (= reported captured_code) :named reported_from_pair))

; negation of the property
(assert (! (not (= reported fail_code)) :named property_violated))

(check-sat)
(get-unsat-core)
