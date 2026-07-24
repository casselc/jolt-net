; Claim: closing a listener terminally closes its accept-owned poller in either
; listener-callback order, so no active await survives listener close return,
; no later await is admitted, and the synchronous callback forms no wait cycle
; through listener native close.
;
; Bounded domain: one accept, one listener close, two serial callbacks, and one
; await case. callback_order is 0 (terminal first) or 1 (ordinary registration
; removal first). await_active_at_terminal_callback and late_await_attempt cover
; the two cancellation boundaries. The model abstracts the kernel fact used by
; poller close: closing the independent wake-pipe write end releases an active
; poll without requiring listener native close. It also abstracts the live
; handle rule that releasing a listener lease never waits for notification.
;
; Expected: unsat. The asserted violation is a blocked await after listener
; close return or a synchronous callback/native-close wait cycle.

(set-option :produce-unsat-cores true)

(declare-const callback_order Int)
(declare-const listener_close_wins Bool)
(declare-const terminal_callback_registered Bool)
(declare-const terminal_callback_invoked Bool)
(declare-const ordinary_callback_invoked Bool)
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

(assert (! (and (<= 0 callback_order) (<= callback_order 1))
           :named both_serial_callback_orders_are_in_domain))
(assert (! listener_close_wins
           :named listener_close_owns_the_transition))
(assert (! terminal_callback_registered
           :named accept_installs_terminal_callback))
(assert
  (! (= terminal_callback_invoked
        (and listener_close_wins terminal_callback_registered))
     :named listener_close_invokes_captured_terminal_callback))
(assert (! ordinary_callback_invoked
           :named listener_close_also_invokes_registration_callback))
(assert (! (or await_active_at_terminal_callback late_await_attempt)
           :named one_relevant_await_boundary_is_exercised))
(assert (! (not (and await_active_at_terminal_callback late_await_attempt))
           :named await_is_either_active_or_late_not_both))

; poller/close! is a completion boundary. An active await exits through the
; independent wake pipe before the callback returns. If no await is active,
; terminal lifecycle still retires admission before callback return.
(assert
  (! (= active_await_exits_before_terminal_return
        (and terminal_callback_invoked
             await_active_at_terminal_callback))
     :named active_await_exits_before_poller_close_returns))
(assert
  (! (= poller_open_after_terminal_return
        (not terminal_callback_invoked))
     :named terminal_callback_retires_poller_lifecycle))
(assert
  (! (= late_await_admitted
        (and late_await_attempt poller_open_after_terminal_return))
     :named late_admission_iff_attempt_on_open_poller))
(assert
  (! (= active_await_survives_listener_return
        (and await_active_at_terminal_callback
             (not active_await_exits_before_terminal_return)))
     :named active_survival_iff_terminal_return_did_not_release_await))

; The callback waits for an active await, but that await waits on poller wake
; retirement, not on listener native close. Listener native close is later
; because handle close invokes callbacks before notification and lease drain.
(assert
  (! (= terminal_return_waits_for_await_exit
        await_active_at_terminal_callback)
     :named terminal_callback_waits_only_for_active_await))
(assert
  (! (not await_exit_waits_for_listener_native_close)
     :named poller_wake_close_is_independent_of_listener_native_close))
(assert
  (! listener_native_close_waits_for_terminal_return
     :named listener_native_close_follows_callbacks))
(assert
  (! (= callback_wait_cycle
        (and terminal_return_waits_for_await_exit
             await_exit_waits_for_listener_native_close
             listener_native_close_waits_for_terminal_return))
     :named callback_cycle_iff_all_three_wait_edges_exist))

(assert
  (! (= blocked_after_listener_close
        (or active_await_survives_listener_return late_await_admitted))
     :named blocked_iff_active_wait_survives_or_late_wait_is_admitted))
(assert
  (! (= close_violation
        (or blocked_after_listener_close callback_wait_cycle))
     :named violation_iff_blocked_after_return_or_callback_cycle))

; Negation of the safety claim.
(assert (! close_violation :named property_violated))

(check-sat)
(get-unsat-core)
