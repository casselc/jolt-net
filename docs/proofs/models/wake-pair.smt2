; Bounded observable contract for one wake-writer attempt and one poller close.
;
; Seven distinct steps 0..6 record attempt, retirement, write, handle-lease
; release, writer-count release, write-end close, and read-end close. Occurrence
; counts are 0 or 1. A rejected late attempt has zero writer-side occurrences;
; its unused writer steps remain bounded placeholders and do not affect order.
;
; Implementations: 0 is corrected; 1 admits after retirement; 2 decrements the
; shared count before releasing the write-handle lease; 3 closes without waiting
; for the admitted writer count to drain.
(set-option :produce-unsat-cores true)

(declare-const implementation Int)
(declare-const scenario Int)
(declare-const writer-attempt-step Int)
(declare-const admission-retire-step Int)
(declare-const writer-write-step Int)
(declare-const handle-lease-release-step Int)
(declare-const writer-count-release-step Int)
(declare-const write-close-step Int)
(declare-const read-close-step Int)
(declare-const writer-admission-count Int)
(declare-const writer-write-count Int)
(declare-const handle-lease-release-count Int)
(declare-const writer-count-release-count Int)

(assert (! (and (<= 0 implementation) (<= implementation 3))
           :named implementation-is-bounded))
(assert (! (and (<= 0 writer-attempt-step) (<= writer-attempt-step 6))
           :named writer-attempt-step-is-bounded))
(assert (! (and (<= 0 admission-retire-step) (<= admission-retire-step 6))
           :named admission-retire-step-is-bounded))
(assert (! (and (<= 0 writer-write-step) (<= writer-write-step 6))
           :named writer-write-step-is-bounded))
(assert (! (and (<= 0 handle-lease-release-step)
                (<= handle-lease-release-step 6))
           :named handle-lease-release-step-is-bounded))
(assert (! (and (<= 0 writer-count-release-step)
                (<= writer-count-release-step 6))
           :named writer-count-release-step-is-bounded))
(assert (! (and (<= 0 write-close-step) (<= write-close-step 6))
           :named write-close-step-is-bounded))
(assert (! (and (<= 0 read-close-step) (<= read-close-step 6))
           :named read-close-step-is-bounded))
(assert (! (distinct writer-attempt-step admission-retire-step
                       writer-write-step handle-lease-release-step
                       writer-count-release-step write-close-step
                       read-close-step)
           :named protocol-steps-are-distinct))
(assert (! (and (<= 0 writer-admission-count)
                (<= writer-admission-count 1))
           :named writer-admission-count-is-bounded))
(assert (! (and (<= 0 writer-write-count) (<= writer-write-count 1))
           :named writer-write-count-is-bounded))
(assert (! (and (<= 0 handle-lease-release-count)
                (<= handle-lease-release-count 1))
           :named handle-lease-release-count-is-bounded))
(assert (! (and (<= 0 writer-count-release-count)
                (<= writer-count-release-count 1))
           :named writer-count-release-count-is-bounded))

; Independent arithmetic reference: every violated source relation contributes
; one penalty. Implications are encoded as zero-penalty conditions, never as
; unconstrained semantic flags.
(declare-const reference-penalty-count Int)
(declare-const reference-valid Bool)
(assert (! (= reference-penalty-count
              (+ (ite (= writer-admission-count
                         (ite (< writer-attempt-step admission-retire-step) 1 0))
                   0 1)
                 (ite (= writer-write-count writer-admission-count) 0 1)
                 (ite (= handle-lease-release-count writer-admission-count) 0 1)
                 (ite (= writer-count-release-count writer-admission-count) 0 1)
                 (ite (or (= writer-admission-count 0)
                          (< writer-attempt-step writer-write-step)) 0 1)
                 (ite (or (= writer-admission-count 0)
                          (< writer-write-step handle-lease-release-step)) 0 1)
                 (ite (or (= writer-admission-count 0)
                          (< handle-lease-release-step writer-count-release-step)) 0 1)
                 (ite (< admission-retire-step write-close-step) 0 1)
                 (ite (or (= writer-admission-count 0)
                          (< writer-count-release-step write-close-step)) 0 1)
                 (ite (< write-close-step read-close-step) 0 1)))
           :named reference-penalty-count-definition))
(assert (! (= reference-valid (= reference-penalty-count 0))
           :named reference-classifier-definition))

; Source-shaped implementation relation. Each selector changes one protocol
; requirement while retaining all neighboring count and ordering constraints.
(declare-const implementation-valid Bool)
(assert (! (= implementation-valid
              (and (ite (= implementation 1)
                     true
                     (= writer-admission-count
                        (ite (< writer-attempt-step admission-retire-step) 1 0)))
                   (= writer-write-count writer-admission-count)
                   (= handle-lease-release-count writer-admission-count)
                   (= writer-count-release-count writer-admission-count)
                   (or (= writer-admission-count 0)
                       (< writer-attempt-step writer-write-step))
                   (or (= writer-admission-count 0)
                       (< writer-write-step handle-lease-release-step))
                   (or (= writer-admission-count 0)
                       (ite (= implementation 2)
                         (< writer-count-release-step handle-lease-release-step)
                         (< handle-lease-release-step writer-count-release-step)))
                   (< admission-retire-step write-close-step)
                   (or (= writer-admission-count 0)
                       (= implementation 3)
                       (< writer-count-release-step write-close-step))
                   (< write-close-step read-close-step)))
           :named implementation-classifier-definition))

(declare-const wake-pair-violation Bool)
(assert (! (= wake-pair-violation
              (not (= implementation-valid reference-valid)))
           :named violation-definition))

(push 1)
(assert (! (= implementation 0) :named corrected-selector))
(assert (! wake-pair-violation :named corrected-counterexample-query))
(check-sat) (get-unsat-core) (pop 1)

; Mutant 1: retirement wins at step 0, but the late attempt is admitted.
(push 1)
(assert (= implementation 1)) (assert (= scenario 0))
(assert (= admission-retire-step 0)) (assert (= writer-attempt-step 1))
(assert (= writer-write-step 2)) (assert (= handle-lease-release-step 3))
(assert (= writer-count-release-step 4)) (assert (= write-close-step 5))
(assert (= read-close-step 6))
(assert (= writer-admission-count 1)) (assert (= writer-write-count 1))
(assert (= handle-lease-release-count 1)) (assert (= writer-count-release-count 1))
(assert (not reference-valid)) (assert implementation-valid)
(assert wake-pair-violation) (check-sat) (pop 1)

; Mutant 2: count reaches zero before the lease, so close passes the drain and
; retires the read end while the admitted writer can still use its handle.
(push 1)
(assert (= implementation 2)) (assert (= scenario 1))
(assert (= writer-attempt-step 0)) (assert (= admission-retire-step 1))
(assert (= writer-write-step 2)) (assert (= writer-count-release-step 3))
(assert (= write-close-step 4)) (assert (= read-close-step 5))
(assert (= handle-lease-release-step 6))
(assert (= writer-admission-count 1)) (assert (= writer-write-count 1))
(assert (= handle-lease-release-count 1)) (assert (= writer-count-release-count 1))
(assert (not reference-valid)) (assert implementation-valid)
(assert wake-pair-violation) (check-sat) (pop 1)

; Mutant 3: close ignores the nonzero writer count and retires both pipe ends
; before the admitted writer releases either ownership layer.
(push 1)
(assert (= implementation 3)) (assert (= scenario 2))
(assert (= writer-attempt-step 0)) (assert (= admission-retire-step 1))
(assert (= writer-write-step 2)) (assert (= write-close-step 3))
(assert (= read-close-step 4)) (assert (= handle-lease-release-step 5))
(assert (= writer-count-release-step 6))
(assert (= writer-admission-count 1)) (assert (= writer-write-count 1))
(assert (= handle-lease-release-count 1)) (assert (= writer-count-release-count 1))
(assert (not reference-valid)) (assert implementation-valid)
(assert wake-pair-violation) (check-sat) (pop 1)

; Accepted: an admitted writer crosses retirement, drains, then close completes.
(push 1)
(assert (= implementation 0)) (assert (= scenario 10))
(assert (= writer-attempt-step 0)) (assert (= admission-retire-step 1))
(assert (= writer-write-step 2)) (assert (= handle-lease-release-step 3))
(assert (= writer-count-release-step 4)) (assert (= write-close-step 5))
(assert (= read-close-step 6))
(assert (= writer-admission-count 1)) (assert (= writer-write-count 1))
(assert (= handle-lease-release-count 1)) (assert (= writer-count-release-count 1))
(assert reference-valid) (assert implementation-valid) (check-sat) (pop 1)

; Accepted: a writer completes before retirement; close still orders both ends.
(push 1)
(assert (= implementation 0)) (assert (= scenario 11))
(assert (= writer-attempt-step 0)) (assert (= writer-write-step 1))
(assert (= handle-lease-release-step 2)) (assert (= writer-count-release-step 3))
(assert (= admission-retire-step 4)) (assert (= write-close-step 5))
(assert (= read-close-step 6))
(assert (= writer-admission-count 1)) (assert (= writer-write-count 1))
(assert (= handle-lease-release-count 1)) (assert (= writer-count-release-count 1))
(assert reference-valid) (assert implementation-valid) (check-sat) (pop 1)

; Accepted: a late attempt is rejected and performs no writer-side operation.
(push 1)
(assert (= implementation 0)) (assert (= scenario 12))
(assert (= admission-retire-step 0)) (assert (= writer-attempt-step 1))
(assert (= writer-write-step 2)) (assert (= handle-lease-release-step 3))
(assert (= writer-count-release-step 4)) (assert (= write-close-step 5))
(assert (= read-close-step 6))
(assert (= writer-admission-count 0)) (assert (= writer-write-count 0))
(assert (= handle-lease-release-count 0)) (assert (= writer-count-release-count 0))
(assert reference-valid) (assert implementation-valid) (check-sat) (pop 1)

; Rejected boundaries replay each mutant observation under the corrected selector.
(push 1)
(assert (= implementation 0)) (assert (= scenario 13))
(assert (= admission-retire-step 0)) (assert (= writer-attempt-step 1))
(assert (= writer-write-step 2)) (assert (= handle-lease-release-step 3))
(assert (= writer-count-release-step 4)) (assert (= write-close-step 5))
(assert (= read-close-step 6))
(assert (= writer-admission-count 1)) (assert (= writer-write-count 1))
(assert (= handle-lease-release-count 1)) (assert (= writer-count-release-count 1))
(assert (not reference-valid)) (assert (not implementation-valid)) (check-sat) (pop 1)

(push 1)
(assert (= implementation 0)) (assert (= scenario 14))
(assert (= writer-attempt-step 0)) (assert (= admission-retire-step 1))
(assert (= writer-write-step 2)) (assert (= writer-count-release-step 3))
(assert (= write-close-step 4)) (assert (= read-close-step 5))
(assert (= handle-lease-release-step 6))
(assert (= writer-admission-count 1)) (assert (= writer-write-count 1))
(assert (= handle-lease-release-count 1)) (assert (= writer-count-release-count 1))
(assert (not reference-valid)) (assert (not implementation-valid)) (check-sat) (pop 1)

(push 1)
(assert (= implementation 0)) (assert (= scenario 15))
(assert (= writer-attempt-step 0)) (assert (= admission-retire-step 1))
(assert (= writer-write-step 2)) (assert (= write-close-step 3))
(assert (= read-close-step 4)) (assert (= handle-lease-release-step 5))
(assert (= writer-count-release-step 6))
(assert (= writer-admission-count 1)) (assert (= writer-write-count 1))
(assert (= handle-lease-release-count 1)) (assert (= writer-count-release-count 1))
(assert (not reference-valid)) (assert (not implementation-valid)) (check-sat) (pop 1)
