; Claim: the Windows W3 wake-less adapter never enters :closing while an await
; is active.
;
; STATUS AFTER TASK W4. This family remains valid and is NOT evidence for the
; W4 transport. It describes `jolt.net.poller/open-readiness-adapter`, the
; INTERNAL Windows poller constructed with no waker, which still exists and
; still refuses exactly as modelled here. The public Windows poller obtained
; from `jolt.net/open-poller` always has a transport and does NOT refuse; its
; close is a completion boundary, modelled in
; close-completion-ordering-corrected.smt2 and
; windows-terminal-wake-corrected.smt2.
;
; Do not relabel this as proof of the W4 transport. It is the historical, and
; still-reproducible, evidence for a poller that has none.
;
; Source anchor: jolt.net.poller/close!. The corrected implementation examines
; :awaiting? on the exact lifecycle value supplied to compare-and-set!. If an
; await won first, close refuses. If close won first, await's CAS against :open
; fails. This model isolates the first direction; the atomic compare-and-set is
; the implementation premise connecting the observation to the transition.
;
; Bounded domain: one wake-less adapter, one initially open lifecycle, one close
; attempt, and one possible await admission before close's CAS. The await remains
; parked for this bounded execution. Phase changes other than the two shown are
; omitted.
;
; Expected: unsat. Transitioning requires no observed await, while the queried
; violation requires the same observed lifecycle value to contain one.

(set-option :produce-unsat-cores true)

(declare-const wake_transport_present Bool)
(declare-const initial_phase_open Bool)
(declare-const initial_awaiting Bool)
(declare-const await_scheduled_before_close_cas Bool)
(declare-const await_admitted_before_close_cas Bool)
(declare-const close_observed_awaiting Bool)
(declare-const atomic_close_guard_allowed Bool)
(declare-const close_transitioned_to_closing Bool)
(declare-const await_remains_parked Bool)
(declare-const violation Bool)

(assert (! (not wake_transport_present)
           :named windows_w3_has_no_wake_transport))
(assert (! initial_phase_open
           :named lifecycle_starts_open))
(assert (! (not initial_awaiting)
           :named lifecycle_initially_has_no_await))
(assert
  (! (= await_admitted_before_close_cas
        (and initial_phase_open
             (not initial_awaiting)
             await_scheduled_before_close_cas))
     :named await_admission_definition))
(assert
  (! (= close_observed_awaiting
        (or initial_awaiting await_admitted_before_close_cas))
     :named close_cas_observation_definition))

; Corrected: the refusal check and transition use the same lifecycle value.
(assert
  (! (= atomic_close_guard_allowed
        (and initial_phase_open
             (not close_observed_awaiting)))
     :named atomic_close_guard_definition))
(assert
  (! (= close_transitioned_to_closing atomic_close_guard_allowed)
     :named corrected_close_transition_definition))

(assert (! await_remains_parked
           :named native_wait_has_not_returned))
(assert
  (! (= violation
        (and (not wake_transport_present)
             close_transitioned_to_closing
             close_observed_awaiting
             await_remains_parked))
     :named violation_definition))

(assert (! violation :named property_violated))

(check-sat)
(get-unsat-core)
