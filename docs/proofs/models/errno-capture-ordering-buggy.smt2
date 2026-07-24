; Invariant: the error a caller reports is the error of the operation that
; FAILED, not the error of the cleanup that ran while unwinding.
;
; This models the CURRENT SHAPE OF THE DEFECT in teensyp.ffi-net's constructors:
; a failed bind calls close() to roll back the socket, and only then reads errno.
;
; errno is a single per-thread slot, valid only until the next native call --
; and close() is a native call.
;
; VERIFIED: sat. Counterexample: fail_code=1, cleanup_code=2, reported=2.
; The reported code is the cleanup's, so the property is violated.

(set-option :produce-unsat-cores true)

(declare-const fail_code Int)      ; e.g. EADDRINUSE from the failing bind
(declare-const cleanup_code Int)   ; e.g. EBADF from the rollback close
(declare-const errno_after_fail Int)
(declare-const errno_after_cleanup Int)
(declare-const reported Int)

; both are real error codes; the interesting case is that they differ -- were
; they equal the bug would be invisible, not absent
(assert (> fail_code 0))
(assert (> cleanup_code 0))
(assert (distinct fail_code cleanup_code))

; the slot's value over time
(assert (= errno_after_fail fail_code))
(assert (= errno_after_cleanup cleanup_code))

; BUGGY ORDERING: capture happens after the rollback close
(assert (! (= reported errno_after_cleanup) :named buggy_capture_after_cleanup))

; negation of the property "the reported code is the failure's code"
(assert (! (not (= reported fail_code)) :named property_violated))

(check-sat)
(get-model)
