; Bounded observable contract for errno capture around a failed native call and
; subsequent rollback cleanup.
;
; Domain: positive, distinct failure and cleanup codes, so cleanup genuinely
; clobbers the thread-local errno slot. The observation is the code ultimately
; reported to the caller. Implementations: 0 captures immediately after the
; failure; 1 captures after cleanup. Scenarios identify one accepted corrected
; observation and one rejected clobbered observation.
(set-option :produce-unsat-cores true)

(declare-const implementation Int)
(declare-const scenario Int)
(declare-const fail-code Int)
(declare-const cleanup-code Int)
(declare-const errno-after-fail Int)
(declare-const errno-after-cleanup Int)
(declare-const observed-reported-code Int)

(assert (! (> fail-code 0) :named fail-code-is-error))
(assert (! (> cleanup-code 0) :named cleanup-code-is-error))
(assert (! (distinct fail-code cleanup-code)
           :named cleanup-clobber-is-visible))
(assert (! (= errno-after-fail fail-code) :named failure-sets-errno))
(assert (! (= errno-after-cleanup cleanup-code)
           :named cleanup-overwrites-errno))

; Declarative reference classification: a report is correct exactly when its
; arithmetic distance from the failing operation's code is zero.
(declare-const reference-distance Int)
(declare-const reference-valid Bool)
(assert (! (= reference-distance (- observed-reported-code fail-code))
           :named reference-distance-definition))
(assert (! (= reference-valid (= reference-distance 0))
           :named reference-classifier-definition))

; Independently derived implementation classification. The implementation
; relation shares raw codes with the reference but no derived decision helper.
(declare-const implementation-captured-code Int)
(declare-const implementation-valid Bool)
(assert (! (= implementation-captured-code
              (ite (= implementation 1)
                errno-after-cleanup
                errno-after-fail))
           :named implementation-capture-definition))
(assert (! (= implementation-valid
              (= observed-reported-code implementation-captured-code))
           :named implementation-classifier-definition))

(declare-const errno-capture-violation Bool)
(assert (! (= errno-capture-violation
              (not (= implementation-valid reference-valid)))
           :named violation-definition))

; Corrected consistency query: no observation can be classified differently by
; the independent reference and immediate-capture implementation. Once immediate
; capture is selected this equivalence is definitional; the fault and boundary
; queries below supply the falsifying evidence for capture ordering and visible
; cleanup clobber.
(push 1)
(assert (! (= implementation 0) :named corrected-selector))
(assert (! errno-capture-violation :named corrected-counterexample-query))
(check-sat)
(get-unsat-core)
(pop 1)

; Fault control: cleanup overwrites errno before the implementation captures it.
(push 1)
(assert (= implementation 1))
(assert (= scenario 0))
(assert errno-capture-violation)
(check-sat)
(pop 1)

; Accepted boundary: failure=1, cleanup=2, and the reported code remains 1.
(push 1)
(assert (= implementation 0))
(assert (= scenario 0))
(assert (= fail-code 1))
(assert (= cleanup-code 2))
(assert (= observed-reported-code 1))
(assert reference-valid)
(assert implementation-valid)
(check-sat)
(pop 1)

; Rejected boundary: the same failure reports cleanup's clobbering code 2.
(push 1)
(assert (= implementation 0))
(assert (= scenario 1))
(assert (= fail-code 1))
(assert (= cleanup-code 2))
(assert (= observed-reported-code 2))
(assert (not reference-valid))
(assert (not implementation-valid))
(check-sat)
(pop 1)
