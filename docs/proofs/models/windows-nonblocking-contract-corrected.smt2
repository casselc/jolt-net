; Windows conditional claim: if the ioctlsocket binding matches its header, the
; command argument has the target's probed long-width FIONBIO representation,
; argp's first sizeof(u_long) bytes are fully initialized to a nonzero u_long,
; and Winsock honors its documented contract, then marking a handle immediately
; after a successful return cannot admit a still-blocking socket to a supposedly
; short lease.
;
; This models production exactly: handle_marked_nonblocking depends on the
; successful return, NOT on later would-block behavior. Winsock exposes no
; portable getter, so the ABI facts plus OS contract are explicit trusted
; premises. Native would-block tests are cross-boundary conformance evidence;
; they are not an in-process admission guard and are not smuggled into this
; proof.
;
; Bounded domain: one ioctlsocket transition, one handle mark, and one short
; operation admission.
;
; Expected: unsat, conditional on the named source, target, and OS premises.

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
           :named successful_transition_path_is_in_domain))
(assert (! ioctlsocket_binding_matches_header
           :named binding_matches_ioctlsocket_header))
(assert (! command_argument_represents_fionbio
           :named command_argument_has_probed_long_width_and_fionbio_bits))
(assert (! argp_contains_nonzero_u_long
           :named argp_contains_fully_initialized_nonzero_u_long))
(assert (! winsock_fionbio_contract_honored
           :named trusted_winsock_fionbio_semantics))
(assert
  (! (= mode_changed_to_nonblocking
        (and transition_returned_success
             ioctlsocket_binding_matches_header
             command_argument_represents_fionbio
             argp_contains_nonzero_u_long
             winsock_fionbio_contract_honored))
     :named documented_contract_effect))

; Exact production ordering: the successful return is the in-process boundary.
(assert
  (! (= handle_marked_nonblocking transition_returned_success)
     :named production_marks_from_successful_return))
(assert
  (! (= short_operation_admitted handle_marked_nonblocking)
     :named short_operation_requires_marked_handle))
(assert
  (! (= native_call_can_block
        (and short_operation_admitted (not mode_changed_to_nonblocking)))
     :named absent_nonblocking_mode_is_the_blocking_hazard))
(assert
  (! (= violation
        (and short_operation_admitted
             (not mode_changed_to_nonblocking)
             native_call_can_block))
     :named violation_definition))

(assert (! violation :named property_violated))

(check-sat)
(get-unsat-core)
