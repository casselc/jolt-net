; Invariant: concurrent first callers of ensure-subsystem! perform AT MOST ONE
; native WSAStartup call, and every caller -- including ones that lose a race
; -- observes the SAME memoized success/failure outcome.
;
; This models the naive CHECK-THEN-RESET implementation jolt.net.ffi used
; before this fix:
;     (let [s @subsystem]
;       (if (some? s) s
;           (let [ok (zero? (wsastartup))] (reset! subsystem ok) ok)))
;
; The read of @subsystem and the eventual (reset! ...) are separate steps, so
; nothing prevents two threads both observing "untried" before either writes
; -- that gap IS the defect. Each then calls WSAStartup and returns ITS OWN
; call's result, so they can even disagree with each other.
;
; VERIFIED: sat. Counterexample: both threads observe untried, both attempt
; (attempt_count=2), and their own returned outcomes can differ.

(set-option :produce-unsat-cores true)

(declare-const t1_saw_untried Bool)
(declare-const t2_saw_untried Bool)
(declare-const t1_attempts Bool)
(declare-const t2_attempts Bool)
(declare-const t1_outcome Bool)       ; true = this caller's own call succeeded
(declare-const t2_outcome Bool)
(declare-const attempt_count Int)

; the read and the act are separate steps: a caller attempts iff it saw
; "untried", with no atomic fusing of the two
(assert (! (= t1_attempts t1_saw_untried) :named t1_acts_on_what_it_saw))
(assert (! (= t2_attempts t2_saw_untried) :named t2_acts_on_what_it_saw))
(assert (! t1_saw_untried :named t1_observed_untried))
(assert (! t2_saw_untried :named t2_observed_untried))

(assert (= attempt_count (+ (ite t1_attempts 1 0) (ite t2_attempts 1 0))))

; nothing forces agreement: a caller that attempts returns its OWN call's
; outcome, independent of whatever the other caller's call returned
(assert (! (distinct t1_outcome t2_outcome) :named outcomes_can_disagree))

; negation of "at most one attempt, and every caller agrees on the outcome"
(assert (! (or (not (<= attempt_count 1)) (not (= t1_outcome t2_outcome)))
           :named property_violated))

(check-sat)
(get-model)
