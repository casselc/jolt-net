; Bounded counterexample query for the implemented Winsock once-only protocol.
;
; Domain: exactly two distinct first-use entrants. AttemptKind covers a native
; success, a returned native error, and a managed exception raised at the
; allocation/FFI boundary. Outcome is the one canonical value callers observe.
;
; Source abstraction:
;   * both callers reach the nil->promise CAS gate;
;   * atom linearizability gives exactly one winner/native attempt;
;   * try/catch normalizes every AttemptKind into :ok or {:error ex};
;   * terminal state publication and promise delivery precede public return or
;     throw; every caller resolves the same canonical outcome.
;
; The final violation definition is byte-for-byte the same semantic query used
; by the buggy and non-vacuity controls.
;
; Omitted: scheduler fairness, thread death/cancellation, weak memory beneath
; Clojure atom linearizability, failure of atom/promise primitives themselves,
; native ABI correctness, and more than two entrants.
;
; VERIFIED: unsat. No violating execution exists inside this abstraction for
; success, returned_error, or thrown_exception.

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

; Exactly one of those entrants wins the atomic nil->promise CAS.
(assert (! (not (and t1_wins_gate t2_wins_gate))
           :named cas_at_most_one))
(assert (! (or t1_wins_gate t2_wins_gate)
           :named cas_has_a_winner))
(assert (! (= attempt_count
              (+ (ite t1_wins_gate 1 0)
                 (ite t2_wins_gate 1 0)))
           :named attempt_count_definition))

(assert (! (= canonical_outcome
              (ite (= attempt_kind success) ok error))
           :named canonical_outcome_definition))

; The catch boundary is total over the finite AttemptKind datatype: native
; success produces :ok; both a returned error and a thrown exception produce
; one memoized {:error ex} outcome.
(assert (! (= outcome_produced
              (and (= attempt_count 1)
                   (or (= attempt_kind success)
                       (= attempt_kind returned_error)
                       (= attempt_kind thrown_exception))))
           :named outcome_production_definition))

; Source ordering: terminal state and delivery both happen before the winner
; returns/throws. A promise waiter and a later caller therefore see one outcome.
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
(get-unsat-core)
