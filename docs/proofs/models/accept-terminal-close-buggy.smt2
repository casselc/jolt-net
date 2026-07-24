; Claim (negated below): listener close cannot return while its blocking accept
; remains parked in the accept-owned poller.
;
; Bounded domain: one accept, one listener close, two close callbacks, and one
; await attempt. Callback order is 0 (terminal first) or 1 (ordinary
; registration removal first). This buggy control has no terminal callback:
; only a best-effort mutation wake is published before await entry, consumed as
; a pre-existing epoch, and therefore unavailable after the await parks.
;
; Expected: sat. The witness has a registered listener, lost pre-entry wake,
; open poller after listener close, and blocked_after_listener_close=true.

(set-option :produce-unsat-cores true)

(declare-const callback_order Int)
(declare-const listener_close_wins Bool)
(declare-const terminal_callback_registered Bool)
(declare-const terminal_callback_invoked Bool)
(declare-const ordinary_callback_invoked Bool)
(declare-const best_effort_wake_before_await Bool)
(declare-const wake_visible_after_await_entry Bool)
(declare-const await_active_at_terminal_callback Bool)
(declare-const late_await_attempt Bool)
(declare-const active_await_exits_before_terminal_return Bool)
(declare-const poller_open_after_terminal_return Bool)
(declare-const late_await_admitted Bool)
(declare-const active_await_survives_listener_return Bool)
(declare-const terminal_return_waits_for_await_exit Bool)
(declare-const await_exit_waits_for_listener_native_close Bool)
(declare-const listener_native_close_waits_for_terminal_return Bool)
(declare-const callback_wait_cycle Bool)
(declare-const blocked_after_listener_close Bool)
(declare-const close_violation Bool)

(assert (and (<= 0 callback_order) (<= callback_order 1)))
(assert (! listener_close_wins :named listener_close_owns_the_transition))
(assert (! (not terminal_callback_registered)
           :named buggy_has_no_terminal_callback))
(assert
  (! (= terminal_callback_invoked
        (and listener_close_wins terminal_callback_registered))
     :named terminal_invocation_iff_listener_captured_callback))
(assert (! ordinary_callback_invoked
           :named ordinary_registration_callback_runs))
(assert (! best_effort_wake_before_await
           :named wake_precedes_await_entry))
(assert
  (! (= wake_visible_after_await_entry
        (and ordinary_callback_invoked
             (not best_effort_wake_before_await)))
     :named pre_entry_wake_is_consumed))
(assert (! await_active_at_terminal_callback
           :named await_parks_after_missing_terminal_callback))
(assert (! (not late_await_attempt)
           :named this_control_uses_the_active_wait_case))

(assert
  (! (= active_await_exits_before_terminal_return
        (or terminal_callback_invoked
            wake_visible_after_await_entry))
     :named active_exit_iff_terminal_callback_supplies_visible_wake))
(assert
  (! (= poller_open_after_terminal_return
        (not terminal_callback_invoked))
     :named poller_stays_open_without_terminal_callback))
(assert
  (! (= late_await_admitted
        (and late_await_attempt poller_open_after_terminal_return))
     :named late_admission_iff_attempt_on_open_poller))
(assert
  (! (= active_await_survives_listener_return
        (and await_active_at_terminal_callback
             (not active_await_exits_before_terminal_return)))
     :named active_survival_iff_terminal_return_did_not_release_await))

; There is no synchronous terminal callback in this buggy control, so it
; demonstrates a surviving blocked await rather than a callback wait cycle.
(assert (= terminal_return_waits_for_await_exit false))
(assert (= await_exit_waits_for_listener_native_close false))
(assert (= listener_native_close_waits_for_terminal_return false))
(assert
  (= callback_wait_cycle
     (and terminal_return_waits_for_await_exit
          await_exit_waits_for_listener_native_close
          listener_native_close_waits_for_terminal_return)))

(assert
  (! (= blocked_after_listener_close
        (or active_await_survives_listener_return late_await_admitted))
     :named blocked_iff_active_wait_survives_or_late_wait_is_admitted))
(assert
  (! (= close_violation
        (or blocked_after_listener_close callback_wait_cycle))
     :named violation_iff_blocked_after_return_or_callback_cycle))

(assert (! close_violation :named property_violated))

(check-sat)
(get-model)
