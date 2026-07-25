; Same invariant with the design jolt.net.ffi/ensure-subsystem! uses: a
; nil->promise compare-and-set! fuses the read and the write into one atomic
; step, so at most one caller can win it. Whichever caller does NOT win never
; computes its own outcome -- it blocks on (deref) the winner's promise and
; returns exactly the value the winner delivered, so agreement is structural,
; not incidental.
;
; VERIFIED: unsat. unsat core: cas_atomicity, someone_attempts,
; t1_reads_winner, t2_reads_winner, property_violated.

(set-option :produce-unsat-cores true)

(declare-const t1_wins_cas Bool)
(declare-const t2_wins_cas Bool)
(declare-const attempt_count Int)
(declare-const t1_outcome Bool)
(declare-const t2_outcome Bool)

; ATOMICITY: at most one caller wins the nil->promise CAS
(assert (! (not (and t1_wins_cas t2_wins_cas)) :named cas_atomicity))
; PROGRESS: of two concurrent first callers, one of them does win it
(assert (! (or t1_wins_cas t2_wins_cas) :named someone_attempts))

(assert (= attempt_count (+ (ite t1_wins_cas 1 0) (ite t2_wins_cas 1 0))))

; AGREEMENT: the CAS loser never attempts its own WSAStartup -- it derefs the
; winner's promise and returns exactly what was delivered there
(assert (! (=> (not t1_wins_cas) (= t1_outcome t2_outcome)) :named t1_reads_winner))
(assert (! (=> (not t2_wins_cas) (= t2_outcome t1_outcome)) :named t2_reads_winner))

; negation of "exactly one attempt, and every caller agrees on the outcome"
(assert (! (or (not (= attempt_count 1)) (not (= t1_outcome t2_outcome)))
           :named property_violated))

(check-sat)
(get-unsat-core)
