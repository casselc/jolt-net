; Non-vacuity control for windows-wakeless-close-race-corrected.smt2.
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
; A wake-less adapter with no active await must still be able to transition from
; :open to :closing. The correction refuses only the lifecycle state it cannot
; terminate; it is not a deny-all close implementation.
;
; Expected: sat, with close_transitioned_to_closing=true and
; close_observed_awaiting=false.

(set-option :produce-unsat-cores true)

(declare-const wake_transport_present Bool)
(declare-const initial_phase_open Bool)
(declare-const initial_awaiting Bool)
(declare-const await_scheduled_before_close_cas Bool)
(declare-const await_admitted_before_close_cas Bool)
(declare-const close_observed_awaiting Bool)
(declare-const atomic_close_guard_allowed Bool)
(declare-const close_transitioned_to_closing Bool)
(declare-const valid_close_reachable Bool)

(assert (! (not wake_transport_present)
           :named windows_w3_has_no_wake_transport))
(assert (! initial_phase_open
           :named lifecycle_starts_open))
(assert (! (not initial_awaiting)
           :named lifecycle_initially_has_no_await))
(assert (! (not await_scheduled_before_close_cas)
           :named no_await_wins_before_close))
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
(assert
  (! (= atomic_close_guard_allowed
        (and initial_phase_open
             (not close_observed_awaiting)))
     :named atomic_close_guard_definition))
(assert
  (! (= close_transitioned_to_closing atomic_close_guard_allowed)
     :named corrected_close_transition_definition))
(assert
  (! (= valid_close_reachable
        (and close_transitioned_to_closing
             (not close_observed_awaiting)))
     :named valid_close_definition))

(assert (! valid_close_reachable :named valid_close_is_reachable))

(check-sat)
(get-model)
