; Claim: on EVERY supported target, no descriptor is admitted to a supposedly
; short non-blocking lease unless that target's own postcondition evidence says
; the descriptor really left blocking mode -- and, where the transition goes
; through a variadic C function, unless its ABI boundary is declared.
;
; This is the platform-neutral form of the postcondition. The two targets do
; not establish it the same way, and the model says so rather than assuming a
; POSIX read-back exists everywhere:
;
;   POSIX   -- fcntl(F_SETFL), then F_GETFL re-reads O_NONBLOCK in process.
;   Windows -- ioctlsocket(FIONBIO). Winsock exposes NO portable getter for a
;              socket's blocking mode, so there is nothing to read back. The
;              evidence is behavioral: a real accept/recv returning would-block
;              instead of parking the thread is direct proof the descriptor is
;              non-blocking.
;
; Bounded domain: one transition, one handle mark, one short-operation
; admission. nonblocking_observed stays FREE -- the model never assumes a
; successful native return implies the mode changed. That is what makes the
; postcondition a fail-closed guard rather than a restatement of the return
; code, on either platform.
;
; Expected: unsat. The asserted violation is either an undeclared variadic ABI
; boundary on the distinguishing POSIX target, or a short operation admitted on
; a descriptor that never actually left blocking mode.

(set-option :produce-unsat-cores true)

(declare-const windows Bool)
(declare-const apple_arm64 Bool)
(declare-const c_function_is_variadic Bool)
(declare-const varargs_boundary_declared Bool)
(declare-const transition_returned_success Bool)
(declare-const nonblocking_observed Bool)
(declare-const flag_readback_performed Bool)
(declare-const would_block_evidence Bool)
(declare-const postcondition_established Bool)
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

; Winsock has no portable getter for a socket's blocking mode, so no read-back
; is available there. Inventing one would be a worse oracle than the value it
; checks.
(assert (! (=> windows (not flag_readback_performed))
           :named windows_has_no_flag_getter))
; POSIX always re-reads the flags before any handle is marked.
(assert (! (=> (not windows) flag_readback_performed)
           :named posix_reads_flags_back_before_marking))
; The Windows substitute, and the reason it is admissible: a call that reported
; would-block did not block, and a descriptor that does not block is not in
; blocking mode.
(assert (! (=> would_block_evidence nonblocking_observed)
           :named would_block_is_behavioral_evidence_of_the_mode))

(assert
  (! (= postcondition_established
        (ite windows
             would_block_evidence
             (and flag_readback_performed nonblocking_observed)))
     :named platform_neutral_postcondition_definition))
(assert
  (! (= handle_marked_nonblocking
        (and transition_returned_success postcondition_established))
     :named mark_iff_success_and_established_postcondition))
(assert
  (! (= short_operation_admitted handle_marked_nonblocking)
     :named short_operation_requires_marked_handle))
(assert
  (! (= native_call_can_block
        (and short_operation_admitted (not nonblocking_observed)))
     :named absent_nonblocking_mode_is_the_blocking_hazard))
; Only the POSIX transition runs through a variadic C function; ioctlsocket is
; an ordinary fixed-arity call, so this branch is scoped to non-Windows.
(assert
  (! (= declaration_violation
        (and (not windows) apple_arm64 c_function_is_variadic
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
