; Non-vacuity control for nonblocking-transition-corrected.smt2.
;
; The corrected query is unsat, which on its own is also what an over-strong
; model that forbids ALL useful work would report. This control forces the
; successful path and shows real non-blocking operation is still reachable.
;
; It carries BOTH targets at once, as two independent copies of the same
; bounded domain, so one satisfying model demonstrates that neither platform's
; postcondition is vacuously unsatisfiable:
;
;   posix_*   -- variadic boundary declared, F_SETFL succeeds, F_GETFL observes
;                the bit, handle marked, short operation admitted and not
;                blocking-capable;
;   windows_* -- ioctlsocket succeeds and a real accept/recv reported
;                would-block, which is the behavioral evidence that stands in
;                for the getter Winsock does not have.
;
; Expected: sat, with both useful_posix_operation and useful_windows_operation
; true.

(set-option :produce-unsat-cores true)

; --- POSIX copy -------------------------------------------------------------
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

; --- Windows copy -----------------------------------------------------------
(declare-const windows_transition_returned_success Bool)
(declare-const windows_nonblocking_observed Bool)
(declare-const windows_flag_readback_performed Bool)
(declare-const windows_would_block_evidence Bool)
(declare-const windows_postcondition_established Bool)
(declare-const windows_handle_marked_nonblocking Bool)
(declare-const windows_short_operation_admitted Bool)
(declare-const windows_native_call_can_block Bool)
(declare-const useful_windows_operation Bool)

(assert windows_transition_returned_success)
(assert (! (not windows_flag_readback_performed)
           :named windows_still_has_no_flag_getter))
(assert (! windows_would_block_evidence
           :named a_real_accept_reported_would_block))
(assert (! (=> windows_would_block_evidence windows_nonblocking_observed)
           :named would_block_is_behavioral_evidence_of_the_mode))
(assert
  (= windows_postcondition_established windows_would_block_evidence))
(assert
  (= windows_handle_marked_nonblocking
     (and windows_transition_returned_success
          windows_postcondition_established)))
(assert (= windows_short_operation_admitted windows_handle_marked_nonblocking))
(assert
  (= windows_native_call_can_block
     (and windows_short_operation_admitted
          (not windows_nonblocking_observed))))
(assert
  (! (= useful_windows_operation
        (and windows_short_operation_admitted
             (not windows_native_call_can_block)))
     :named useful_windows_path_definition))

; Both platforms must still permit real non-blocking work.
(assert (! (and useful_posix_operation useful_windows_operation)
           :named both_platforms_permit_useful_nonblocking_operation))

(check-sat)
(get-model)
