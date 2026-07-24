; Non-vacuity control for accept-terminal-close-corrected.smt2.
;
; Force the harder callback order and race: ordinary registration removal runs
; first, an await is active when terminal close begins, the terminal callback
; releases it through the independent wake pipe, and listener close completes
; without a surviving await or callback/native-close wait cycle.
;
; Expected: sat with callback_order=1, active await true, terminal release true,
; poller open after callback false, and successful_close=true.

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
(declare-const successful_close Bool)

(assert (! (= callback_order 1)
           :named registration_removal_callback_runs_first))
(assert listener_close_wins)
(assert terminal_callback_registered)
(assert
  (= terminal_callback_invoked
     (and listener_close_wins terminal_callback_registered)))
(assert ordinary_callback_invoked)
(assert (! await_active_at_terminal_callback
           :named active_await_crosses_terminal_callback))
(assert (not late_await_attempt))

(assert
  (= active_await_exits_before_terminal_return
     (and terminal_callback_invoked await_active_at_terminal_callback)))
(assert
  (= poller_open_after_terminal_return
     (not terminal_callback_invoked)))
(assert
  (= late_await_admitted
     (and late_await_attempt poller_open_after_terminal_return)))
(assert
  (= active_await_survives_listener_return
     (and await_active_at_terminal_callback
          (not active_await_exits_before_terminal_return))))

(assert
  (= terminal_return_waits_for_await_exit
     await_active_at_terminal_callback))
(assert (not await_exit_waits_for_listener_native_close))
(assert listener_native_close_waits_for_terminal_return)
(assert
  (= callback_wait_cycle
     (and terminal_return_waits_for_await_exit
          await_exit_waits_for_listener_native_close
          listener_native_close_waits_for_terminal_return)))
(assert
  (= blocked_after_listener_close
     (or active_await_survives_listener_return late_await_admitted)))
(assert
  (! (= successful_close
        (and active_await_exits_before_terminal_return
             (not poller_open_after_terminal_return)
             (not blocked_after_listener_close)
             (not callback_wait_cycle)))
     :named success_iff_active_await_released_and_no_cycle))

(assert (! successful_close :named successful_close_reachable))

(check-sat)
(get-model)
