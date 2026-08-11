; Control (USEFUL / NON-VACUITY): the receiver really is retired, and the wait
; really does span the park.
;
; wake-receiver-lease-corrected.smt2 is unsat, and would also be unsat for a
; design that simply never closed the receiver at all -- a leak satisfies "never
; closed too early" perfectly. It would likewise be unsat for a "wait" that
; never actually used the handle. Both would be useless, so this model carries
; the same premises and demands a run in which:
;
;   1. the receiver's native handle IS eventually closed (no leak), AND
;   2. the wait genuinely spanned an interval while holding the lease, AND
;   3. the close nevertheless did not land under the wait.
;
; Point 1 is the one the corrected model cannot speak to on its own, and it is
; the one the native W4 gate independently corroborates: the handle-space leak
; probe over 60 poller open/close cycles moves by 0 against a leak signal of
; roughly 960.
;
; Expected: sat. `(get-model)` names a run in which a real wait completes and
; the receiver is then really retired.

(set-option :produce-models true)

(declare-const lease_acquire_step Int)
(declare-const park_start_step Int)
(declare-const park_end_step Int)
(declare-const lease_release_step Int)
(declare-const await_exit_step Int)
(declare-const native_receiver_close_step Int)

(declare-const receiver_closed_under_the_wait Bool)
(declare-const receiver_eventually_closed Bool)
(declare-const wait_spanned_the_lease Bool)

(assert (and (<= 0 lease_acquire_step) (<= lease_acquire_step 5)))
(assert (and (<= 0 park_start_step) (<= park_start_step 5)))
(assert (and (<= 0 park_end_step) (<= park_end_step 5)))
(assert (and (<= 0 lease_release_step) (<= lease_release_step 5)))
(assert (and (<= 0 await_exit_step) (<= await_exit_step 5)))
(assert (and (<= 0 native_receiver_close_step)
             (<= native_receiver_close_step 5)))
(assert (distinct lease_acquire_step park_start_step park_end_step
                  lease_release_step await_exit_step
                  native_receiver_close_step))

; Every premise of the corrected model, unchanged.
(assert (and (< lease_acquire_step park_start_step)
             (< park_start_step park_end_step)
             (< park_end_step lease_release_step)
             (< lease_release_step await_exit_step)))
(assert (< await_exit_step native_receiver_close_step))
(assert (< lease_release_step native_receiver_close_step))
(assert (= receiver_closed_under_the_wait
           (< native_receiver_close_step park_end_step)))

; The usefulness demands.
(assert
  (! (= receiver_eventually_closed
        (<= native_receiver_close_step 5))
     :named retirement_actually_happens))
(assert
  (! (= wait_spanned_the_lease
        (and (< lease_acquire_step park_start_step)
             (< park_start_step park_end_step)
             (< park_end_step lease_release_step)))
     :named the_wait_really_held_the_lease_across_the_park))

(assert (! receiver_eventually_closed :named no_leak))
(assert (! wait_spanned_the_lease :named a_real_wait_occurred))
(assert (! (not receiver_closed_under_the_wait) :named and_it_was_still_safe))

(check-sat)
(get-model)
