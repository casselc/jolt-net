; POSIX claim: no descriptor is admitted to a supposedly short non-blocking
; lease unless F_GETFL has read O_NONBLOCK back after F_SETFL, and the variadic
; fcntl ABI boundary is declared.
;
; This model is intentionally POSIX-only. Unlike Winsock, production can inspect
; the state of this exact descriptor before marking it. nonblocking_observed
; remains FREE: if the bit is absent, the implementation fails closed and the
; handle is never marked.
;
; Bounded domain: one fcntl transition, one read-back, one handle mark, and one
; short-operation admission.
;
; Expected: unsat. The asserted violation is either an undeclared variadic ABI
; boundary on Apple arm64 or a short operation admitted while O_NONBLOCK is
; absent.

(set-option :produce-unsat-cores true)

(declare-const apple_arm64 Bool)
(declare-const c_function_is_variadic Bool)
(declare-const varargs_boundary_declared Bool)
(declare-const transition_returned_success Bool)
(declare-const nonblocking_observed Bool)
(declare-const flag_readback_performed Bool)
(declare-const handle_marked_nonblocking Bool)
(declare-const short_operation_admitted Bool)
(declare-const native_call_can_block Bool)
(declare-const declaration_violation Bool)
(declare-const lease_violation Bool)
(declare-const violation Bool)

(assert (! transition_returned_success
           :named successful_transition_path_is_in_domain))
(assert (! varargs_boundary_declared
           :named binding_declares_varargs_after_two))
(assert (! c_function_is_variadic
           :named fcntl_has_two_fixed_arguments_then_varargs))
(assert (! flag_readback_performed
           :named posix_reads_flags_back_before_marking))
(assert
  (! (= handle_marked_nonblocking
        (and transition_returned_success
             flag_readback_performed
             nonblocking_observed))
     :named mark_requires_success_and_observed_nonblocking_bit))
(assert
  (! (= short_operation_admitted handle_marked_nonblocking)
     :named short_operation_requires_marked_handle))
(assert
  (! (= native_call_can_block
        (and short_operation_admitted (not nonblocking_observed)))
     :named absent_nonblocking_mode_is_the_blocking_hazard))
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
