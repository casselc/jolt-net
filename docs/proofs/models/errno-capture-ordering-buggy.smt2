; Invariant: the error a caller reports is the error of the operation that
; FAILED, not the value left after intervening runtime/FFI return work.
;
; This models both Windows W1 defects: blocking connect and a first-use bind
; fail, then a source-level WSAGetLastError read observes a different value
; (zero in the concrete witnesses) even before rollback cleanup.
;
; errno/last-error is a per-thread slot, valid only until intervening runtime or
; native work.
;
; VERIFIED: sat. Counterexample includes fail_code > 0,
; reactivation_code=0, reported=0.

(set-option :produce-unsat-cores true)

(declare-const fail_code Int)      ; e.g. WSAECONNREFUSED
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

; the slot's value over time
(assert (= errno_after_fail fail_code))
(assert (= errno_after_reactivation reactivation_code))
(assert (= errno_after_cleanup cleanup_code))

; BUGGY BOUNDARY: the "capture" is a separate source-level accessor after
; intervening return work, so it pairs the failed result with the wrong slot.
(assert (! (= captured_code errno_after_reactivation)
           :named buggy_capture_after_reactivation))
(assert (! (= reported captured_code) :named reported_from_late_capture))

; negation of the property "the reported code is the failure's code"
(assert (! (not (= reported fail_code)) :named property_violated))

(check-sat)
(get-model)
