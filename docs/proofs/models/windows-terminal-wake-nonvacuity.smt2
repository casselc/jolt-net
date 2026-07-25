; Control (USEFUL / NON-VACUITY): the corrected design still lets an await
; actually PARK, and still lets close cancel one that did.
;
; windows-terminal-wake-corrected.smt2 is unsat, which alone would also be true
; of a design that never parks at all -- a poller whose pre-entry check refused
; every wait would satisfy the safety claim vacuously and be useless. This model
; carries the same premises and demands a run in which the poller does real
; work:
;
;   1. the await genuinely parks in the native wait (await_parks), AND
;   2. close subsequently begins and publishes its terminal byte, AND
;   3. that byte reaches the parked await, AND
;   4. the await is therefore NOT stranded.
;
; Together these say the pre-entry check does not degenerate into "never wait",
; and that the terminal byte is a real cancellation path rather than a
; formality. Point 3 is the one that matters most: it is the level-triggered
; premise being exercised, not assumed away.
;
; Source anchors and premises are identical to the corrected model; see it for
; the environmental statement about datagram sender retirement not being
; observable to its peer.
;
; Expected: sat. `(get-model)` names a run in which a real wait is really
; cancelled.

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

; Every premise of the corrected model, unchanged.
(assert (< close_cas_step publish_step))
(assert close_awaiting_exit)
(assert (< await_drain_step await_phase_read_step))
(assert (= await_parks (< await_phase_read_step close_cas_step)))
(assert (= byte_consumed_before_entry (< publish_step await_drain_step)))
(assert (= wake_reaches_await (not byte_consumed_before_entry)))
(assert (= await_stranded
           (and await_parks (not wake_reaches_await) close_awaiting_exit)))

; The usefulness demands.
(assert (! await_parks :named the_await_really_parks))
(assert (! wake_reaches_await :named the_terminal_byte_really_reaches_it))
(assert (! (not await_stranded) :named and_it_is_therefore_released))

(check-sat)
(get-model)
