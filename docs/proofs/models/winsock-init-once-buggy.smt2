; Bounded known-SAT control for Winsock initialization.
;
; Domain: exactly two distinct first-use entrants. AttemptKind is one native
; success, a native function that returns an error code, or a managed exception
; raised at the allocation/FFI boundary. Outcome is the one canonical value
; callers may observe.
;
; This deliberately combines the two defects this family must detect:
;   1. a check-then-reset gate lets both entrants perform the native attempt;
;   2. a thrown attempt is not normalized, so no outcome is terminalized or
;      delivered and callers cannot complete with one memoized result.
;
; The corrected and non-vacuity files use the SAME declarations and violation
; definition. Only their implementation constraints differ.
;
; Omitted: scheduler fairness, thread death/cancellation, weak memory beneath
; Clojure atom linearizability, failure of atom/promise primitives themselves,
; native ABI correctness, and more than two entrants.
;
; VERIFIED: sat. Witness has two gate winners, attempt_count=2,
; attempt_kind=thrown_exception, no produced/delivered/terminal outcome, and
; violation=true.

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

; Genuine contention is DERIVED from two distinct entrants reaching the gate.
(assert (! (= t1_attempts_gate true) :named t1_is_an_entrant))
(assert (! (= t2_attempts_gate true) :named t2_is_an_entrant))

; Bug injection: check-then-reset admits both callers, and the winning native
; attempts raise instead of returning an outcome.
(assert (! (= t1_wins_gate t1_attempts_gate) :named buggy_t1_check_then_act))
(assert (! (= t2_wins_gate t2_attempts_gate) :named buggy_t2_check_then_act))
(assert (! (= attempt_kind thrown_exception) :named buggy_attempt_throws))

(assert (! (= attempt_count
              (+ (ite t1_wins_gate 1 0)
                 (ite t2_wins_gate 1 0)))
           :named attempt_count_definition))
(assert (! (= canonical_outcome
              (ite (= attempt_kind success) ok error))
           :named canonical_outcome_definition))

; Bug injection: the implementation only creates an outcome when exactly one
; attempt returns normally. A thrown attempt therefore leaves the shared state
; pending and no promise is delivered.
(assert (! (= outcome_produced
              (and (= attempt_count 1)
                   (not (= attempt_kind thrown_exception))))
           :named buggy_outcome_production))
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
(assert (! violation :named violation_query))

(check-sat)
(get-model)
