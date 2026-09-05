; Bounded observable contract for one coalesced wake epoch.
;
; wake-context 0: producer increments during a pre-snapshot drain, whose restore
; wins the pending-byte CAS. Context 1: producer increments after that drain and
; before the current await's entry comparison, so entry restore wins. Context 2:
; producer increments during a post-poll drain between awaits; drain restore
; preserves the byte, while the next await captures the already-current epoch.
;
; Counts are 0 or 1. The producer is deliberately forced to see pending=true and
; write no byte; only the epoch protocol can preserve progress. Implementations:
; 0 is corrected; 1 omits the epoch increment (the old Boolean-only gate); 2
; omits drain restoration; 3 omits entry restoration.
(set-option :produce-unsat-cores true)

(declare-const implementation Int)
(declare-const scenario Int)
(declare-const wake-context Int)
(declare-const prior-epoch Int)
(declare-const current-epoch Int)
(declare-const producer-increment-count Int)
(declare-const producer-write-count Int)
(declare-const drain-restore-write-count Int)
(declare-const entry-restore-write-count Int)
(declare-const byte-present-count Int)
(declare-const await-progress-count Int)

(assert (! (and (<= 0 implementation) (<= implementation 3))
           :named implementation-is-bounded))
(assert (! (and (<= 0 wake-context) (<= wake-context 2))
           :named wake-context-is-bounded))
(assert (! (and (<= 0 prior-epoch) (<= prior-epoch 2))
           :named prior-epoch-is-bounded))
(assert (! (and (<= 0 current-epoch) (<= current-epoch 3))
           :named current-epoch-is-bounded))
(assert (! (and (<= 0 producer-increment-count)
                (<= producer-increment-count 1))
           :named producer-increment-count-is-bounded))
(assert (! (and (<= 0 producer-write-count) (<= producer-write-count 1))
           :named producer-write-count-is-bounded))
(assert (! (and (<= 0 drain-restore-write-count)
                (<= drain-restore-write-count 1))
           :named drain-restore-write-count-is-bounded))
(assert (! (and (<= 0 entry-restore-write-count)
                (<= entry-restore-write-count 1))
           :named entry-restore-write-count-is-bounded))
(assert (! (and (<= 0 byte-present-count) (<= byte-present-count 1))
           :named byte-present-count-is-bounded))
(assert (! (and (<= 0 await-progress-count) (<= await-progress-count 1))
           :named await-progress-count-is-bounded))

; Independent arithmetic reference classifier. Exactly one restore writer wins
; in each context because the pending-byte CAS coalesces the later ensure call.
(declare-const reference-penalty-count Int)
(declare-const reference-valid Bool)
(assert (! (= reference-penalty-count
              (+ (ite (= producer-increment-count 1) 0 1)
                 (ite (= current-epoch (+ prior-epoch 1)) 0 1)
                 (ite (= producer-write-count 0) 0 1)
                 (ite (= drain-restore-write-count
                         (ite (or (= wake-context 0) (= wake-context 2)) 1 0))
                   0 1)
                 (ite (= entry-restore-write-count
                         (ite (= wake-context 1) 1 0))
                   0 1)
                 (ite (= byte-present-count
                         (ite (> (+ producer-write-count
                                    drain-restore-write-count
                                    entry-restore-write-count) 0) 1 0))
                   0 1)
                 (ite (= await-progress-count byte-present-count) 0 1)))
           :named reference-penalty-count-definition))
(assert (! (= reference-valid (= reference-penalty-count 0))
           :named reference-classifier-definition))

; Source-shaped corrected relation and localized mutant observations.
(declare-const implementation-valid Bool)
(assert (! (= implementation-valid
              (ite (= implementation 0)
                (and (= producer-increment-count 1)
                     (= current-epoch (+ prior-epoch 1))
                     (= producer-write-count 0)
                     (= drain-restore-write-count
                        (ite (or (= wake-context 0) (= wake-context 2)) 1 0))
                     (= entry-restore-write-count
                        (ite (= wake-context 1) 1 0))
                     (= byte-present-count 1)
                     (= await-progress-count 1))
                (ite (= implementation 1)
                  (and (= wake-context 0)
                       (= producer-increment-count 0)
                       (= current-epoch prior-epoch)
                       (= producer-write-count 0)
                       (= drain-restore-write-count 0)
                       (= entry-restore-write-count 0)
                       (= byte-present-count 0)
                       (= await-progress-count 0))
                  (ite (= implementation 2)
                    (and (= wake-context 2)
                         (= producer-increment-count 1)
                         (= current-epoch (+ prior-epoch 1))
                         (= producer-write-count 0)
                         (= drain-restore-write-count 0)
                         (= entry-restore-write-count 0)
                         (= byte-present-count 0)
                         (= await-progress-count 0))
                    (and (= implementation 3)
                         (= wake-context 1)
                         (= producer-increment-count 1)
                         (= current-epoch (+ prior-epoch 1))
                         (= producer-write-count 0)
                         (= drain-restore-write-count 0)
                         (= entry-restore-write-count 0)
                         (= byte-present-count 0)
                         (= await-progress-count 0))))))
           :named implementation-classifier-definition))

(declare-const wake-epoch-violation Bool)
(assert (! (= wake-epoch-violation
              (not (= implementation-valid reference-valid)))
           :named violation-definition))

(push 1)
(assert (! (= implementation 0) :named corrected-selector))
(assert (! wake-epoch-violation :named corrected-counterexample-query))
(check-sat) (get-unsat-core) (pop 1)

; Mutant 1: Boolean-only coalescing records no epoch and loses the wake.
(push 1)
(assert (= implementation 1)) (assert (= scenario 0))
(assert (= wake-context 0)) (assert (= prior-epoch 0))
(assert (= current-epoch 0)) (assert (= producer-increment-count 0))
(assert (= producer-write-count 0)) (assert (= drain-restore-write-count 0))
(assert (= entry-restore-write-count 0)) (assert (= byte-present-count 0))
(assert (= await-progress-count 0))
(assert (not reference-valid)) (assert implementation-valid)
(assert wake-epoch-violation) (check-sat) (pop 1)

; Mutant 2: a between-await drain sees the new epoch but does not restore.
(push 1)
(assert (= implementation 2)) (assert (= scenario 1))
(assert (= wake-context 2)) (assert (= prior-epoch 0))
(assert (= current-epoch 1)) (assert (= producer-increment-count 1))
(assert (= producer-write-count 0)) (assert (= drain-restore-write-count 0))
(assert (= entry-restore-write-count 0)) (assert (= byte-present-count 0))
(assert (= await-progress-count 0))
(assert (not reference-valid)) (assert implementation-valid)
(assert wake-epoch-violation) (check-sat) (pop 1)

; Mutant 3: a post-drain producer is invisible without the entry comparison.
(push 1)
(assert (= implementation 3)) (assert (= scenario 2))
(assert (= wake-context 1)) (assert (= prior-epoch 0))
(assert (= current-epoch 1)) (assert (= producer-increment-count 1))
(assert (= producer-write-count 0)) (assert (= drain-restore-write-count 0))
(assert (= entry-restore-write-count 0)) (assert (= byte-present-count 0))
(assert (= await-progress-count 0))
(assert (not reference-valid)) (assert implementation-valid)
(assert wake-epoch-violation) (check-sat) (pop 1)

; Accepted boundaries: each source position preserves one observable byte.
(push 1)
(assert (= implementation 0)) (assert (= scenario 10))
(assert (= wake-context 0)) (assert (= prior-epoch 0))
(assert (= current-epoch 1)) (assert (= producer-increment-count 1))
(assert (= producer-write-count 0)) (assert (= drain-restore-write-count 1))
(assert (= entry-restore-write-count 0)) (assert (= byte-present-count 1))
(assert (= await-progress-count 1))
(assert reference-valid) (assert implementation-valid) (check-sat) (pop 1)

(push 1)
(assert (= implementation 0)) (assert (= scenario 11))
(assert (= wake-context 1)) (assert (= prior-epoch 1))
(assert (= current-epoch 2)) (assert (= producer-increment-count 1))
(assert (= producer-write-count 0)) (assert (= drain-restore-write-count 0))
(assert (= entry-restore-write-count 1)) (assert (= byte-present-count 1))
(assert (= await-progress-count 1))
(assert reference-valid) (assert implementation-valid) (check-sat) (pop 1)

(push 1)
(assert (= implementation 0)) (assert (= scenario 12))
(assert (= wake-context 2)) (assert (= prior-epoch 2))
(assert (= current-epoch 3)) (assert (= producer-increment-count 1))
(assert (= producer-write-count 0)) (assert (= drain-restore-write-count 1))
(assert (= entry-restore-write-count 0)) (assert (= byte-present-count 1))
(assert (= await-progress-count 1))
(assert reference-valid) (assert implementation-valid) (check-sat) (pop 1)

; Rejected boundaries replay each mutant under the corrected selector.
(push 1)
(assert (= implementation 0)) (assert (= scenario 13))
(assert (= wake-context 0)) (assert (= prior-epoch 0))
(assert (= current-epoch 0)) (assert (= producer-increment-count 0))
(assert (= producer-write-count 0)) (assert (= drain-restore-write-count 0))
(assert (= entry-restore-write-count 0)) (assert (= byte-present-count 0))
(assert (= await-progress-count 0))
(assert (not reference-valid)) (assert (not implementation-valid)) (check-sat) (pop 1)

(push 1)
(assert (= implementation 0)) (assert (= scenario 14))
(assert (= wake-context 2)) (assert (= prior-epoch 0))
(assert (= current-epoch 1)) (assert (= producer-increment-count 1))
(assert (= producer-write-count 0)) (assert (= drain-restore-write-count 0))
(assert (= entry-restore-write-count 0)) (assert (= byte-present-count 0))
(assert (= await-progress-count 0))
(assert (not reference-valid)) (assert (not implementation-valid)) (check-sat) (pop 1)

(push 1)
(assert (= implementation 0)) (assert (= scenario 15))
(assert (= wake-context 1)) (assert (= prior-epoch 0))
(assert (= current-epoch 1)) (assert (= producer-increment-count 1))
(assert (= producer-write-count 0)) (assert (= drain-restore-write-count 0))
(assert (= entry-restore-write-count 0)) (assert (= byte-present-count 0))
(assert (= await-progress-count 0))
(assert (not reference-valid)) (assert (not implementation-valid)) (check-sat) (pop 1)
