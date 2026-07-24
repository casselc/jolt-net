; Non-vacuity control for nonblocking-transition-corrected.smt2.
;
; Force the successful useful path: the variadic boundary is declared,
; F_SETFL returns success, F_GETFL observes O_NONBLOCK, the handle is marked,
; and a short operation is admitted without being blocking-capable.
;
; Expected: sat with useful_nonblocking_operation=true.

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
(declare-const useful_nonblocking_operation Bool)

(assert apple_arm64)
(assert c_function_is_variadic)
(assert varargs_boundary_declared)
(assert setfl_returned_success)
(assert (! nonblocking_observed
           :named kernel_visible_nonblocking_bit_is_present))
(assert postcondition_checked)
(assert
  (= handle_marked_nonblocking
     (and setfl_returned_success postcondition_checked
          nonblocking_observed)))
(assert (= short_operation_admitted handle_marked_nonblocking))
(assert
  (= native_call_can_block
     (and short_operation_admitted (not nonblocking_observed))))
(assert
  (! (= useful_nonblocking_operation
        (and short_operation_admitted (not native_call_can_block)))
     :named useful_path_definition))

(assert (! useful_nonblocking_operation
           :named useful_nonblocking_operation_is_reachable))

(check-sat)
(get-model)
