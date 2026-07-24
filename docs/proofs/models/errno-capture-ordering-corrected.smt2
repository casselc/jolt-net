; Same invariant with the ordering jolt.net.error/checked enforces: the native
; error is captured IMMEDIATELY after the failing call, and rollback runs only
; afterwards, from a catch block that by construction executes later.
;
; VERIFIED: unsat. unsat core:
;   failing_call_sets_errno, capture_before_cleanup, property_violated
; The core is the useful part -- it shows the property follows from the capture
; ordering alone, and does not depend on the cleanup's code at all.

(declare-const fail_code Int)
(declare-const cleanup_code Int)
(declare-const errno_after_fail Int)
(declare-const errno_after_cleanup Int)
(declare-const reported Int)

(assert (! (> fail_code 0) :named fail_is_error))
(assert (! (> cleanup_code 0) :named cleanup_is_error))
(assert (! (distinct fail_code cleanup_code) :named codes_differ))

(assert (! (= errno_after_fail fail_code) :named failing_call_sets_errno))
(assert (! (= errno_after_cleanup cleanup_code) :named cleanup_overwrites_errno))

; CORRECTED ORDERING: capture reads the slot before cleanup can touch it
(assert (! (= reported errno_after_fail) :named capture_before_cleanup))

; negation of the property
(assert (! (not (= reported fail_code)) :named property_violated))
