; Non-vacuity control for winsock-init-once-corrected.smt2.
;
; It uses the same two-entrant schema and exact violation definition, selects
; the hardest modeled attempt kind (a managed exception), requires one concrete
; CAS winner, and asks for a useful non-violating execution. Contention is not a
; free Boolean: both distinct t1/t2 gate-attempt facts are asserted directly.
;
; Omitted behavior matches the corrected model.
;
; VERIFIED: sat. Witness has t1 win, t2 lose, attempt_count=1,
; attempt_kind=thrown_exception, canonical_outcome=error, terminal state and
; delivery true, both callers observing error, and violation=false.

(set-option :produce-unsat-cores true)

(declare-datatypes () ((AttemptKind success returned_error thrown_exception)))
(declare-datatypes () ((Outcome none ok error)))

(declare-const attempt_kind AttemptKind)
(declare-const canonical_outcome Outcome)
(declare-const t1_attempts_gate Bool)
(declare-const t2_attempts_gate Bool)
(declare-const t1_wins_gate Bool)
(declare-const t2_wins_gate Bool)
(declare-const attempt_count Int)
(declare-const outcome_produced Bool)
(declare-const promise_delivered Bool)
(declare-const state_terminal Bool)
(declare-const public_completed Bool)
(declare-const t1_observed Outcome)
(declare-const t2_observed Outcome)
(declare-const violation Bool)

(assert (! (= t1_attempts_gate true) :named t1_is_an_entrant))
(assert (! (= t2_attempts_gate true) :named t2_is_an_entrant))

; Select one concrete contended schedule and the exceptional boundary case.
(assert (! (= t1_wins_gate true) :named t1_is_the_winner))
(assert (! (= t2_wins_gate false) :named t2_is_the_waiter))
(assert (! (= attempt_kind thrown_exception)
           :named exceptional_attempt_is_reachable))

(assert (! (= attempt_count
              (+ (ite t1_wins_gate 1 0)
                 (ite t2_wins_gate 1 0)))
           :named attempt_count_definition))
(assert (! (= canonical_outcome
              (ite (= attempt_kind success) ok error))
           :named canonical_outcome_definition))
(assert (! (= outcome_produced
              (and (= attempt_count 1)
                   (or (= attempt_kind success)
                       (= attempt_kind returned_error)
                       (= attempt_kind thrown_exception))))
           :named outcome_production_definition))
(assert (! (= promise_delivered outcome_produced)
           :named delivery_definition))
(assert (! (= state_terminal outcome_produced)
           :named terminal_definition))
(assert (! (= public_completed
              (and outcome_produced promise_delivered state_terminal))
           :named completion_definition))
(assert (! (= t1_observed
              (ite public_completed canonical_outcome none))
           :named t1_observation_definition))
(assert (! (= t2_observed
              (ite public_completed canonical_outcome none))
           :named t2_observation_definition))

; Exact shared query used by all three files.
(assert (! (= violation
              (or (not (= attempt_count 1))
                  (not outcome_produced)
                  (not promise_delivered)
                  (not state_terminal)
                  (not public_completed)
                  (= t1_observed none)
                  (= t2_observed none)
                  (not (= t1_observed canonical_outcome))
                  (not (= t2_observed canonical_outcome))
                  (not (= t1_observed t2_observed))))
           :named violation_definition))

; Reachability/non-vacuity query: the exceptional path completes safely.
(assert (! (not violation) :named safe_execution_reachable))
(assert (! public_completed :named useful_completion_reachable))
(assert (! (= canonical_outcome error) :named memoized_error_reachable))

(check-sat)
(get-model)
