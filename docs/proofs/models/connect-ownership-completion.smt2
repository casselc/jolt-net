; Bounded observable contract for ownership transfer and completion ordering of
; one non-blocking connect attempt.
;
; Implementations: 0 is the corrected source design; 1 leaks a synchronous
; failure; 2 returns EINPROGRESS without an owner; 3 lets completion failure
; close and consume the caller's owner; 4 closes the native handle before an
; admitted SO_ERROR read. Scenarios classify successful and failing initiation,
; both completion outcomes, a real close race, and a rejected ownerless return.
(set-option :produce-unsat-cores true)

(declare-datatypes () ((Initiation sync_failure immediate_success in_progress)))
(declare-datatypes () ((Completion no_completion completion_success completion_failure)))
(declare-const implementation Int)
(declare-const scenario Int)
(declare-const initiation Initiation)
(declare-const completion Completion)

; Observable constructor and completion state.
(declare-const returned Bool)
(declare-const raw-open-after-constructor Bool)
(declare-const owner-count-after-constructor Int)
(declare-const owner-count-after-completion Int)
(declare-const finish-runs Bool)
(declare-const finish-initiates-close Bool)

; Observable ordering for one completion racing one caller close.
(declare-const acquire-step Int)
(declare-const close-begin-step Int)
(declare-const getsockopt-step Int)
(declare-const release-step Int)
(declare-const native-close-step Int)
(declare-const lease-admitted Bool)
(declare-const getsockopt-issued Bool)

(assert (! (and (<= 0 owner-count-after-constructor)
                (<= owner-count-after-constructor 1))
           :named constructor-owner-count-is-bounded))
(assert (! (and (<= 0 owner-count-after-completion)
                (<= owner-count-after-completion 1))
           :named completion-owner-count-is-bounded))
(assert (! (and (<= 0 acquire-step) (<= acquire-step 4))
           :named acquire-step-is-bounded))
(assert (! (and (<= 0 close-begin-step) (<= close-begin-step 4))
           :named close-begin-step-is-bounded))
(assert (! (and (<= 0 getsockopt-step) (<= getsockopt-step 4))
           :named getsockopt-step-is-bounded))
(assert (! (and (<= 0 release-step) (<= release-step 4))
           :named release-step-is-bounded))
(assert (! (and (<= 0 native-close-step) (<= native-close-step 4))
           :named native-close-step-is-bounded))
(assert (! (distinct acquire-step close-begin-step getsockopt-step
                     release-step native-close-step)
           :named lifecycle-steps-are-distinct))

; Declarative reference classifier. It assigns an arithmetic penalty to each
; externally visible violation, without using any implementation-side helper.
(declare-const reference-initiation-penalty Int)
(declare-const reference-completion-penalty Int)
(declare-const reference-lease-penalty Int)
(declare-const reference-order-penalty Int)
(declare-const reference-distance Int)
(declare-const reference-valid Bool)
(assert (! (= reference-initiation-penalty
              (ite
                (ite (= initiation sync_failure)
                  (and (not returned)
                       (not raw-open-after-constructor)
                       (= owner-count-after-constructor 0))
                  (and returned
                       raw-open-after-constructor
                       (= owner-count-after-constructor 1)))
                0 1))
           :named reference-initiation-penalty-definition))
(assert (! (= reference-completion-penalty
              (ite
                (and (= (= completion no_completion)
                        (not (= initiation in_progress)))
                     (= finish-runs (not (= completion no_completion)))
                     (not finish-initiates-close)
                     (= owner-count-after-completion
                        owner-count-after-constructor))
                0 1))
           :named reference-completion-penalty-definition))
(assert (! (= reference-lease-penalty
              (ite
                (and (= lease-admitted
                        (and finish-runs (< acquire-step close-begin-step)))
                     (= getsockopt-issued lease-admitted)
                     (=> getsockopt-issued
                         (and (< acquire-step getsockopt-step)
                              (< getsockopt-step release-step))))
                0 1))
           :named reference-lease-penalty-definition))
(assert (! (= reference-order-penalty
              (ite (=> lease-admitted (< release-step native-close-step))
                0 1))
           :named reference-order-penalty-definition))
(assert (! (= reference-distance
              (+ reference-initiation-penalty
                 reference-completion-penalty
                 reference-lease-penalty
                 reference-order-penalty))
           :named reference-distance-definition))
(assert (! (= reference-valid (= reference-distance 0))
           :named reference-classifier-definition))

; Independently derived implementation relation. Every mutant changes one
; selected source-shaped rule; the classifier shares no derived decision helper
; with the arithmetic reference above.
(declare-const implementation-return-rule Bool)
(declare-const implementation-raw-lifetime-rule Bool)
(declare-const implementation-constructor-owner-rule Bool)
(declare-const implementation-completion-domain-rule Bool)
(declare-const implementation-finish-run-rule Bool)
(declare-const implementation-finish-close-rule Bool)
(declare-const implementation-completion-owner-rule Bool)
(declare-const implementation-lease-admission-rule Bool)
(declare-const implementation-getsockopt-rule Bool)
(declare-const implementation-lease-order-rule Bool)
(declare-const implementation-native-close-rule Bool)
(declare-const implementation-valid Bool)
(assert (! (= implementation-return-rule
              (= returned (not (= initiation sync_failure))))
           :named implementation-return-rule-definition))
(assert (! (= implementation-raw-lifetime-rule
              (= raw-open-after-constructor
                 (ite (and (= implementation 1)
                           (= initiation sync_failure))
                   true
                   returned)))
           :named implementation-raw-lifetime-rule-definition))
(assert (! (= implementation-constructor-owner-rule
              (= owner-count-after-constructor
                 (ite returned
                   (ite (and (= implementation 2)
                             (= initiation in_progress))
                     0 1)
                   0)))
           :named implementation-constructor-owner-rule-definition))
(assert (! (= implementation-completion-domain-rule
              (= (= completion no_completion)
                 (not (= initiation in_progress))))
           :named implementation-completion-domain-rule-definition))
(assert (! (= implementation-finish-run-rule
              (= finish-runs (not (= completion no_completion))))
           :named implementation-finish-run-rule-definition))
(assert (! (= implementation-finish-close-rule
              (= finish-initiates-close
                 (and (= implementation 3) finish-runs)))
           :named implementation-finish-close-rule-definition))
(assert (! (= implementation-completion-owner-rule
              (= owner-count-after-completion
                 (ite finish-initiates-close
                   0
                   owner-count-after-constructor)))
           :named implementation-completion-owner-rule-definition))
(assert (! (= implementation-lease-admission-rule
              (= lease-admitted
                 (and finish-runs (< acquire-step close-begin-step))))
           :named implementation-lease-admission-rule-definition))
(assert (! (= implementation-getsockopt-rule
              (= getsockopt-issued lease-admitted))
           :named implementation-getsockopt-rule-definition))
(assert (! (= implementation-lease-order-rule
              (=> getsockopt-issued
                  (and (< acquire-step getsockopt-step)
                       (< getsockopt-step release-step))))
           :named implementation-lease-order-rule-definition))
(assert (! (= implementation-native-close-rule
              (=> lease-admitted
                  (ite (= implementation 4)
                    (< native-close-step getsockopt-step)
                    (< release-step native-close-step))))
           :named implementation-native-close-rule-definition))
(assert (! (= implementation-valid
              (and implementation-return-rule
                   implementation-raw-lifetime-rule
                   implementation-constructor-owner-rule
                   implementation-completion-domain-rule
                   implementation-finish-run-rule
                   implementation-finish-close-rule
                   implementation-completion-owner-rule
                   implementation-lease-admission-rule
                   implementation-getsockopt-rule
                   implementation-lease-order-rule
                   implementation-native-close-rule))
           :named implementation-classifier-definition))

(declare-const connect-ownership-violation Bool)
(assert (! (= connect-ownership-violation
              (not (= implementation-valid reference-valid)))
           :named violation-definition))

; Corrected consistency: every observation is classified the same way by the
; independent reference and source-shaped implementation relation.
(push 1)
(assert (! (= implementation 0) :named corrected-selector))
(assert (! connect-ownership-violation :named corrected-counterexample-query))
(check-sat)
(get-unsat-core)
(pop 1)

; Mutant 1: a synchronous failure returns nothing but leaks its raw descriptor.
(push 1)
(assert (= implementation 1))
(assert (= scenario 0))
(assert (= initiation sync_failure))
(assert (= completion no_completion))
(assert (not returned))
(assert raw-open-after-constructor)
(assert (= owner-count-after-constructor 0))
(assert (= owner-count-after-completion 0))
(assert (not finish-runs))
(assert (not finish-initiates-close))
(assert (not lease-admitted))
(assert (not getsockopt-issued))
(assert (not reference-valid))
(assert implementation-valid)
(assert connect-ownership-violation)
(check-sat)
(pop 1)

; Mutant 2: EINPROGRESS is returned without transferring caller ownership.
(push 1)
(assert (= implementation 2))
(assert (= scenario 1))
(assert (= initiation in_progress))
(assert (= completion completion_failure))
(assert returned)
(assert raw-open-after-constructor)
(assert (= owner-count-after-constructor 0))
(assert (= owner-count-after-completion 0))
(assert finish-runs)
(assert (not finish-initiates-close))
(assert (= acquire-step 0))
(assert (= close-begin-step 1))
(assert (= getsockopt-step 2))
(assert (= release-step 3))
(assert (= native-close-step 4))
(assert lease-admitted)
(assert getsockopt-issued)
(assert (not reference-valid))
(assert implementation-valid)
(assert connect-ownership-violation)
(check-sat)
(pop 1)

; Mutant 3: completion failure closes the socket and consumes its caller owner.
(push 1)
(assert (= implementation 3))
(assert (= scenario 2))
(assert (= initiation in_progress))
(assert (= completion completion_failure))
(assert returned)
(assert raw-open-after-constructor)
(assert (= owner-count-after-constructor 1))
(assert (= owner-count-after-completion 0))
(assert finish-runs)
(assert finish-initiates-close)
(assert (= acquire-step 0))
(assert (= close-begin-step 1))
(assert (= getsockopt-step 2))
(assert (= release-step 3))
(assert (= native-close-step 4))
(assert lease-admitted)
(assert getsockopt-issued)
(assert (not reference-valid))
(assert implementation-valid)
(assert connect-ownership-violation)
(check-sat)
(pop 1)

; Mutant 4: native close retires the descriptor before the admitted SO_ERROR
; inspection rather than draining the completion lease.
(push 1)
(assert (= implementation 4))
(assert (= scenario 3))
(assert (= initiation in_progress))
(assert (= completion completion_failure))
(assert returned)
(assert raw-open-after-constructor)
(assert (= owner-count-after-constructor 1))
(assert (= owner-count-after-completion 1))
(assert finish-runs)
(assert (not finish-initiates-close))
(assert (= acquire-step 0))
(assert (= close-begin-step 1))
(assert (= native-close-step 2))
(assert (= getsockopt-step 3))
(assert (= release-step 4))
(assert lease-admitted)
(assert getsockopt-issued)
(assert (not reference-valid))
(assert implementation-valid)
(assert connect-ownership-violation)
(check-sat)
(pop 1)

; Accepted boundaries: synchronous rollback, immediate success, and both
; completion outcomes retain exactly one caller owner. The failure completion
; also carries the real close-after-lease race.
(push 1)
(assert (= implementation 0))
(assert (= scenario 10))
(assert (= initiation sync_failure))
(assert (= completion no_completion))
(assert (not returned))
(assert (not raw-open-after-constructor))
(assert (= owner-count-after-constructor 0))
(assert (= owner-count-after-completion 0))
(assert (not finish-runs))
(assert (not finish-initiates-close))
(assert (not lease-admitted))
(assert (not getsockopt-issued))
(assert reference-valid)
(assert implementation-valid)
(check-sat)
(pop 1)

(push 1)
(assert (= implementation 0))
(assert (= scenario 11))
(assert (= initiation immediate_success))
(assert (= completion no_completion))
(assert returned)
(assert raw-open-after-constructor)
(assert (= owner-count-after-constructor 1))
(assert (= owner-count-after-completion 1))
(assert (not finish-runs))
(assert (not finish-initiates-close))
(assert (not lease-admitted))
(assert (not getsockopt-issued))
(assert reference-valid)
(assert implementation-valid)
(check-sat)
(pop 1)

(push 1)
(assert (= implementation 0))
(assert (= scenario 12))
(assert (= initiation in_progress))
(assert (= completion completion_success))
(assert returned)
(assert raw-open-after-constructor)
(assert (= owner-count-after-constructor 1))
(assert (= owner-count-after-completion 1))
(assert finish-runs)
(assert (not finish-initiates-close))
(assert (= acquire-step 0))
(assert (= close-begin-step 1))
(assert (= getsockopt-step 2))
(assert (= release-step 3))
(assert (= native-close-step 4))
(assert lease-admitted)
(assert getsockopt-issued)
(assert reference-valid)
(assert implementation-valid)
(check-sat)
(pop 1)

(push 1)
(assert (= implementation 0))
(assert (= scenario 13))
(assert (= initiation in_progress))
(assert (= completion completion_failure))
(assert returned)
(assert raw-open-after-constructor)
(assert (= owner-count-after-constructor 1))
(assert (= owner-count-after-completion 1))
(assert finish-runs)
(assert (not finish-initiates-close))
(assert (= acquire-step 0))
(assert (= close-begin-step 1))
(assert (= getsockopt-step 2))
(assert (= release-step 3))
(assert (= native-close-step 4))
(assert lease-admitted)
(assert getsockopt-issued)
(assert reference-valid)
(assert implementation-valid)
(check-sat)
(pop 1)

; Rejected boundary: an otherwise valid EINPROGRESS return has no owner.
(push 1)
(assert (= implementation 0))
(assert (= scenario 14))
(assert (= initiation in_progress))
(assert (= completion completion_failure))
(assert returned)
(assert raw-open-after-constructor)
(assert (= owner-count-after-constructor 0))
(assert (= owner-count-after-completion 0))
(assert finish-runs)
(assert (not finish-initiates-close))
(assert (= acquire-step 0))
(assert (= close-begin-step 1))
(assert (= getsockopt-step 2))
(assert (= release-step 3))
(assert (= native-close-step 4))
(assert lease-admitted)
(assert getsockopt-issued)
(assert (not reference-valid))
(assert (not implementation-valid))
(check-sat)
(pop 1)
