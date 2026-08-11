; Control (BUGGY): delete exactly one thing -- the pre-entry terminal check in
; jolt.net.poller/await-ready -- and a byte-only transport strands the await.
;
; This is the non-triviality control for windows-terminal-wake-corrected.smt2.
; Everything else is identical: same events, same premises, same violation
; definition. The ONLY difference is that `await_parks` is no longer conditioned
; on the lifecycle read, which is what the code looked like before the check was
; added.
;
; The witness this produces is the real race, and it is not exotic: close wins
; the lifecycle transition and publishes its terminal byte; the await's own
; pre-snapshot drain -- which exists to consume a byte left by an earlier
; acknowledged mutation -- legitimately consumes that terminal byte instead;
; the await then parks. On POSIX the retired write end's POLLHUP would still
; release it, which is exactly why this defect is invisible there. On a
; byte-only datagram transport nothing else exists, so the await parks until its
; own timeout while close spins waiting for it.
;
; The drain's epoch-restore does not save it either. That restore runs through
; the ordinary admission path, and by then close has retired admission, so the
; restore is refused. That is modelled here as the simple absence of any second
; wake term.
;
; Expected: sat. `(get-model)` names the interleaving.

(set-option :produce-models true)

(declare-const close_cas_step Int)
(declare-const publish_step Int)
(declare-const await_drain_step Int)
(declare-const await_phase_read_step Int)

(declare-const await_parks Bool)
(declare-const byte_consumed_before_entry Bool)
(declare-const wake_reaches_await Bool)
(declare-const close_awaiting_exit Bool)
(declare-const await_stranded Bool)

(assert (and (<= 0 close_cas_step) (<= close_cas_step 3)))
(assert (and (<= 0 publish_step) (<= publish_step 3)))
(assert (and (<= 0 await_drain_step) (<= await_drain_step 3)))
(assert (and (<= 0 await_phase_read_step) (<= await_phase_read_step 3)))
(assert (distinct close_cas_step publish_step
                  await_drain_step await_phase_read_step))

(assert (< close_cas_step publish_step))
(assert close_awaiting_exit)
(assert (< await_drain_step await_phase_read_step))

; THE DELETED CHECK. Production conditions this on the lifecycle read:
;   (= await_parks (< await_phase_read_step close_cas_step))
; Without it, the await parks no matter what the lifecycle says.
(assert await_parks)

(assert (= byte_consumed_before_entry (< publish_step await_drain_step)))
(assert (= wake_reaches_await (not byte_consumed_before_entry)))
(assert (= await_stranded
           (and await_parks (not wake_reaches_await) close_awaiting_exit)))

; Ask for the violating execution rather than negating a safety claim.
(assert await_stranded)

(check-sat)
(get-model)
