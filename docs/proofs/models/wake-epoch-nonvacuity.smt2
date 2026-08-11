; Non-vacuity control for wake-epoch-corrected.smt2.
;
; TRANSPORT INDEPENDENCE (task W4). The epoch handshake is arithmetic over the
; poller's own shared counter and Boolean gate. It names no descriptor, no
; syscall and no delivery guarantee, so it holds unchanged for the POSIX
; self-pipe and for the Windows connected loopback datagram pair.
;
; It does NOT cover close's terminal wake, which deliberately BYPASSES this
; coalescing gate: the gate can read `true` for a producer whose own send has
; not landed, and close needs a byte that is definitely in the receiver. That is
; a separate claim -- see windows-terminal-wake-corrected.smt2 for delivery and
; close-completion-ordering-corrected.smt2 for the publish/retire order.
;
; Force the exact Boolean coalescing race: await captured entry epoch 0, drain
; also sampled 0, a concurrent producer advanced to epoch 1 while the old gate
; remained true and wrote no byte, then reset observed the epoch change and
; restored one. Await can progress instead of parking indefinitely.
;
; Expected: sat, with drain_restores_byte=true, byte_present_before_park=true,
; and await_progresses=true.

(set-option :produce-unsat-cores true)

(declare-const entry_epoch Int)
(declare-const producer_epoch Int)
(declare-const drain_snapshot_epoch Int)
(declare-const current_epoch Int)
(declare-const producer_saw_pending Bool)
(declare-const producer_writes_byte Bool)
(declare-const drain_restores_byte Bool)
(declare-const entry_restores_byte Bool)
(declare-const byte_present_before_park Bool)
(declare-const await_progresses Bool)

(assert (= entry_epoch 0))
(assert (= producer_epoch 1))
(assert (= drain_snapshot_epoch 0))
(assert (= current_epoch producer_epoch))
(assert producer_saw_pending)
(assert
  (! (= producer_writes_byte (not producer_saw_pending))
     :named boolean_gate_controls_write))
(assert
  (! (= drain_restores_byte
        (distinct drain_snapshot_epoch current_epoch))
     :named drain_restore_iff_epoch_advanced))
(assert
  (! (= entry_restores_byte
        (distinct entry_epoch current_epoch))
     :named entry_restore_iff_epoch_advanced))
(assert
  (! (= byte_present_before_park
        (or producer_writes_byte
            drain_restores_byte
            entry_restores_byte))
     :named byte_iff_written_or_restored))
(assert
  (! (= await_progresses byte_present_before_park)
     :named await_progresses_iff_byte_is_observable))

; Require a genuine restore, not a direct producer write or a vacuous outcome.
(assert (! (not producer_writes_byte) :named producer_really_coalesced))
(assert (! drain_restores_byte :named drain_really_restores))
(assert (! await_progresses :named progress_reachable))

(check-sat)
(get-model)
