; Non-vacuity control for wake-pair-corrected.smt2.
;
; The corrected protocol must permit a genuine race: a writer is admitted,
; close retires admission while that writer remains active, the writer performs
; its nonblocking write and releases in the required order, and close then
; retires both pipe ends successfully.
;
; Expected: sat. In every witness admitted=true, retirement precedes the
; writer's write/release, and write-close precedes read-close.

(set-option :produce-unsat-cores true)

(declare-const writer_admit_step Int)
(declare-const admission_retire_step Int)
(declare-const writer_write_step Int)
(declare-const handle_lease_release_step Int)
(declare-const writer_count_release_step Int)
(declare-const write_close_step Int)
(declare-const read_close_step Int)
(declare-const writer_admitted Bool)
(declare-const writer_active_at_retirement Bool)
(declare-const successful_close Bool)

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

(assert
  (! (= writer_admitted
        (< writer_admit_step admission_retire_step))
     :named one_cas_admission_gate))
(assert
  (! (= writer_active_at_retirement
        (and writer_admitted
             (< writer_admit_step admission_retire_step)
             (< admission_retire_step writer_count_release_step)))
     :named writer_really_crosses_close_retirement))

; Force the interesting race, not merely a serial open/write/close execution.
(assert
  (! (and (< admission_retire_step writer_write_step)
          (< writer_write_step handle_lease_release_step)
          (< handle_lease_release_step writer_count_release_step))
     :named admitted_writer_drains_after_retirement))
(assert
  (! (< writer_count_release_step write_close_step)
     :named writer_count_drains_before_write_close))
(assert
  (! (< write_close_step read_close_step)
     :named write_end_closes_before_read_end))

(assert
  (! (= successful_close
        (and writer_active_at_retirement
             (< writer_count_release_step write_close_step)
             (< write_close_step read_close_step)))
     :named successful_close_iff_race_drains_and_both_ends_retire))

(assert (! successful_close :named successful_close_reachable))

(check-sat)
(get-model)
