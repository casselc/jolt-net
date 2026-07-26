; Claim: with a caller-supplied wake cursor, no publication can be both missed
; by the consumer's own read AND discarded as stale before the native wait.
;
; This is the claim wake-epoch-corrected.smt2 does NOT make. That model defines
; its violation as `(> current_epoch entry_epoch)` -- a wake strictly AFTER
; await entry -- so an execution in which the wake is already visible when
; await-ready begins is outside its domain by construction. The witnessed
; jolt-tcp latency lived exactly there: the reactor drained its :pending set,
; a worker then published a generation and woke, and only then did the reactor
; call await-ready. The wake predated the AWAIT but not the consumer's READ,
; and the two are different facts. This model separates them.
;
; The protocol has two halves, and neither is sufficient alone:
;
;   producer:  make state visible      THEN  advance the sequence  (wake!)
;   consumer:  sample the sequence     THEN  read that state       THEN await
;
; Together they give the boundary its meaning: a sequence value sampled before
; the read cannot name a publication the read could have missed.
;
; Times are integers on a total order; only their order is used, never any
; duration. There is no clock in this model because the defect is not a timing
; defect -- the production 1000 ms native cap merely sets what a lost wake
; COSTS, not whether one occurs.
;
; Domain: one publication, sequence 0 -> 1, one consumer turn.
; Expected: sat -- the corrected design still permits a genuine park.

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

; THE FIX. The stale/fresh boundary is the caller's cursor, not a value read at
; await entry. This single equation is what wake-cursor-ordering-buggy.smt2
; changes.
(assert (! (= entry_boundary cursor) :named boundary_is_the_caller_cursor))

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

; NON-VACUITY. The corrected model must not be unsat merely because it forbids
; every execution, and the fix must not degenerate into "always arm the wait" --
; which would replace a 1000 ms park with a spin and would still be `unsat`
; above. So ask for the execution the fix has to KEEP: the consumer's read did
; observe the publication, and the wait therefore parks with nothing in the
; receiver. A park is the correct outcome there; there is nothing left to wake
; for.
;
; This is the model-level twin of the "pre-cursor publication" control in
; test/jolt/net/wake_cursor_test.clj, which asserts the same wait is NOT armed.
;
; Expected: sat.
(assert (! (not lost_wake) :named property_holds))
(assert
  (! consumer_observed_state :named the_read_did_observe_the_publication))
(assert
  (! (not byte_at_native_entry) :named and_the_wait_still_parks))

(check-sat)
(get-model)
