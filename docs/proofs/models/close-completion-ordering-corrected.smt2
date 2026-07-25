; Claim: close publishes a terminal wake while sends are still admitted, and
; reports completion only from a :closed lifecycle.
;
; This is the ORDERING claim, kept separate from the delivery claim in
; windows-terminal-wake-*.smt2. That one asks "can a parked await be stranded";
; this one asks "can close retire the ability to wake it before it has woken
; it, and can close call itself complete before it is".
;
; Source anchors, and the step numbering they carry in the docstring:
;   jolt.net.poller/close!  step 1 win the lifecycle transition
;                           step 2 clear registrations through the queue
;                           step 3 publish the terminal wake
;                           step 4 retire wake-send admission
;                           step 5 wait for admitted sends to drain
;                           step 6 retire the sender
;                           step 7 wait for the active wait to exit
;                           step 8 retire the receiver
;                           step 9 return, only once :closed
;   jolt.net.poller/publish-terminal-wake!  step 3
;   jolt.net.poller/retire-wake-writes!     steps 4-6
;   jolt.net.poller/finish-close!           steps 8-9
;
; Environmental premise: a wake send is admitted through the SAME CAS state that
; retirement flips, so a send attempted after retirement is refused and delivers
; nothing. On a :byte-only transport (jolt.net.wake/terminal-wake) that refusal
; is total, because sender retirement itself is invisible to the peer. This is
; why the publish/retire order is load-bearing here and merely belt-and-braces
; on POSIX; see posix-pipe-hangup-independence-control.smt2.
;
; Bounded domain: one close, five distinct events numbered 0..4. Writer drain
; counting is abstracted (see wake-pair-*), as is receiver lease lifetime (see
; wake-receiver-lease-*).
;
; Limits: bounded, single-close. It does not model concurrent closers; the
; single-winner property is idempotent-close-*.smt2.
;
; Expected: unsat. The asserted violation is "close reported completion while
; either no terminal wake could have been delivered, or the lifecycle was not
; yet :closed."

(set-option :produce-unsat-cores true)

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

; Negation of the safety claim.
(assert (! completion_violation :named property_violated))

(check-sat)
(get-unsat-core)
