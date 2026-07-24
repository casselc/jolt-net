; Claim (negated below): every returned non-blocking connect socket has exactly
; one caller owner, every unreturned synchronous failure is closed, and a
; concurrent close cannot retire the descriptor before SO_ERROR completes.
;
; Bounded domain: one connect initiation, one completion, one concurrent close,
; and five ordered events numbered 0..4. This deliberately buggy constructor
; returns EINPROGRESS without transferring ownership, while its close path does
; not drain the completion operation.
;
; Expected: sat. The witness has an in-progress socket returned with owner_count
; 0 and native close before getsockopt.

(set-option :produce-unsat-cores true)

(declare-datatypes () ((Initiation sync_failure immediate_success in_progress)))
(declare-datatypes () ((Completion no_completion completion_success completion_failure)))
(declare-const initiation Initiation)
(declare-const completion Completion)

(declare-const returned Bool)
(declare-const raw_open_after_constructor Bool)
(declare-const owner_count_after_constructor Int)
(declare-const finish_initiates_close Bool)

(declare-const acquire_step Int)
(declare-const close_begin_step Int)
(declare-const native_close_step Int)
(declare-const getsockopt_step Int)
(declare-const release_step Int)
(declare-const getsockopt_issued Bool)

(declare-const returned_without_owner Bool)
(declare-const unreturned_raw_left_open Bool)
(declare-const getsockopt_after_native_close Bool)
(declare-const completion_stole_ownership Bool)
(declare-const violation Bool)

(assert (= initiation in_progress))
(assert (= completion completion_failure))
(assert (= returned (not (= initiation sync_failure))))
(assert (= raw_open_after_constructor returned))

; BUG: only immediate success transfers ownership; EINPROGRESS is returned raw.
(assert (= owner_count_after_constructor
           (ite (= initiation immediate_success) 1 0)))

; BUG: completion failure silently takes close ownership.
(assert (= finish_initiates_close (= completion completion_failure)))

(assert (and (<= 0 acquire_step) (<= acquire_step 4)))
(assert (and (<= 0 close_begin_step) (<= close_begin_step 4)))
(assert (and (<= 0 native_close_step) (<= native_close_step 4)))
(assert (and (<= 0 getsockopt_step) (<= getsockopt_step 4)))
(assert (and (<= 0 release_step) (<= release_step 4)))
(assert (distinct acquire_step close_begin_step native_close_step
                  getsockopt_step release_step))
(assert (= acquire_step 0))
(assert (= close_begin_step 1))
; BUG: native close does not wait for the admitted completion lease.
(assert (= native_close_step 2))
(assert (= getsockopt_step 3))
(assert (= release_step 4))
(assert (= getsockopt_issued (not (= completion no_completion))))

(assert (= returned_without_owner
           (and returned (not (= owner_count_after_constructor 1)))))
(assert (= unreturned_raw_left_open
           (and (not returned) raw_open_after_constructor)))
(assert (= getsockopt_after_native_close
           (and getsockopt_issued (< native_close_step getsockopt_step))))
(assert (= completion_stole_ownership
           (and getsockopt_issued finish_initiates_close)))
(assert (= violation
           (or returned_without_owner
               unreturned_raw_left_open
               getsockopt_after_native_close
               completion_stole_ownership)))

(assert (! violation :named violation_query))

(check-sat)
(get-model)
