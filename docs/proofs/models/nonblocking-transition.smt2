; Bounded observable contract for one first-use POSIX non-blocking transition.
;
; Live source declares fcntl's C varargs boundary after two fixed arguments.
; set-raw! returns only after F_SETFL succeeds and a second F_GETFL observes
; O_NONBLOCK. Callers mark a handle only after that return, and short operations
; are admitted only after the mark. The modeled observed-flags domain is 0..3,
; with O_NONBLOCK represented by bit 1 (value 2); this preserves both bit-absent
; and bit-present-with-neighboring-flags cases without modeling target constants.
;
; Implementations: 0 is the corrected source relation; 1 omits the varargs
; declaration; 2 omits the post-F_SETFL read-back requirement; 3 ignores the
; observed O_NONBLOCK bit; 4 permits marking without transition return; 5
; permits short-operation admission without a marked handle.
(set-option :produce-unsat-cores true)

(declare-const implementation Int)
(declare-const scenario Int)
(declare-const varargs-boundary-declared Bool)
(declare-const setfl-rc Int)
(declare-const getfl-call-count Int)
(declare-const observed-flags Int)
(declare-const transition-returned Bool)
(declare-const handle-marked Bool)
(declare-const short-operation-admitted Bool)

(assert (! (and (<= 0 implementation) (<= implementation 5))
           :named implementation-is-bounded))
(assert (! (and (<= -1 setfl-rc) (<= setfl-rc 0))
           :named setfl-return-code-is-bounded))
(assert (! (and (<= 1 getfl-call-count) (<= getfl-call-count 2))
           :named getfl-call-count-is-bounded))
(assert (! (and (<= 0 observed-flags) (<= observed-flags 3))
           :named observed-flags-are-bounded))

; Source-derived concrete flag classification. This Boolean is not free: bit 1
; is present exactly when integer division by 2 is odd in the bounded domain.
(declare-const reference-nonblock-observed Bool)
(assert (! (= reference-nonblock-observed
              (= (mod (div observed-flags 2) 2) 1))
           :named reference-nonblock-observed-definition))

; Chiasmus config-equivalence reference side. Arithmetic penalties keep the
; classifier independent from the implementation's conjunctive source shape.
(declare-const reference-penalty-count Int)
(declare-const reference-valid Bool)
(assert (! (= reference-penalty-count
              (+ (ite varargs-boundary-declared 0 1)
                 (ite (= transition-returned
                         (and (= setfl-rc 0)
                              (= getfl-call-count 2)
                              reference-nonblock-observed))
                   0 1)
                 (ite (= handle-marked transition-returned) 0 1)
                 (ite (= short-operation-admitted handle-marked) 0 1)))
           :named reference-penalty-count-definition))
(assert (! (= reference-valid (= reference-penalty-count 0))
           :named reference-classifier-definition))

; Independently derived implementation side. Each selector removes exactly one
; production requirement while all neighboring requirements remain active.
(declare-const implementation-nonblock-observed Bool)
(declare-const implementation-transition-expected Bool)
(declare-const implementation-valid Bool)
(assert (! (= implementation-nonblock-observed
              (or (= observed-flags 2) (= observed-flags 3)))
           :named implementation-nonblock-observed-definition))
(assert (! (= implementation-transition-expected
              (and (= setfl-rc 0)
                   (ite (= implementation 2)
                     true
                     (= getfl-call-count 2))
                   (ite (= implementation 3)
                     true
                     implementation-nonblock-observed)))
           :named implementation-transition-definition))
(assert (! (= implementation-valid
              (and (ite (= implementation 1)
                     true
                     varargs-boundary-declared)
                   (= transition-returned implementation-transition-expected)
                   (ite (= implementation 4)
                     true
                     (= handle-marked transition-returned))
                   (ite (= implementation 5)
                     true
                     (= short-operation-admitted handle-marked))))
           :named implementation-classifier-definition))

(declare-const nonblocking-transition-violation Bool)
(assert (! (= nonblocking-transition-violation
              (not (= implementation-valid reference-valid)))
           :named violation-definition))

; Corrected consistency query: no bounded observation is classified differently
; by the arithmetic reference and the source-shaped implementation.
(push 1)
(assert (! (= implementation 0) :named corrected-selector))
(assert (! nonblocking-transition-violation
           :named corrected-counterexample-query))
(check-sat)
(get-unsat-core)
(pop 1)

; Mutant 1: the C function is variadic but the binding omits its boundary.
(push 1)
(assert (= implementation 1))
(assert (= scenario 0))
(assert (not varargs-boundary-declared))
(assert (= setfl-rc 0))
(assert (= getfl-call-count 2))
(assert (= observed-flags 2))
(assert transition-returned)
(assert handle-marked)
(assert short-operation-admitted)
(assert (not reference-valid))
(assert implementation-valid)
(assert nonblocking-transition-violation)
(check-sat)
(pop 1)

; Mutant 2: apparent F_SETFL success admits work after only the initial F_GETFL.
(push 1)
(assert (= implementation 2))
(assert (= scenario 1))
(assert varargs-boundary-declared)
(assert (= setfl-rc 0))
(assert (= getfl-call-count 1))
(assert (= observed-flags 2))
(assert transition-returned)
(assert handle-marked)
(assert short-operation-admitted)
(assert (not reference-valid))
(assert implementation-valid)
(assert nonblocking-transition-violation)
(check-sat)
(pop 1)

; Mutant 3: read-back runs, but an absent O_NONBLOCK bit is ignored.
(push 1)
(assert (= implementation 3))
(assert (= scenario 2))
(assert varargs-boundary-declared)
(assert (= setfl-rc 0))
(assert (= getfl-call-count 2))
(assert (= observed-flags 0))
(assert transition-returned)
(assert handle-marked)
(assert short-operation-admitted)
(assert (not reference-valid))
(assert implementation-valid)
(assert nonblocking-transition-violation)
(check-sat)
(pop 1)

; Mutant 4: a caller marks and admits even though set-raw! did not return.
(push 1)
(assert (= implementation 4))
(assert (= scenario 3))
(assert varargs-boundary-declared)
(assert (= setfl-rc -1))
(assert (= getfl-call-count 1))
(assert (= observed-flags 2))
(assert (not transition-returned))
(assert handle-marked)
(assert short-operation-admitted)
(assert (not reference-valid))
(assert implementation-valid)
(assert nonblocking-transition-violation)
(check-sat)
(pop 1)

; Mutant 5: a short operation is admitted without the handle mark.
(push 1)
(assert (= implementation 5))
(assert (= scenario 4))
(assert varargs-boundary-declared)
(assert (= setfl-rc -1))
(assert (= getfl-call-count 1))
(assert (= observed-flags 2))
(assert (not transition-returned))
(assert (not handle-marked))
(assert short-operation-admitted)
(assert (not reference-valid))
(assert implementation-valid)
(assert nonblocking-transition-violation)
(check-sat)
(pop 1)

; Accepted useful behavior: a verified transition marks and admits one operation.
(push 1)
(assert (= implementation 0))
(assert (= scenario 10))
(assert varargs-boundary-declared)
(assert (= setfl-rc 0))
(assert (= getfl-call-count 2))
(assert (= observed-flags 2))
(assert transition-returned)
(assert handle-marked)
(assert short-operation-admitted)
(assert reference-valid)
(assert implementation-valid)
(check-sat)
(pop 1)

; Accepted fail-closed behavior: successful F_SETFL without the observed bit.
(push 1)
(assert (= implementation 0))
(assert (= scenario 11))
(assert varargs-boundary-declared)
(assert (= setfl-rc 0))
(assert (= getfl-call-count 2))
(assert (= observed-flags 0))
(assert (not transition-returned))
(assert (not handle-marked))
(assert (not short-operation-admitted))
(assert reference-valid)
(assert implementation-valid)
(check-sat)
(pop 1)

; Accepted native failure: no post-success read-back, mark, or admission occurs.
(push 1)
(assert (= implementation 0))
(assert (= scenario 12))
(assert varargs-boundary-declared)
(assert (= setfl-rc -1))
(assert (= getfl-call-count 1))
(assert (= observed-flags 0))
(assert (not transition-returned))
(assert (not handle-marked))
(assert (not short-operation-admitted))
(assert reference-valid)
(assert implementation-valid)
(check-sat)
(pop 1)

; Accepted neighboring flags: O_NONBLOCK remains recognized in value 3.
(push 1)
(assert (= implementation 0))
(assert (= scenario 13))
(assert varargs-boundary-declared)
(assert (= setfl-rc 0))
(assert (= getfl-call-count 2))
(assert (= observed-flags 3))
(assert transition-returned)
(assert handle-marked)
(assert short-operation-admitted)
(assert reference-valid)
(assert implementation-valid)
(check-sat)
(pop 1)

; Rejected observations independently pin each of the five requirements.
(push 1)
(assert (= implementation 0))
(assert (= scenario 14))
(assert (not varargs-boundary-declared))
(assert (= setfl-rc 0))
(assert (= getfl-call-count 2))
(assert (= observed-flags 2))
(assert transition-returned)
(assert handle-marked)
(assert short-operation-admitted)
(assert (not reference-valid))
(assert (not implementation-valid))
(check-sat)
(pop 1)

(push 1)
(assert (= implementation 0))
(assert (= scenario 15))
(assert varargs-boundary-declared)
(assert (= setfl-rc 0))
(assert (= getfl-call-count 1))
(assert (= observed-flags 2))
(assert transition-returned)
(assert handle-marked)
(assert short-operation-admitted)
(assert (not reference-valid))
(assert (not implementation-valid))
(check-sat)
(pop 1)

(push 1)
(assert (= implementation 0))
(assert (= scenario 16))
(assert varargs-boundary-declared)
(assert (= setfl-rc 0))
(assert (= getfl-call-count 2))
(assert (= observed-flags 0))
(assert transition-returned)
(assert handle-marked)
(assert short-operation-admitted)
(assert (not reference-valid))
(assert (not implementation-valid))
(check-sat)
(pop 1)

(push 1)
(assert (= implementation 0))
(assert (= scenario 17))
(assert varargs-boundary-declared)
(assert (= setfl-rc -1))
(assert (= getfl-call-count 1))
(assert (= observed-flags 2))
(assert (not transition-returned))
(assert handle-marked)
(assert short-operation-admitted)
(assert (not reference-valid))
(assert (not implementation-valid))
(check-sat)
(pop 1)

(push 1)
(assert (= implementation 0))
(assert (= scenario 18))
(assert varargs-boundary-declared)
(assert (= setfl-rc -1))
(assert (= getfl-call-count 1))
(assert (= observed-flags 2))
(assert (not transition-returned))
(assert (not handle-marked))
(assert short-operation-admitted)
(assert (not reference-valid))
(assert (not implementation-valid))
(check-sat)
(pop 1)
