; Claim: one atomic admission gate plus writer drain and write-first/read-last
; retirement prevents the wake RECEIVER from closing while an admitted
; writer remains.
;
; TRANSPORT INDEPENDENCE (task W4). Every premise below -- one CAS admission
; gate shared with retirement, the handle lease released before the counted
; writer admission, retirement before sender close, and sender before receiver
; -- is a property of jolt.net.poller's shared writer-admission protocol, not of
; any one transport. It holds unchanged for the POSIX self-pipe and for the
; Windows connected loopback datagram pair, so the naming here is generic.
;
; What this family does NOT cover, and must not be read as covering:
;   - that retiring the sender is observable to the receiver. It is on POSIX
;     (POLLHUP) and is NOT on Windows. See
;     posix-pipe-hangup-independence-control.smt2 and
;     windows-terminal-wake-corrected.smt2.
;   - SIGPIPE. Ordering the sender's retirement after the writer drain is what
;     makes closing the write end safe on POSIX; there are no admitted writers
;     left by then. Windows has no SIGPIPE at all.
;   - receiver lease lifetime across a native wait. See
;     wake-receiver-lease-corrected.smt2.
;
; Bounded domain: one writer attempt and seven distinct protocol events,
; numbered 0..6. The admission CAS succeeds iff the attempt linearizes before
; retirement. An admitted writer releases its write-handle lease before
; decrementing the shared writer count. Close waits for that count to drain,
; closes the write end, then closes the read end.
;
; Expected: unsat. The asserted violation is "read closed while the admitted
; writer count still includes this writer."

(set-option :produce-unsat-cores true)

(declare-const writer_admit_step Int)
(declare-const admission_retire_step Int)
(declare-const writer_write_step Int)
(declare-const handle_lease_release_step Int)
(declare-const writer_count_release_step Int)
(declare-const write_close_step Int)
(declare-const read_close_step Int)
(declare-const writer_admitted Bool)
(declare-const late_writer_admitted Bool)
(declare-const writer_active_at_read_close Bool)
(declare-const read_close_violation Bool)

(assert (and (<= 0 writer_admit_step) (<= writer_admit_step 6)))
(assert (and (<= 0 admission_retire_step) (<= admission_retire_step 6)))
(assert (and (<= 0 writer_write_step) (<= writer_write_step 6)))
(assert (and (<= 0 handle_lease_release_step)
             (<= handle_lease_release_step 6)))
(assert (and (<= 0 writer_count_release_step)
             (<= writer_count_release_step 6)))
(assert (and (<= 0 write_close_step) (<= write_close_step 6)))
(assert (and (<= 0 read_close_step) (<= read_close_step 6)))
(assert (distinct writer_admit_step admission_retire_step writer_write_step
                  handle_lease_release_step writer_count_release_step
                  write_close_step read_close_step))

; The admission attempt and retirement CAS share one atomic state. Exactly an
; attempt before retirement is admitted; one after retirement is rejected.
(assert
  (! (= writer_admitted
        (< writer_admit_step admission_retire_step))
     :named one_cas_admission_gate))
(assert
  (! (= late_writer_admitted
        (and writer_admitted
             (< admission_retire_step writer_admit_step)))
     :named late_writer_iff_admitted_after_retirement))

; Release order is load-bearing: after the counted writer disappears it can no
; longer hold the handle lease or issue write(2).
(assert
  (! (=> writer_admitted
          (and (< writer_admit_step writer_write_step)
               (< writer_write_step handle_lease_release_step)
               (< handle_lease_release_step writer_count_release_step)))
     :named handle_lease_released_before_writer_count))

; Retirement rejects future writers; the closer then observes the counted
; writer drain before closing the write end.
(assert
  (! (< admission_retire_step write_close_step)
     :named admission_retires_before_sender_close))
(assert
  (! (=> writer_admitted
          (< writer_count_release_step write_close_step))
     :named admitted_writers_drain_before_write_close))

; Closing the write end first is safe after drain; the read end is last.
(assert
  (! (< write_close_step read_close_step)
     :named write_end_closes_before_read_end))

(assert
  (! (= writer_active_at_read_close
        (and writer_admitted
             (< writer_admit_step read_close_step)
             (< read_close_step handle_lease_release_step)))
     :named active_writer_iff_handle_lease_not_released))
(assert
  (! (= read_close_violation
        (or late_writer_admitted writer_active_at_read_close))
     :named violation_iff_late_admission_or_live_writer))

; Negation of the safety claim.
(assert (! read_close_violation :named property_violated))

(check-sat)
(get-unsat-core)
