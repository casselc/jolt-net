; Control (EXISTENCE): the POSIX self-pipe has a SECOND, independent
; cancellation path that the Windows datagram transport does not have.
;
; Why this file exists at all
; ---------------------------
;
; It would be convenient to fold POSIX and Windows into one "wake transport"
; claim, and it would be wrong. The two differ in exactly one premise, and that
; premise is load-bearing:
;
;   POSIX self-pipe    retiring the WRITE end makes the retained READ end report
;                      POLLHUP. Cancellation therefore has two sources: the
;                      published byte, and sender retirement itself.
;   Windows datagram   retiring the SENDER is invisible to its connected peer.
;                      No hangup, no error, no readiness event. Cancellation has
;                      exactly ONE source: the published byte. Any WSAECONNRESET
;                      the receiver may later surface comes from a best-effort
;                      ICMP port-unreachable and is not a protocol guarantee --
;                      jolt.net.wake treats it as benign drain noise precisely
;                      so that nothing can come to depend on it.
;
; This is recorded in the source as jolt.net.wake/terminal-wake, which is
; :byte-or-hangup on POSIX and :byte-only on Windows.
;
; This model is a POSITIVE EXISTENCE control, not a safety claim, which is why
; it is a single file rather than a buggy/corrected/nonvacuity trio: it has no
; corrected violation query to be unsat. It demonstrates that on POSIX a run
; exists in which the terminal byte IS consumed by the await's own pre-entry
; drain -- the exact interleaving that strands a byte-only transport when the
; pre-entry lifecycle check is absent, see windows-terminal-wake-buggy.smt2 --
; and the await is nevertheless released, by hangup alone.
;
; The point is NOT that POSIX is safer. The corrected design does not use this
; second path: the pre-entry terminal check in jolt.net.poller/await-ready is
; unconditional and covers both transports, which is what lets one close
; sequence serve both. The point is that this premise must never be silently
; borrowed by a Windows claim, and that the Windows models must stand without
; it. windows-terminal-wake-corrected.smt2 does: it contains no hangup term.
;
; Bounded domain: one close, one await, four distinct events numbered 0..3.
; Same numbering as windows-terminal-wake-*.smt2 so the two are directly
; comparable.
;
; Limits: this asserts POSIX pipe semantics as a PREMISE, from the POSIX
; specification of a pipe whose write end has been closed. It does not derive
; them, and it says nothing about SIGPIPE, which is a separate concern handled
; by ordering the sender's retirement after the writer drain (wake-pair-*).
;
; Expected: sat. `(get-model)` names a run in which the byte was consumed early
; and hangup alone released the await.

(set-option :produce-models true)

(declare-const close_cas_step Int)
(declare-const publish_step Int)
(declare-const await_drain_step Int)
(declare-const sender_retire_step Int)

(declare-const byte_consumed_before_entry Bool)
(declare-const hangup_available Bool)
(declare-const wake_reaches_await Bool)
(declare-const released_by_hangup_alone Bool)

(assert (and (<= 0 close_cas_step) (<= close_cas_step 3)))
(assert (and (<= 0 publish_step) (<= publish_step 3)))
(assert (and (<= 0 await_drain_step) (<= await_drain_step 3)))
(assert (and (<= 0 sender_retire_step) (<= sender_retire_step 3)))
(assert (distinct close_cas_step publish_step
                  await_drain_step sender_retire_step))

; Close's own ordering, identical to the Windows models.
(assert (! (< close_cas_step publish_step)
           :named publish_follows_the_winning_transition))
(assert (! (< publish_step sender_retire_step)
           :named publish_precedes_sender_retirement))

; THE POSIX-ONLY PREMISE. This term is deliberately absent from every Windows
; model in this directory.
(assert (! hangup_available :named posix_pipe_write_close_yields_pollhup))

(assert (! (= byte_consumed_before_entry (< publish_step await_drain_step))
           :named byte_consumed_only_by_a_later_drain))

; Two independent sources on POSIX.
(assert
  (! (= wake_reaches_await
        (or (not byte_consumed_before_entry) hangup_available))
     :named posix_has_byte_or_hangup))

(assert
  (! (= released_by_hangup_alone
        (and byte_consumed_before_entry wake_reaches_await))
     :named released_by_hangup_alone_iff_byte_was_already_gone))

; Demand the interesting run: the byte was consumed early, and the await was
; released anyway.
(assert (! byte_consumed_before_entry :named the_terminal_byte_was_drained_early))
(assert (! released_by_hangup_alone :named and_hangup_alone_still_released_it))

(check-sat)
(get-model)
