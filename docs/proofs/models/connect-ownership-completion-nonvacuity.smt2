; Non-vacuity control for the corrected connect ownership/completion model.
;
; Bounded scenario: EINPROGRESS transfers one owned handle; finish-connect!
; observes an asynchronous failure while a concurrent close begins after lease
; admission. getsockopt completes, the lease releases, and only then can native
; close occur. Completion failure does not take caller ownership.
;
; Expected: sat, with event order acquire/close/getsockopt/release/native-close
; = 0/1/2/3/4.

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
(declare-const valid_scenario Bool)

(assert (= returned (not (= initiation sync_failure))))
(assert (= raw_open_after_constructor returned))
(assert (= owner_count_after_constructor (ite returned 1 0)))
(assert (= finish_runs (not (= completion no_completion))))
(assert (= (= completion no_completion)
           (not (= initiation in_progress))))
(assert (= finish_initiates_close false))
(assert (= owner_count_after_completion owner_count_after_constructor))

(assert (and (<= 0 acquire_step) (<= acquire_step 4)))
(assert (and (<= 0 close_begin_step) (<= close_begin_step 4)))
(assert (and (<= 0 getsockopt_step) (<= getsockopt_step 4)))
(assert (and (<= 0 release_step) (<= release_step 4)))
(assert (and (<= 0 native_close_step) (<= native_close_step 4)))
(assert (distinct acquire_step close_begin_step getsockopt_step
                  release_step native_close_step))

(assert (= lease_admitted
           (and finish_runs (< acquire_step close_begin_step))))
(assert (= getsockopt_issued lease_admitted))
(assert (=> getsockopt_issued
            (and (< acquire_step getsockopt_step)
                 (< getsockopt_step release_step))))
(assert (=> lease_admitted (< release_step native_close_step)))

(assert (= initiation in_progress))
(assert (= completion completion_failure))
(assert (= acquire_step 0))
(assert (= close_begin_step 1))
(assert (= getsockopt_step 2))
(assert (= release_step 3))
(assert (= native_close_step 4))

(assert (= valid_scenario
           (and returned
                raw_open_after_constructor
                (= owner_count_after_constructor 1)
                (= owner_count_after_completion 1)
                finish_runs
                getsockopt_issued
                (not finish_initiates_close)
                (< getsockopt_step native_close_step))))
(assert valid_scenario)

(check-sat)
(get-model)
