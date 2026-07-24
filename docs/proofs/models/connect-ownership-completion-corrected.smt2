; Claim: every returned non-blocking connect socket has exactly one caller owner,
; every unreturned synchronous failure is closed, finish-connect! never takes
; ownership, and an admitted SO_ERROR read completes before native close.
;
; Bounded domain: one initiation outcome, at most one completion outcome, one
; concurrent close, and five distinct event positions numbered 0..4. Atom CAS
; and short-lease linearization are treated as atomic source facts.
;
; Expected: unsat. This asserts the negated safety claim.

(set-option :produce-unsat-cores true)

(declare-datatypes () ((Initiation sync_failure immediate_success in_progress)))
(declare-datatypes () ((Completion no_completion completion_success completion_failure)))
(declare-const initiation Initiation)
(declare-const completion Completion)

(declare-const returned Bool)
(declare-const raw_open_after_constructor Bool)
(declare-const owner_count_after_constructor Int)
(declare-const owner_count_after_completion Int)
(declare-const finish_runs Bool)
(declare-const finish_initiates_close Bool)

(declare-const acquire_step Int)
(declare-const close_begin_step Int)
(declare-const getsockopt_step Int)
(declare-const release_step Int)
(declare-const native_close_step Int)
(declare-const lease_admitted Bool)
(declare-const getsockopt_issued Bool)

(declare-const returned_without_owner Bool)
(declare-const unreturned_raw_left_open Bool)
(declare-const getsockopt_after_native_close Bool)
(declare-const completion_changed_owner Bool)
(declare-const violation Bool)

(assert
  (! (= returned (not (= initiation sync_failure)))
     :named return_iff_immediate_or_in_progress))
(assert
  (! (= raw_open_after_constructor returned)
     :named rollback_closes_every_unreturned_failure))
(assert
  (! (= owner_count_after_constructor (ite returned 1 0))
     :named ownership_transfers_for_every_returned_status))

(assert
  (! (= finish_runs (not (= completion no_completion)))
     :named finish_runs_iff_completion_selected))
(assert
  (! (= (= completion no_completion)
        (not (= initiation in_progress)))
     :named completion_only_for_in_progress_in_this_bound))
(assert
  (! (= finish_initiates_close false)
     :named finish_never_closes_or_transfers))
(assert
  (! (= owner_count_after_completion owner_count_after_constructor)
     :named finish_preserves_owner_count))

(assert (and (<= 0 acquire_step) (<= acquire_step 4)))
(assert (and (<= 0 close_begin_step) (<= close_begin_step 4)))
(assert (and (<= 0 getsockopt_step) (<= getsockopt_step 4)))
(assert (and (<= 0 release_step) (<= release_step 4)))
(assert (and (<= 0 native_close_step) (<= native_close_step 4)))
(assert (distinct acquire_step close_begin_step getsockopt_step
                  release_step native_close_step))

(assert
  (! (= lease_admitted
        (and finish_runs (< acquire_step close_begin_step)))
     :named lease_admission_rejects_after_close))
(assert
  (! (= getsockopt_issued lease_admitted)
     :named getsockopt_requires_admitted_lease))
(assert
  (! (=> getsockopt_issued
          (and (< acquire_step getsockopt_step)
               (< getsockopt_step release_step)))
     :named getsockopt_inside_short_lease))
(assert
  (! (=> lease_admitted (< release_step native_close_step))
     :named native_close_waits_for_completion_release))

(assert
  (! (= returned_without_owner
        (and returned (not (= owner_count_after_constructor 1))))
     :named returned_owner_violation_definition))
(assert
  (! (= unreturned_raw_left_open
        (and (not returned) raw_open_after_constructor))
     :named rollback_violation_definition))
(assert
  (! (= getsockopt_after_native_close
        (and getsockopt_issued (< native_close_step getsockopt_step)))
     :named post_close_completion_violation_definition))
(assert
  (! (= completion_changed_owner
        (and finish_runs
             (or finish_initiates_close
                 (not (= owner_count_after_completion
                         owner_count_after_constructor)))))
     :named completion_owner_violation_definition))
(assert
  (! (= violation
        (or returned_without_owner
            unreturned_raw_left_open
            getsockopt_after_native_close
            completion_changed_owner))
     :named violation_definition))

(assert (! violation :named violation_query))

(check-sat)
(get-unsat-core)
