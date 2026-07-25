; Control (BUGGY): with BOTH receiver guarantees deleted, the wake receiver's
; native handle can be closed out from under a running WSAPoll.
;
; This is the non-triviality control for wake-receiver-lease-corrected.smt2, and
; it deletes two premises rather than one. That is not sloppiness, it is the
; finding: the two guarantees are independent and either alone is sufficient, so
; no single deletion can expose the violation. This was checked, not assumed --
; the corrected model with `close_retires_the_receiver_only_after_the_await_exits`
; deleted is still unsat, and with
; `owned_handle_defers_native_close_until_the_last_lease` deleted it is still
; unsat. Only removing both, as here, makes the violation reachable.
;
; Read that as a claim about the DESIGN, not about the model: a future change
; that quietly removes one of the two -- for example retiring the receiver
; directly with a raw close instead of through the owned handle, or letting
; finish-close! run from a lifecycle value that still has an await admitted --
; would leave the system correct but with no margin, and the next such change
; would break it silently.
;
; The witness matters on Windows specifically. WSAPoll takes an array, and one
; invalid SOCKET in it can fail the whole call with WSAENOTSOCK rather than
; degrading that entry, so the readiness of every other registered socket in the
; same wait is lost too.
;
; Expected: sat. `(get-model)` names the interleaving.

(set-option :produce-models true)

(declare-const lease_acquire_step Int)
(declare-const park_start_step Int)
(declare-const park_end_step Int)
(declare-const lease_release_step Int)
(declare-const await_exit_step Int)
(declare-const native_receiver_close_step Int)

(declare-const receiver_closed_under_the_wait Bool)

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

; The await's own structure is unchanged.
(assert (and (< lease_acquire_step park_start_step)
             (< park_start_step park_end_step)
             (< park_end_step lease_release_step)
             (< lease_release_step await_exit_step)))

; DELETED: close_retires_the_receiver_only_after_the_await_exits
; DELETED: owned_handle_defers_native_close_until_the_last_lease

(assert (= receiver_closed_under_the_wait
           (< native_receiver_close_step park_end_step)))

; Ask for the violating execution.
(assert receiver_closed_under_the_wait)

(check-sat)
(get-model)
