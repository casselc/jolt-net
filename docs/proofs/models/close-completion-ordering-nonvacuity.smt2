; Control (USEFUL / NON-VACUITY): the corrected ordering still lets close
; actually complete, having actually delivered a wake.
;
; close-completion-ordering-corrected.smt2 is unsat, and would also be unsat for
; a close that simply never reports completion -- one that refuses, or parks
; forever, satisfies "never completes wrongly" trivially. That is precisely the
; behavior task W4 had to replace: the W3 wake-less adapter REFUSED terminal
; close against an active await, and refusing is not completing.
;
; This model therefore carries the same premises and demands a run in which
; close reports completion AND a terminal wake was genuinely delivered AND the
; lifecycle really reached :closed first.
;
; Expected: sat. `(get-model)` names a run in which close is a real completion
; boundary.

(set-option :produce-models true)
(declare-const cas_step Int)
(declare-const publish_step Int)
(declare-const retire_admission_step Int)
(declare-const closed_phase_step Int)
(declare-const return_step Int)

(declare-const terminal_wake_delivered Bool)
(declare-const reported_complete Bool)
(declare-const completion_violation Bool)

(assert (and (<= 0 cas_step) (<= cas_step 4)))
(assert (and (<= 0 publish_step) (<= publish_step 4)))
(assert (and (<= 0 retire_admission_step) (<= retire_admission_step 4)))
(assert (and (<= 0 closed_phase_step) (<= closed_phase_step 4)))
(assert (and (<= 0 return_step) (<= return_step 4)))
(assert (distinct cas_step publish_step retire_admission_step
                  closed_phase_step return_step))

; Step 1 then step 3: nothing is published before the transition is won, so a
; loser cannot publish a wake for a close it did not perform.
(assert
  (! (< cas_step publish_step)
     :named publish_follows_the_winning_transition))

; Steps 3 then 4. THE ordering under test.
(assert
  (! (< publish_step retire_admission_step)
     :named publish_precedes_admission_retirement))

; Steps 4 then 8: the lifecycle cannot read :closed before retirement began.
(assert
  (! (< retire_admission_step closed_phase_step)
     :named retirement_precedes_the_closed_phase))

; Step 9: close returns only from a :closed lifecycle.
(assert
  (! (< closed_phase_step return_step)
     :named return_follows_the_closed_phase))

; The environmental premise: admission and retirement share one CAS state, so a
; send is delivered exactly when it was attempted before retirement.
(assert
  (! (= terminal_wake_delivered (< publish_step retire_admission_step))
     :named delivery_iff_published_before_retirement))

(assert (! reported_complete :named close_reports_completion))

(assert
  (! (= completion_violation
        (and reported_complete
             (or (not terminal_wake_delivered)
                 (< return_step closed_phase_step))))
     :named violation_iff_completed_without_wake_or_before_closed))

; The usefulness demands.
(assert (! terminal_wake_delivered :named a_wake_was_really_delivered))
(assert (! (< closed_phase_step return_step) :named it_really_reached_closed))
(assert (! (not completion_violation) :named and_completion_was_sound))

(check-sat)
(get-model)
