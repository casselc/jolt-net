; Deliberately BUGGY control for windows-wakeless-close-race-corrected.smt2.
;
; Source shape before the review correction: jolt.net.poller/close! read
; :awaiting? outside its lifecycle CAS loop. An await could therefore publish
; :awaiting? true after that precheck, after which close would CAS the newer
; lifecycle value to :closing with :awaiting? still true. The Windows W3
; adapter has no wake transport, so close could not interrupt that native wait.
;
; Bounded domain: one wake-less adapter, one initially open lifecycle, one close
; attempt, and one await admission scheduled between close's stale precheck and
; its CAS. The await remains parked for this bounded execution. Phase changes
; other than the two shown are omitted.
;
; Expected: sat. The stale precheck admits :closing with an active parked await.

(set-option :produce-unsat-cores true)

(declare-const wake_transport_present Bool)
(declare-const initial_phase_open Bool)
(declare-const initial_awaiting Bool)
(declare-const await_scheduled_between_check_and_cas Bool)
(declare-const await_admitted_before_close_cas Bool)
(declare-const close_observed_awaiting Bool)
(declare-const stale_close_precheck_allowed Bool)
(declare-const close_transitioned_to_closing Bool)
(declare-const await_remains_parked Bool)
(declare-const violation Bool)

(assert (! (not wake_transport_present)
           :named windows_w3_has_no_wake_transport))
(assert (! initial_phase_open
           :named lifecycle_starts_open))
(assert (! (not initial_awaiting)
           :named stale_close_precheck_observes_no_await))
(assert (! await_scheduled_between_check_and_cas
           :named await_wins_the_interleaving))
(assert
  (! (= await_admitted_before_close_cas
        (and initial_phase_open
             (not initial_awaiting)
             await_scheduled_between_check_and_cas))
     :named await_admission_definition))
(assert
  (! (= close_observed_awaiting
        (or initial_awaiting await_admitted_before_close_cas))
     :named close_cas_observation_definition))

; BUG: permission came from the earlier observation, not the value close CASes.
(assert
  (! (= stale_close_precheck_allowed
        (and initial_phase_open
             (not initial_awaiting)))
     :named buggy_stale_precheck_definition))
(assert
  (! (= close_transitioned_to_closing stale_close_precheck_allowed)
     :named buggy_close_transition_definition))

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
(get-model)
