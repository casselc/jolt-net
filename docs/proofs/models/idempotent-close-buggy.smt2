; Invariant: a socket handle issues at most ONE close(2) for its descriptor,
; however many times close! is called and from however many threads.
;
; This matters more than it looks. Descriptors are reused aggressively, so a
; second close on the same number can close an UNRELATED socket that inherited
; it -- surfacing in whatever code owns the new descriptor, far from the cause.
;
; This models the naive CHECK-THEN-ACT implementation:
;     (when-not @closed (close raw) (reset! closed true))
;
; VERIFIED: sat. Counterexample: both threads observe :open, close_count=2.

(declare-const t1_saw_open Bool)
(declare-const t2_saw_open Bool)
(declare-const t1_closes Bool)
(declare-const t2_closes Bool)
(declare-const close_count Int)

; the read and the write are separate steps, so nothing prevents both threads
; observing :open before either writes -- that gap IS the defect
(assert (! (= t1_closes t1_saw_open) :named t1_acts_on_what_it_saw))
(assert (! (= t2_closes t2_saw_open) :named t2_acts_on_what_it_saw))
(assert (! t1_saw_open :named t1_observed_open))
(assert (! t2_saw_open :named t2_observed_open))

(assert (= close_count (+ (ite t1_closes 1 0) (ite t2_closes 1 0))))

; negation of "close is issued exactly once"
(assert (! (not (= close_count 1)) :named property_violated))
