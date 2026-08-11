; Claim (negated below): a wake admitted after await entry cannot disappear in
; the drain/reset window before the native poll parks.
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
; Buggy protocol: one Boolean coalesces wake bytes. Start with pending=true and
; one old byte. Await drains that byte; a concurrent producer still observes
; pending=true and therefore writes nothing; await then resets pending=false and
; parks. The new wake has no remaining byte.
;
; Bounded domain: four distinct steps numbered 0..3.
; Expected: sat, with drain=0, producer-observe=1, reset=2, park=3.

(set-option :produce-unsat-cores true)

(declare-const drain_step Int)
(declare-const producer_observe_step Int)
(declare-const pending_reset_step Int)
(declare-const native_park_step Int)
(declare-const pending_seen_by_producer Bool)
(declare-const producer_writes_byte Bool)
(declare-const byte_present_before_park Bool)
(declare-const wake_after_entry Bool)
(declare-const lost_wake Bool)

(assert (and (<= 0 drain_step) (<= drain_step 3)))
(assert (and (<= 0 producer_observe_step)
             (<= producer_observe_step 3)))
(assert (and (<= 0 pending_reset_step) (<= pending_reset_step 3)))
(assert (and (<= 0 native_park_step) (<= native_park_step 3)))
(assert (distinct drain_step producer_observe_step
                  pending_reset_step native_park_step))

; The producer races after the old byte was drained but before the Boolean gate
; is reset. It sees the old true value and coalesces its own wake.
(assert
  (! (= pending_seen_by_producer
        (and (< drain_step producer_observe_step)
             (< producer_observe_step pending_reset_step)))
     :named producer_sees_stale_true_gate))
(assert
  (! (= producer_writes_byte (not pending_seen_by_producer))
     :named boolean_gate_controls_write))
(assert
  (! (= wake_after_entry
        (< producer_observe_step native_park_step))
     :named concurrent_wake_iff_before_park))

; The old byte was drained, so only this producer could leave a byte.
(assert
  (! (= byte_present_before_park producer_writes_byte)
     :named byte_after_drain_iff_producer_writes))
(assert
  (! (= lost_wake
        (and wake_after_entry
             (< pending_reset_step native_park_step)
             (not byte_present_before_park)))
     :named violation_iff_new_epoch_has_no_byte))

(assert (! lost_wake :named property_violated))

(check-sat)
(get-model)
