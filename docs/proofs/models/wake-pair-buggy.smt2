; Claim (negated below): the self-pipe read end never closes while an admitted
; wake writer can still execute write(2).
;
; Bounded domain: five distinct steps numbered 0..4. The buggy pair has no
; owner-independent admission/drain gate, so close can retire the read end after
; a writer was admitted but before that writer writes and releases. A write in
; this interval can raise process-fatal SIGPIPE.
;
; Expected: sat, with admit=0, close-begin=1, read-close=2, write=3, release=4.

(set-option :produce-unsat-cores true)

(declare-const writer_admit_step Int)
(declare-const close_begin_step Int)
(declare-const read_close_step Int)
(declare-const writer_write_step Int)
(declare-const writer_release_step Int)
(declare-const writer_admitted Bool)
(declare-const writer_active_at_read_close Bool)
(declare-const write_after_read_close Bool)
(declare-const read_close_violation Bool)

(assert (and (<= 0 writer_admit_step) (<= writer_admit_step 4)))
(assert (and (<= 0 close_begin_step) (<= close_begin_step 4)))
(assert (and (<= 0 read_close_step) (<= read_close_step 4)))
(assert (and (<= 0 writer_write_step) (<= writer_write_step 4)))
(assert (and (<= 0 writer_release_step) (<= writer_release_step 4)))
(assert (distinct writer_admit_step close_begin_step read_close_step
                  writer_write_step writer_release_step))

(assert (! writer_admitted :named writer_was_admitted))
(assert
  (! (= writer_active_at_read_close
        (and writer_admitted
             (< writer_admit_step read_close_step)
             (< read_close_step writer_release_step)))
     :named active_writer_iff_release_has_not_happened))
(assert
  (! (= write_after_read_close
        (and writer_admitted
             (< read_close_step writer_write_step)
             (< writer_write_step writer_release_step)))
     :named write_iff_it_hits_retired_reader))
(assert
  (! (= read_close_violation
        (and (< writer_admit_step close_begin_step)
             (< close_begin_step read_close_step)
             writer_active_at_read_close
             write_after_read_close))
     :named violation_iff_reader_retires_before_admitted_write))

(assert (! read_close_violation :named property_violated))

(check-sat)
(get-model)
