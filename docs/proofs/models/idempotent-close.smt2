; Bounded observable contract for exactly-once native close ownership when two
; callers race on one handle.
;
; Domain: both callers attempt close. The corrected implementation uses one
; open-to-closing CAS winner; mutant 1 performs a split check-then-act so both
; callers that observed open close the descriptor; mutant 2 wins ownership but
; omits the native close side effect. Scenarios classify one accepted single
; close and one rejected double-close observation.
(set-option :produce-unsat-cores true)

(declare-const implementation Int)
(declare-const scenario Int)
(declare-const t1-attempted Bool)
(declare-const t2-attempted Bool)
(declare-const t1-saw-open Bool)
(declare-const t2-saw-open Bool)
(declare-const t1-cas-wins Bool)
(declare-const t2-cas-wins Bool)
(declare-const observed-close-count Int)

; Source-shaped corrected ownership constraints. Both callers enter the close
; race; atomicity supplies at most one winner and progress supplies at least one.
(assert (! t1-attempted :named t1-attempts-close))
(assert (! t2-attempted :named t2-attempts-close))
(assert (! (not (and t1-cas-wins t2-cas-wins))
           :named cas-atomicity))
(assert (! (or t1-cas-wins t2-cas-wins)
           :named close-owner-progress))

; Declarative reference classification: the externally observed native close
; count is correct exactly when its arithmetic distance from one is zero.
(declare-const reference-distance Int)
(declare-const reference-valid Bool)
(assert (! (= reference-distance (- observed-close-count 1))
           :named reference-distance-definition))
(assert (! (= reference-valid (= reference-distance 0))
           :named reference-classifier-definition))

; Independently derived implementation classification. Selector 0 closes for
; the unique CAS winner; selector 1 closes for every stale open observation;
; selector 2 models a winner that omits the native close side effect.
(declare-const implementation-t1-closes Bool)
(declare-const implementation-t2-closes Bool)
(declare-const implementation-close-count Int)
(declare-const implementation-valid Bool)
(assert (! (= implementation-t1-closes
              (ite (= implementation 0)
                t1-cas-wins
                (ite (= implementation 1) t1-saw-open false)))
           :named implementation-t1-close-definition))
(assert (! (= implementation-t2-closes
              (ite (= implementation 0)
                t2-cas-wins
                (ite (= implementation 1) t2-saw-open false)))
           :named implementation-t2-close-definition))
(assert (! (= implementation-close-count
              (+ (ite implementation-t1-closes 1 0)
                 (ite implementation-t2-closes 1 0)))
           :named implementation-count-definition))
(assert (! (= implementation-valid
              (= observed-close-count implementation-close-count))
           :named implementation-classifier-definition))

(declare-const idempotent-close-violation Bool)
(assert (! (= idempotent-close-violation
              (not (= implementation-valid reference-valid)))
           :named violation-definition))

; Corrected consistency query: atomic ownership plus progress makes the
; implementation count one, so no observation is classified differently from
; the independent reference. The mutants and boundaries below carry the
; falsifying evidence for both safety and progress.
(push 1)
(assert (! (= implementation 0) :named corrected-selector))
(assert (! idempotent-close-violation
           :named corrected-counterexample-query))
(check-sat)
(get-unsat-core)
(pop 1)

; Safety fault: both racing callers observed open before either state update,
; so the split check-then-act implementation issues two native closes.
(push 1)
(assert (= implementation 1))
(assert (= scenario 0))
(assert t1-attempted)
(assert t2-attempted)
(assert t1-saw-open)
(assert t2-saw-open)
(assert (= observed-close-count 1))
(assert idempotent-close-violation)
(check-sat)
(pop 1)

; Progress fault: one caller wins the ownership transition, but the native
; close side effect is omitted entirely.
(push 1)
(assert (= implementation 2))
(assert (= scenario 0))
(assert t1-attempted)
(assert t2-attempted)
(assert t1-cas-wins)
(assert (not t2-cas-wins))
(assert (= observed-close-count 1))
(assert idempotent-close-violation)
(check-sat)
(pop 1)

; Accepted boundary: both callers attempt close, exactly one wins, and one
; native close is observed.
(push 1)
(assert (= implementation 0))
(assert (= scenario 0))
(assert t1-attempted)
(assert t2-attempted)
(assert t1-cas-wins)
(assert (not t2-cas-wins))
(assert (= observed-close-count 1))
(assert reference-valid)
(assert implementation-valid)
(check-sat)
(pop 1)

; Rejected boundary: the same corrected ownership state rejects an observation
; containing two native closes.
(push 1)
(assert (= implementation 0))
(assert (= scenario 1))
(assert t1-attempted)
(assert t2-attempted)
(assert t1-cas-wins)
(assert (not t2-cas-wins))
(assert (= observed-close-count 2))
(assert (not reference-valid))
(assert (not implementation-valid))
(check-sat)
(pop 1)
