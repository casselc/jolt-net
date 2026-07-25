; Non-vacuity control for windows-nonblocking-contract-corrected.smt2.
;
; The useful production path is forced under the target/OS premises. The model
; also records the real ordering: the handle is marked before a later native
; would-block observation supplies cross-boundary conformance evidence.
;
; Expected: sat, with useful_windows_operation true and mark_step <
; behavioral_evidence_step.

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
(declare-const would_block_behavior_observed Bool)
(declare-const mark_step Int)
(declare-const behavioral_evidence_step Int)
(declare-const useful_windows_operation Bool)

(assert transition_returned_success)
(assert ioctlsocket_binding_matches_header)
(assert command_argument_represents_fionbio)
(assert argp_contains_nonzero_u_long)
(assert winsock_fionbio_contract_honored)
(assert
  (= mode_changed_to_nonblocking
     (and transition_returned_success
          ioctlsocket_binding_matches_header
          command_argument_represents_fionbio
          argp_contains_nonzero_u_long
          winsock_fionbio_contract_honored)))
(assert (= handle_marked_nonblocking transition_returned_success))
(assert (= short_operation_admitted handle_marked_nonblocking))
(assert
  (= native_call_can_block
     (and short_operation_admitted (not mode_changed_to_nonblocking))))

; Runtime conformance evidence is useful but occurs after the production mark.
(assert would_block_behavior_observed)
(assert (= mark_step 1))
(assert (= behavioral_evidence_step 2))
(assert (< mark_step behavioral_evidence_step))
(assert
  (! (= useful_windows_operation
        (and short_operation_admitted
             (not native_call_can_block)
             would_block_behavior_observed))
     :named useful_windows_path_definition))
(assert (! useful_windows_operation
           :named windows_permits_useful_nonblocking_operation))

(check-sat)
(get-model)
