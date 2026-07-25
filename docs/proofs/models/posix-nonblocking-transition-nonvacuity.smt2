; Non-vacuity control for posix-nonblocking-transition-corrected.smt2.
;
; The corrected query is unsat, which on its own is also what an over-strong
; model that forbids ALL useful work would report. This control forces the
; successful path and shows real non-blocking operation is still reachable.
;
; It forces the useful POSIX path: the variadic boundary is declared, F_SETFL
; succeeds, F_GETFL observes the bit, the handle is marked, and a short
; operation is admitted without becoming blocking-capable.
;
; Expected: sat, with useful_posix_operation true.

(set-option :produce-unsat-cores true)

(declare-const posix_apple_arm64 Bool)
(declare-const posix_c_function_is_variadic Bool)
(declare-const posix_varargs_boundary_declared Bool)
(declare-const posix_transition_returned_success Bool)
(declare-const posix_nonblocking_observed Bool)
(declare-const posix_flag_readback_performed Bool)
(declare-const posix_postcondition_established Bool)
(declare-const posix_handle_marked_nonblocking Bool)
(declare-const posix_short_operation_admitted Bool)
(declare-const posix_native_call_can_block Bool)
(declare-const useful_posix_operation Bool)

(assert posix_apple_arm64)
(assert posix_c_function_is_variadic)
(assert posix_varargs_boundary_declared)
(assert posix_transition_returned_success)
(assert (! posix_flag_readback_performed
           :named posix_reads_the_flags_back))
(assert (! posix_nonblocking_observed
           :named kernel_visible_nonblocking_bit_is_present))
(assert
  (= posix_postcondition_established
     (and posix_flag_readback_performed posix_nonblocking_observed)))
(assert
  (= posix_handle_marked_nonblocking
     (and posix_transition_returned_success posix_postcondition_established)))
(assert (= posix_short_operation_admitted posix_handle_marked_nonblocking))
(assert
  (= posix_native_call_can_block
     (and posix_short_operation_admitted (not posix_nonblocking_observed))))
(assert
  (! (= useful_posix_operation
        (and posix_short_operation_admitted
             (not posix_native_call_can_block)))
     :named useful_posix_path_definition))

(assert (! useful_posix_operation
           :named posix_permits_useful_nonblocking_operation))

(check-sat)
(get-model)
