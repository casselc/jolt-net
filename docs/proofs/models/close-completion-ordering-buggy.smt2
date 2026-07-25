; Control (BUGGY): swap steps 3 and 4 -- retire wake-send admission BEFORE
; publishing the terminal wake -- and close reports completion having delivered
; no wake at all.
;
; This is the non-triviality control for
; close-completion-ordering-corrected.smt2. Exactly one premise is deleted and
; replaced by its inverse: `publish_precedes_admission_retirement` becomes
; `retire_admission_step < publish_step`. Everything else is identical.
;
; This is not a hypothetical mis-ordering. It is what the code did before task
; W4 separated the two: close called the ordinary coalescing wake and then
; retired writes, and the ordinary wake path returns quietly when admission has
; already been retired. On POSIX that was survivable because retiring the write
; end still leaves the retained read end reporting POLLHUP, so cancellation had
; a second source. On a :byte-only datagram transport there is no second source,
; and the resulting close is a stop request that never stops anything.
;
; Expected: sat. `(get-model)` names the ordering.

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
; SWAPPED. Production asserts publish_step < retire_admission_step here.
(assert
  (! (< retire_admission_step publish_step)
     :named admission_retired_before_the_terminal_wake))

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

; Ask for the violating execution rather than negating a safety claim.
(assert completion_violation)

(check-sat)
(get-model)
