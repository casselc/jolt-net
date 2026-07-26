; NEGATIVE CONTROL for wake-cursor-ordering-corrected.smt2: one fault, one
; witness. Exactly one assertion differs -- the stale/fresh boundary is read at
; await entry instead of being supplied by the caller -- and that alone makes
; the lost wake reachable.
;
; This is the defect jolt-tcp exhibited. The witness Z3 returns is the observed
; interleaving in miniature: the reactor drains its :pending set, a worker
; publishes a generation and calls wake!, and await-ready then reads a sequence
; that already includes that increment, classifies the byte as predating the
; wait, drains it, and parks. In production the park ends only at jolt.net's
; 1000 ms native safety tick, which is the `base + k * ~1000 ms` latency the
; jolt-http backpressure property measured.
;
; Note what is NOT changed to obtain the witness: the producer still publishes
; before waking, the consumer still samples a cursor before its read, the
; transport still writes and restores bytes the same way. A model that had to
; weaken several facts at once would not be evidence about this defect.
;
; Expected: sat.


(declare-const t_publish Int)
(declare-const t_increment Int)
(declare-const t_cursor_sample Int)
(declare-const t_consumer_read Int)
(declare-const t_await_entry Int)
(declare-const t_native_entry Int)

(declare-const current_seq Int)
(declare-const cursor Int)
(declare-const entry_boundary Int)
(declare-const drain_snapshot_seq Int)

(declare-const consumer_observed_state Bool)
(declare-const producer_writes_byte Bool)
(declare-const drain_runs Bool)
(declare-const drain_restores_byte Bool)
(declare-const boundary_restores_byte Bool)
(declare-const byte_at_native_entry Bool)
(declare-const lost_wake Bool)

(assert
  (! (distinct t_publish t_increment t_cursor_sample
                t_consumer_read t_await_entry t_native_entry)
     :named events_are_distinct_instants))

; --- producer discipline -----------------------------------------------------
; jolt.net.poller/signal-wake! advances the sequence; every producer publishes
; the state that wake describes BEFORE calling it. jolt-tcp's finish-work! is
; mark-pending! then wake-server!, and the poller's own submit! is documented
; "Publish state before its wake epoch".
(assert (! (< t_publish t_increment) :named producer_publishes_before_waking))
(assert (! (= current_seq 1) :named one_publication_advances_the_sequence))

; --- consumer discipline -----------------------------------------------------
; The cursor is sampled before the consumer reads producer-owned state, and
; that read completes before the await begins.
(assert
  (! (< t_cursor_sample t_consumer_read) :named consumer_samples_before_reading))
(assert
  (! (< t_consumer_read t_await_entry) :named consumer_reads_before_awaiting))
(assert
  (! (<= t_await_entry t_native_entry) :named await_precedes_native_entry))

; The value visible at the sample: 1 once the increment has happened, else 0.
(assert
  (! (= cursor (ite (< t_increment t_cursor_sample) 1 0))
     :named cursor_is_the_sequence_at_sample_time))

; THE FAULT, and the only difference from wake-cursor-ordering-corrected.smt2.
; The boundary is the sequence value read at await entry -- jolt-net's
; `entry-wake-sequence` before this task. The caller's cursor is still computed
; above and is simply not used, which is precisely the production defect: the
; consumer HAD a sound boundary available and await-ready substituted its own.
(assert
  (! (= entry_boundary (ite (< t_increment t_await_entry) 1 0))
     :named boundary_is_read_at_await_entry))

; The consumer's read observes the state iff the publication preceded it.
(assert
  (! (= consumer_observed_state (< t_publish t_consumer_read))
     :named read_observes_iff_publication_precedes_it))

; --- transport: write, pre-snapshot drain, restore ---------------------------
; The producer's byte is written against a gate that starts clear.
(assert (! producer_writes_byte :named fresh_gate_admits_the_byte))
; await-ready drains before taking its snapshot whenever a byte is present.
(assert (! (= drain_runs producer_writes_byte) :named drain_runs_iff_byte_present))
; That drain begins after the increment, since the byte it found is the one the
; increment accompanied, so its own snapshot cannot restore anything here.
(assert
  (! (= drain_snapshot_seq current_seq) :named drain_snapshot_is_current))
(assert
  (! (= drain_restores_byte (distinct drain_snapshot_seq current_seq))
     :named drain_restore_iff_sequence_advanced_during_drain))
(assert
  (! (= boundary_restores_byte (distinct entry_boundary current_seq))
     :named boundary_restore_iff_sequence_advanced_since_boundary))

; What is actually in the receiver when the native wait is entered: the
; producer's byte survives only if no drain consumed it.
(assert
  (! (= byte_at_native_entry
        (or (and producer_writes_byte (not drain_runs))
            drain_restores_byte
            boundary_restores_byte))
     :named byte_present_iff_unconsumed_or_restored))

; --- the violation -----------------------------------------------------------
; A lost wake is a publication the consumer's read did NOT observe, parked on
; with nothing in the receiver to release it. Either half alone is benign: an
; observed publication needs no wake, and an armed wait is not a park.
(assert
  (! (= lost_wake
        (and (not consumer_observed_state) (not byte_at_native_entry)))
     :named violation_iff_missed_publication_parks_unarmed))

(assert (! lost_wake :named property_violated))

(check-sat)
(get-model)
