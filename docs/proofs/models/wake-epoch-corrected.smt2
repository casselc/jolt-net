; Claim: the sequence/epoch handshake prevents a wake admitted after await
; entry from disappearing in the drain/reset window.
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
; The producer increments a monotonic epoch before attempting the Boolean-gated
; byte write. After drain resets the gate, it restores a byte when the epoch
; changed since drain began. Await also compares the epoch captured at entry,
; covering the complementary case where drain began after the increment.
;
; Domain: one new wake, entry epoch E, producer/current epoch E+1, and a drain
; snapshot that saw either E or E+1. The producer may have coalesced against the
; old true gate. Expected: unsat for the lost-wake violation.

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
(declare-const wake_after_entry Bool)
(declare-const lost_wake Bool)

(assert (! (>= entry_epoch 0) :named epoch_is_nonnegative))
(assert
  (! (= producer_epoch (+ entry_epoch 1))
     :named producer_increments_epoch_first))
(assert
  (! (= current_epoch producer_epoch)
     :named one_wake_is_current_epoch))

; Drain can sample immediately before or immediately after this producer's
; increment; these are the two sides of the enter/drain race.
(assert
  (! (or (= drain_snapshot_epoch entry_epoch)
         (= drain_snapshot_epoch producer_epoch))
     :named drain_snapshot_covers_both_interleavings))

; Worst-case coalescing control: the producer sees the old true gate and does
; not itself add a byte.
(assert (! producer_saw_pending :named producer_coalesces_on_old_gate))
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
  (! (= wake_after_entry (> current_epoch entry_epoch))
     :named new_epoch_iff_wake_after_entry))
(assert
  (! (= lost_wake
        (and wake_after_entry (not byte_present_before_park)))
     :named violation_iff_new_epoch_has_no_byte))

(assert (! lost_wake :named property_violated))

(check-sat)
(get-unsat-core)
