; Claim: the wake RECEIVER's native handle is never closed while a native
; readiness wait is still using it.
;
; This matters more on Windows than the POSIX phrasing suggests. WSAPoll takes
; an ARRAY: handing it one invalid SOCKET does not degrade that entry, it can
; fail the entire call with WSAENOTSOCK, losing the readiness of every other
; registered socket in the same wait. The receiver is in slot 0 of every wait
; this poller performs, so retiring it early is not a small error.
;
; Source anchors:
;   jolt.net.poller/await-ready       acquires the receiver lease before the
;                                     wait and releases it in the finally, then
;                                     calls exit-await!
;   jolt.net.poller/close!            step 7: spins until :awaiting? is false
;   jolt.net.poller/finish-close!     step 8: retires the receiver, and only
;                                     from a lifecycle value with awaiting? false
;   jolt.net.wake/retire-receiver!    the retirement itself
;   jolt.net.handle/close!            defers the native close to the last lease
;                                     releaser when leases are outstanding
;
; TWO INDEPENDENT guarantees are asserted, and that redundancy is deliberate:
;
;   P_order  close only retires the receiver from a lifecycle value in which no
;            await is admitted, so retirement follows the await's exit.
;   P_lease  the owned handle defers its native close until the last short
;            operation lease is released, so retirement follows the await's
;            lease release even if the ordering above were violated.
;
; EITHER ONE ALONE IS SUFFICIENT: this model is still unsat with P_order deleted
; and still unsat with P_lease deleted. Only deleting both makes the violation
; reachable, which is what wake-receiver-lease-buggy.smt2 does and says.
;
; Bounded domain: one await, one close, six distinct events numbered 0..5.
; Abstracted away: the registration set, the readiness decoder, the mutation
; queue, and writer admission (modelled in wake-pair-*).
;
; Limits: a bounded interleaving argument over one wait and one close. It says
; nothing about what WSAPoll does with an invalid handle -- that is stated above
; as motivation, from Winsock documentation, not derived here.
;
; Expected: unsat. The asserted violation is "the receiver's native handle was
; closed before the wait using it had finished."

(set-option :produce-unsat-cores true)

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

; The await's own structure: it leases the receiver before entering the wait,
; and releases that lease in its finally, before clearing :awaiting?.
(assert
  (! (and (< lease_acquire_step park_start_step)
          (< park_start_step park_end_step)
          (< park_end_step lease_release_step)
          (< lease_release_step await_exit_step))
     :named await_leases_across_the_wait_and_releases_before_exit))

; P_order.
(assert
  (! (< await_exit_step native_receiver_close_step)
     :named close_retires_the_receiver_only_after_the_await_exits))

; P_lease.
(assert
  (! (< lease_release_step native_receiver_close_step)
     :named owned_handle_defers_native_close_until_the_last_lease))

(assert
  (! (= receiver_closed_under_the_wait
        (< native_receiver_close_step park_end_step))
     :named violation_iff_closed_before_the_wait_finished))

; Negation of the safety claim.
(assert (! receiver_closed_under_the_wait :named property_violated))

(check-sat)
(get-unsat-core)
