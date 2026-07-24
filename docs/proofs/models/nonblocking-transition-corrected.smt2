; Claim: on Apple arm64 the fcntl binding declares its two-fixed-argument
; variadic boundary, and no supposedly short non-blocking operation is admitted
; unless a post-F_SETFL F_GETFL observes O_NONBLOCK.
;
; Bounded domain: one transition, one handle mark, and one operation admission.
; The model intentionally does not assume that a successful F_SETFL implies the
; bit is present: nonblocking_observed remains free. That makes the read-back a
; fail-closed guard even for a future toolchain, libc, or binding regression.
;
; Expected: unsat. The asserted violation is either an ABI declaration mismatch
; on the distinguishing target or a short operation admitted without the bit.

(set-option :produce-unsat-cores true)

(declare-const apple_arm64 Bool)
(declare-const c_function_is_variadic Bool)
(declare-const varargs_boundary_declared Bool)
(declare-const setfl_returned_success Bool)
(declare-const nonblocking_observed Bool)
(declare-const postcondition_checked Bool)
(declare-const handle_marked_nonblocking Bool)
(declare-const short_operation_admitted Bool)
(declare-const native_call_can_block Bool)
(declare-const declaration_violation Bool)
(declare-const lease_violation Bool)
(declare-const violation Bool)

(assert (! apple_arm64 :named apple_arm64_is_the_distinguishing_target))
(assert (! c_function_is_variadic :named fcntl_has_two_fixed_arguments_then_varargs))
(assert (! varargs_boundary_declared
           :named binding_declares_varargs_after_two))
(assert (! setfl_returned_success
           :named successful_transition_path_is_in_domain))
(assert (! postcondition_checked
           :named f_getfl_readback_follows_f_setfl))

(assert
  (! (= handle_marked_nonblocking
        (and setfl_returned_success postcondition_checked
             nonblocking_observed))
     :named mark_iff_success_and_observed_postcondition))
(assert
  (! (= short_operation_admitted handle_marked_nonblocking)
     :named short_operation_requires_marked_handle))
(assert
  (! (= native_call_can_block
        (and short_operation_admitted (not nonblocking_observed)))
     :named absent_flag_is_the_blocking_hazard))
(assert
  (! (= declaration_violation
        (and apple_arm64 c_function_is_variadic
             (not varargs_boundary_declared)))
     :named declaration_violation_definition))
(assert
  (! (= lease_violation
        (and short_operation_admitted (not nonblocking_observed)
             native_call_can_block))
     :named lease_violation_definition))
(assert
  (! (= violation (or declaration_violation lease_violation))
     :named violation_definition))

; Negation of the bounded safety claim.
(assert (! violation :named property_violated))

(check-sat)
(get-unsat-core)
