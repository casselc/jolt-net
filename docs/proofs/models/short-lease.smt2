; Bounded observable contract for one handle operation racing close.
;
; Mode 0 is a completed short nonblocking syscall. Mode 1 exposes the wait-for
; graph of a potentially blocking operation. In mode 0, five distinct steps
; cover lease acquisition, close admission, syscall issue, lease release, and
; native close. Mode 1 classifies the wait graph; its step values are bounded
; placeholders rather than a claim that a deadlocked operation completes.
;
; Implementations: 0 is corrected; 1 admits after close begins; 2 lets the
; syscall escape its lease and begin after native close; 3 performs native close
; before an admitted lease releases; 4 accepts the blocking close/return cycle.
(set-option :produce-unsat-cores true)

(declare-const implementation Int)
(declare-const scenario Int)
(declare-const operation-mode Int)
(declare-const acquire-step Int)
(declare-const close-begin-step Int)
(declare-const syscall-step Int)
(declare-const release-step Int)
(declare-const native-close-step Int)
(declare-const lease-admitted Bool)
(declare-const syscall-issued Bool)
(declare-const blocking-syscall-holds-lease Bool)
(declare-const syscall-waits-for-native-close Bool)
(declare-const native-close-waits-for-lease-release Bool)
(declare-const lease-release-waits-for-syscall-return Bool)
(declare-const deadlock Bool)

(assert (! (and (<= 0 implementation) (<= implementation 4))
           :named implementation-is-bounded))
(assert (! (and (<= 0 operation-mode) (<= operation-mode 1))
           :named operation-mode-is-bounded))
(assert (! (and (<= 0 acquire-step) (<= acquire-step 4))
           :named acquire-step-is-bounded))
(assert (! (and (<= 0 close-begin-step) (<= close-begin-step 4))
           :named close-begin-step-is-bounded))
(assert (! (and (<= 0 syscall-step) (<= syscall-step 4))
           :named syscall-step-is-bounded))
(assert (! (and (<= 0 release-step) (<= release-step 4))
           :named release-step-is-bounded))
(assert (! (and (<= 0 native-close-step) (<= native-close-step 4))
           :named native-close-step-is-bounded))
(assert (! (distinct acquire-step close-begin-step syscall-step
                     release-step native-close-step)
           :named lifecycle-steps-are-distinct))

; Declarative reference classifier. Arithmetic penalties keep its decision
; independent of the implementation-side source-shaped Boolean rules.
(declare-const reference-admission-penalty Int)
(declare-const reference-syscall-penalty Int)
(declare-const reference-lease-order-penalty Int)
(declare-const reference-native-close-penalty Int)
(declare-const reference-cycle-definition-penalty Int)
(declare-const reference-deadlock-penalty Int)
(declare-const reference-distance Int)
(declare-const reference-valid Bool)
(assert (! (= reference-admission-penalty
              (ite (or (= operation-mode 1)
                       (= lease-admitted
                          (< acquire-step close-begin-step)))
                0 1))
           :named reference-admission-penalty-definition))
(assert (! (= reference-syscall-penalty
              (ite (or (= operation-mode 1)
                       (= syscall-issued lease-admitted))
                0 1))
           :named reference-syscall-penalty-definition))
(assert (! (= reference-lease-order-penalty
              (ite (or (= operation-mode 1)
                       (not syscall-issued)
                       (and (< acquire-step syscall-step)
                            (< syscall-step release-step)))
                0 1))
           :named reference-lease-order-penalty-definition))
(assert (! (= reference-native-close-penalty
              (ite (or (= operation-mode 1)
                       (not lease-admitted)
                       (< release-step native-close-step))
                0 1))
           :named reference-native-close-penalty-definition))
(assert (! (= reference-cycle-definition-penalty
              (ite (= deadlock
                      (and blocking-syscall-holds-lease
                           syscall-waits-for-native-close
                           native-close-waits-for-lease-release
                           lease-release-waits-for-syscall-return))
                0 1))
           :named reference-cycle-definition-penalty-definition))
(assert (! (= reference-deadlock-penalty (ite deadlock 1 0))
           :named reference-deadlock-penalty-definition))
(assert (! (= reference-distance
              (+ reference-admission-penalty
                 reference-syscall-penalty
                 reference-lease-order-penalty
                 reference-native-close-penalty
                 reference-cycle-definition-penalty
                 reference-deadlock-penalty))
           :named reference-distance-definition))
(assert (! (= reference-valid (= reference-distance 0))
           :named reference-classifier-definition))

; Independently source-shaped implementation classifier. Each mutant changes
; one selected rule without sharing a derived decision helper with the reference.
(declare-const implementation-admission-rule Bool)
(declare-const implementation-syscall-rule Bool)
(declare-const implementation-lease-order-rule Bool)
(declare-const implementation-native-close-rule Bool)
(declare-const implementation-cycle-rule Bool)
(declare-const implementation-deadlock-free-rule Bool)
(declare-const implementation-valid Bool)
(assert (! (= implementation-admission-rule
              (or (= operation-mode 1)
                  (= lease-admitted
                     (ite (= implementation 1)
                       true
                       (< acquire-step close-begin-step)))))
           :named implementation-admission-rule-definition))
(assert (! (= implementation-syscall-rule
              (or (= operation-mode 1)
                  (= syscall-issued lease-admitted)))
           :named implementation-syscall-rule-definition))
(assert (! (= implementation-lease-order-rule
              (or (= operation-mode 1)
                  (not syscall-issued)
                  (ite (= implementation 2)
                    (and (< acquire-step release-step)
                         (< release-step native-close-step)
                         (< native-close-step syscall-step))
                    (and (< acquire-step syscall-step)
                         (< syscall-step release-step)))))
           :named implementation-lease-order-rule-definition))
(assert (! (= implementation-native-close-rule
              (or (= operation-mode 1)
                  (not lease-admitted)
                  (ite (= implementation 3)
                    (< native-close-step release-step)
                    (< release-step native-close-step))))
           :named implementation-native-close-rule-definition))
(assert (! (= implementation-cycle-rule
              (= deadlock
                 (and blocking-syscall-holds-lease
                      syscall-waits-for-native-close
                      native-close-waits-for-lease-release
                      lease-release-waits-for-syscall-return)))
           :named implementation-cycle-rule-definition))
(assert (! (= implementation-deadlock-free-rule
              (or (= implementation 4) (not deadlock)))
           :named implementation-deadlock-free-rule-definition))
(assert (! (= implementation-valid
              (and implementation-admission-rule
                   implementation-syscall-rule
                   implementation-lease-order-rule
                   implementation-native-close-rule
                   implementation-cycle-rule
                   implementation-deadlock-free-rule))
           :named implementation-classifier-definition))

(declare-const short-lease-violation Bool)
(assert (! (= short-lease-violation
              (not (= implementation-valid reference-valid)))
           :named violation-definition))

; Corrected consistency.
(push 1)
(assert (! (= implementation 0) :named corrected-selector))
(assert (! short-lease-violation :named corrected-counterexample-query))
(check-sat) (get-unsat-core) (pop 1)

; Mutant 1: an acquire after close begins is admitted.
(push 1)
(assert (= implementation 1)) (assert (= scenario 0))
(assert (= operation-mode 0))
(assert (= close-begin-step 0)) (assert (= acquire-step 1))
(assert (= syscall-step 2)) (assert (= release-step 3))
(assert (= native-close-step 4))
(assert lease-admitted) (assert syscall-issued)
(assert (not blocking-syscall-holds-lease))
(assert (not syscall-waits-for-native-close))
(assert (not native-close-waits-for-lease-release))
(assert (not lease-release-waits-for-syscall-return))
(assert (not deadlock))
(assert (not reference-valid)) (assert implementation-valid)
(assert short-lease-violation) (check-sat) (pop 1)

; Mutant 2: the syscall escapes its lease and begins after native close.
(push 1)
(assert (= implementation 2)) (assert (= scenario 1))
(assert (= operation-mode 0))
(assert (= acquire-step 0)) (assert (= close-begin-step 1))
(assert (= release-step 2)) (assert (= native-close-step 3))
(assert (= syscall-step 4))
(assert lease-admitted) (assert syscall-issued)
(assert (not blocking-syscall-holds-lease))
(assert (not syscall-waits-for-native-close))
(assert (not native-close-waits-for-lease-release))
(assert (not lease-release-waits-for-syscall-return))
(assert (not deadlock))
(assert (not reference-valid)) (assert implementation-valid)
(assert short-lease-violation) (check-sat) (pop 1)

; Mutant 3: native close precedes the syscall and final lease release.
(push 1)
(assert (= implementation 3)) (assert (= scenario 2))
(assert (= operation-mode 0))
(assert (= acquire-step 0)) (assert (= close-begin-step 1))
(assert (= native-close-step 2)) (assert (= syscall-step 3))
(assert (= release-step 4))
(assert lease-admitted) (assert syscall-issued)
(assert (not blocking-syscall-holds-lease))
(assert (not syscall-waits-for-native-close))
(assert (not native-close-waits-for-lease-release))
(assert (not lease-release-waits-for-syscall-return))
(assert (not deadlock))
(assert (not reference-valid)) (assert implementation-valid)
(assert short-lease-violation) (check-sat) (pop 1)

; Mutant 4: a blocking syscall retains the close-drained lease while only
; native close can unblock it, completing the return/close/release wait cycle.
(push 1)
(assert (= implementation 4)) (assert (= scenario 3))
(assert (= operation-mode 1))
(assert (= acquire-step 0)) (assert (= close-begin-step 1))
(assert (= syscall-step 2)) (assert (= release-step 3))
(assert (= native-close-step 4))
(assert lease-admitted) (assert syscall-issued)
(assert blocking-syscall-holds-lease)
(assert syscall-waits-for-native-close)
(assert native-close-waits-for-lease-release)
(assert lease-release-waits-for-syscall-return)
(assert deadlock)
(assert (not reference-valid)) (assert implementation-valid)
(assert short-lease-violation) (check-sat) (pop 1)

; Accepted boundaries: an admitted close race, completion before close, a
; rejected late acquire, and an incomplete wait graph.
(push 1)
(assert (= implementation 0)) (assert (= scenario 10))
(assert (= operation-mode 0))
(assert (= acquire-step 0)) (assert (= close-begin-step 1))
(assert (= syscall-step 2)) (assert (= release-step 3))
(assert (= native-close-step 4))
(assert lease-admitted) (assert syscall-issued)
(assert (not blocking-syscall-holds-lease))
(assert (not syscall-waits-for-native-close))
(assert (not native-close-waits-for-lease-release))
(assert (not lease-release-waits-for-syscall-return))
(assert (not deadlock))
(assert reference-valid) (assert implementation-valid) (check-sat) (pop 1)

(push 1)
(assert (= implementation 0)) (assert (= scenario 11))
(assert (= operation-mode 0))
(assert (= acquire-step 0)) (assert (= syscall-step 1))
(assert (= release-step 2)) (assert (= close-begin-step 3))
(assert (= native-close-step 4))
(assert lease-admitted) (assert syscall-issued)
(assert (not blocking-syscall-holds-lease))
(assert (not syscall-waits-for-native-close))
(assert (not native-close-waits-for-lease-release))
(assert (not lease-release-waits-for-syscall-return))
(assert (not deadlock))
(assert reference-valid) (assert implementation-valid) (check-sat) (pop 1)

(push 1)
(assert (= implementation 0)) (assert (= scenario 12))
(assert (= operation-mode 0))
(assert (= close-begin-step 0)) (assert (= acquire-step 1))
(assert (= syscall-step 2)) (assert (= release-step 3))
(assert (= native-close-step 4))
(assert (not lease-admitted)) (assert (not syscall-issued))
(assert (not blocking-syscall-holds-lease))
(assert (not syscall-waits-for-native-close))
(assert (not native-close-waits-for-lease-release))
(assert (not lease-release-waits-for-syscall-return))
(assert (not deadlock))
(assert reference-valid) (assert implementation-valid) (check-sat) (pop 1)

(push 1)
(assert (= implementation 0)) (assert (= scenario 13))
(assert (= operation-mode 1))
(assert (= acquire-step 0)) (assert (= close-begin-step 1))
(assert (= syscall-step 2)) (assert (= release-step 3))
(assert (= native-close-step 4))
(assert lease-admitted) (assert syscall-issued)
(assert blocking-syscall-holds-lease)
(assert syscall-waits-for-native-close)
(assert native-close-waits-for-lease-release)
(assert (not lease-release-waits-for-syscall-return))
(assert (not deadlock))
(assert reference-valid) (assert implementation-valid) (check-sat) (pop 1)

; Rejected boundaries replay every mutant under the corrected selector.
(push 1)
(assert (= implementation 0)) (assert (= scenario 14))
(assert (= operation-mode 0))
(assert (= close-begin-step 0)) (assert (= acquire-step 1))
(assert (= syscall-step 2)) (assert (= release-step 3))
(assert (= native-close-step 4))
(assert lease-admitted) (assert syscall-issued)
(assert (not blocking-syscall-holds-lease))
(assert (not syscall-waits-for-native-close))
(assert (not native-close-waits-for-lease-release))
(assert (not lease-release-waits-for-syscall-return))
(assert (not deadlock))
(assert (not reference-valid)) (assert (not implementation-valid))
(check-sat) (pop 1)

(push 1)
(assert (= implementation 0)) (assert (= scenario 15))
(assert (= operation-mode 0))
(assert (= acquire-step 0)) (assert (= close-begin-step 1))
(assert (= release-step 2)) (assert (= native-close-step 3))
(assert (= syscall-step 4))
(assert lease-admitted) (assert syscall-issued)
(assert (not blocking-syscall-holds-lease))
(assert (not syscall-waits-for-native-close))
(assert (not native-close-waits-for-lease-release))
(assert (not lease-release-waits-for-syscall-return))
(assert (not deadlock))
(assert (not reference-valid)) (assert (not implementation-valid))
(check-sat) (pop 1)

(push 1)
(assert (= implementation 0)) (assert (= scenario 16))
(assert (= operation-mode 0))
(assert (= acquire-step 0)) (assert (= close-begin-step 1))
(assert (= native-close-step 2)) (assert (= syscall-step 3))
(assert (= release-step 4))
(assert lease-admitted) (assert syscall-issued)
(assert (not blocking-syscall-holds-lease))
(assert (not syscall-waits-for-native-close))
(assert (not native-close-waits-for-lease-release))
(assert (not lease-release-waits-for-syscall-return))
(assert (not deadlock))
(assert (not reference-valid)) (assert (not implementation-valid))
(check-sat) (pop 1)

(push 1)
(assert (= implementation 0)) (assert (= scenario 17))
(assert (= operation-mode 1))
(assert (= acquire-step 0)) (assert (= close-begin-step 1))
(assert (= syscall-step 2)) (assert (= release-step 3))
(assert (= native-close-step 4))
(assert lease-admitted) (assert syscall-issued)
(assert blocking-syscall-holds-lease)
(assert syscall-waits-for-native-close)
(assert native-close-waits-for-lease-release)
(assert lease-release-waits-for-syscall-return)
(assert deadlock)
(assert (not reference-valid)) (assert (not implementation-valid))
(check-sat) (pop 1)
