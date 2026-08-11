; Buggy control for windows-nonblocking-contract-corrected.smt2.
;
; Production really does mark the handle on a successful ioctlsocket return.
; That ordering is not itself the bug: it is safe only under the explicitly
; source/ABI facts and the documented Winsock FIONBIO contract. This control
; removes the load-bearing value premise: the u_long at argp is zero. That is a
; valid FIONBIO call requesting blocking mode, so ioctlsocket may return success.
;
; Bounded domain: one ioctlsocket transition, one handle mark, and one
; try-accept admission. Behavioral evidence is deliberately later than marking
; in production and is not an admission guard.
;
; Expected: sat. The call succeeds while requesting blocking mode, the mode
; remains blocking, and production's successful-return mark admits a
; blocking-capable accept.

(set-option :produce-unsat-cores true)

(declare-const transition_returned_success Bool)
(declare-const ioctlsocket_binding_matches_header Bool)
(declare-const command_argument_represents_fionbio Bool)
(declare-const argp_contains_nonzero_u_long Bool)
(declare-const winsock_fionbio_contract_honored Bool)
(declare-const mode_changed_to_nonblocking Bool)
(declare-const handle_marked_nonblocking Bool)
(declare-const short_operation_admitted Bool)
(declare-const native_call_can_block Bool)
(declare-const violation Bool)

(assert (! transition_returned_success
           :named native_return_value_looks_successful))
(assert (! ioctlsocket_binding_matches_header
           :named binding_matches_ioctlsocket_header))
(assert (! command_argument_represents_fionbio
           :named command_argument_has_probed_long_width_and_fionbio_bits))
(assert (! (not argp_contains_nonzero_u_long)
           :named buggy_argp_requests_blocking_mode))
(assert (! winsock_fionbio_contract_honored
           :named documented_contract_would_hold_for_a_valid_call))
(assert
  (! (= mode_changed_to_nonblocking
        (and transition_returned_success
             ioctlsocket_binding_matches_header
             command_argument_represents_fionbio
             argp_contains_nonzero_u_long
             winsock_fionbio_contract_honored))
     :named winsock_contract_applies_to_the_requested_mode))

(assert
  (! (= handle_marked_nonblocking transition_returned_success)
     :named production_marks_from_successful_return))
(assert
  (! (= short_operation_admitted handle_marked_nonblocking)
     :named marked_handle_admits_short_operation))
(assert
  (! (= native_call_can_block
        (and short_operation_admitted (not mode_changed_to_nonblocking)))
     :named absent_nonblocking_mode_makes_accept_blocking_capable))
(assert
  (! (= violation
        (and short_operation_admitted
             (not mode_changed_to_nonblocking)
             native_call_can_block))
     :named violation_definition))

(assert (! violation :named property_violated))

(check-sat)
(get-model)
