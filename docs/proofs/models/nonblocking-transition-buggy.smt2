; Claim (negated below): a descriptor is never admitted to a supposedly short
; non-blocking lease unless both the variadic C ABI boundary is declared and a
; read-back observes O_NONBLOCK.
;
; Bounded domain: one Apple-arm64 fcntl F_SETFL transition, one handle mark, and
; one try-accept admission. This buggy control declares fcntl as a fixed
; three-argument function and treats rc=0 as proof that O_NONBLOCK took effect.
; Apple arm64 places arguments after `...` on the stack, so the fixed declaration
; need not deliver the third argument where fcntl reads it.
;
; Expected: sat. F_SETFL returns success, O_NONBLOCK is absent, the handle is
; nevertheless marked non-blocking, and a blocking-capable accept is admitted.

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
(assert (! (not varargs_boundary_declared)
           :named buggy_binding_declares_a_fixed_third_argument))
(assert (! setfl_returned_success
           :named native_return_value_looks_successful))
(assert (! (not nonblocking_observed)
           :named third_argument_was_not_effective))
(assert (! (not postcondition_checked)
           :named buggy_path_does_not_read_flags_back))

(assert
  (! (= handle_marked_nonblocking setfl_returned_success)
     :named buggy_marks_from_return_code_alone))
(assert
  (! (= short_operation_admitted handle_marked_nonblocking)
     :named marked_handle_admits_short_operation))
(assert
  (! (= native_call_can_block
        (and short_operation_admitted (not nonblocking_observed)))
     :named absent_flag_makes_accept_blocking_capable))
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

(assert (! violation :named property_violated))

(check-sat)
(get-model)
