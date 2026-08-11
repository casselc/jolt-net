; Claim: on a BYTE-ONLY wake transport, an await cannot park in the native wait
; with nothing left able to wake it, once close has begun.
;
; Source anchors:
;   jolt.net.wake/terminal-wake            = :byte-only on Windows
;   jolt.net.poller/close!                 steps 1 and 3 (CAS, then publish)
;   jolt.net.poller/publish-terminal-wake! the unconditional terminal byte
;   jolt.net.poller/await-ready            the pre-entry terminal check, placed
;                                          after the last drain and before the
;                                          native call
;
; Environmental premises, stated because the whole claim rests on them:
;   1. Retiring a connected datagram SENDER is NOT observable to its peer. There
;      is no hangup, no error, and no readiness event. This is the premise that
;      distinguishes this model from the POSIX self-pipe, where closing the
;      write end additionally makes the read end report POLLHUP; see
;      posix-pipe-hangup-independence-control.smt2, which is deliberately a
;      SEPARATE claim and must not be read as covering this one.
;   2. The native wait is level-triggered on the receiver: a datagram that is in
;      the receive buffer at any point during the wait -- whether it arrived
;      before entry or while parked -- completes the wait.
;   3. A byte can only be consumed by a drain that runs after it was published.
;
; Bounded domain: one close, one await, four distinct protocol events numbered
; 0..3. One terminal byte. Registration mutations, the wake epoch, writer
; admission counting, and the readiness decoder are all abstracted away; they
; are modelled separately (wake-epoch-*, wake-pair-*, readiness-*).
;
; Limits: this is a bounded interleaving argument over a single await and a
; single close, not a proof about Winsock datagram delivery itself. It assumes
; the published datagram is delivered to the connected loopback peer; that
; assumption is what the native W4 gate exercises rather than what this model
; establishes.
;
; Expected: unsat. The asserted violation is "the await parked, no wake can
; reach it, and close is waiting for it to exit."

(set-option :produce-unsat-cores true)

(declare-const close_cas_step Int)
(declare-const publish_step Int)
(declare-const await_drain_step Int)
(declare-const await_phase_read_step Int)

(declare-const await_parks Bool)
(declare-const byte_consumed_before_entry Bool)
(declare-const wake_reaches_await Bool)
(declare-const close_awaiting_exit Bool)
(declare-const await_stranded Bool)

(assert (and (<= 0 close_cas_step) (<= close_cas_step 3)))
(assert (and (<= 0 publish_step) (<= publish_step 3)))
(assert (and (<= 0 await_drain_step) (<= await_drain_step 3)))
(assert (and (<= 0 await_phase_read_step) (<= await_phase_read_step 3)))
(assert (distinct close_cas_step publish_step
                  await_drain_step await_phase_read_step))

; --- close's side ------------------------------------------------------------
; The terminal byte is published only after close has atomically won the
; lifecycle transition, and it is published unconditionally rather than through
; the coalescing gate.
(assert
  (! (< close_cas_step publish_step)
     :named publish_follows_the_winning_transition))

; Having won the transition, close waits for the await to exit. That is what
; makes a stranded await a real defect rather than a latency question.
(assert (! close_awaiting_exit :named close_waits_for_the_await_to_exit))

; --- the await's side --------------------------------------------------------
; The pre-entry terminal check sits AFTER the last drain and BEFORE the native
; call. This ordering is the entire mechanism.
(assert
  (! (< await_drain_step await_phase_read_step)
     :named phase_check_follows_the_last_drain))

; An await parks only if that check still observed an open lifecycle.
(assert
  (! (= await_parks (< await_phase_read_step close_cas_step))
     :named await_parks_iff_it_read_an_open_lifecycle))

; --- delivery ----------------------------------------------------------------
; Premise 3: only a drain that runs after publication can consume the byte.
(assert
  (! (= byte_consumed_before_entry (< publish_step await_drain_step))
     :named byte_consumed_only_by_a_later_drain))

; Premise 2: level-triggered. A byte not consumed before entry reaches the wait,
; whether it was already queued or arrives while parked. Premise 1 is expressed
; by omission: sender retirement contributes NO other wake term here.
(assert
  (! (= wake_reaches_await (not byte_consumed_before_entry))
     :named byte_is_the_only_wake_and_it_is_level_triggered))

(assert
  (! (= await_stranded
        (and await_parks (not wake_reaches_await) close_awaiting_exit))
     :named stranded_iff_parked_unwakeable_while_close_waits))

; Negation of the safety claim.
(assert (! await_stranded :named property_violated))

(check-sat)
(get-unsat-core)
