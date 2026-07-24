; Claim (negated below): work selected for one descriptor generation never
; executes after that descriptor number has been closed and reused.
;
; Bounded domain: four distinct steps numbered 0..3, one raw fd, and two
; ownership generations. This is the raw-fd design's known counterexample:
; select old work, close the old owner, reuse the same integer, then execute the
; stale syscall. No generation-bearing ownership check prevents it.
;
; Expected: sat, with select=0, close=1, reuse=2, syscall=3, fd=8, and
; generation 9 replaced by generation 10.

(set-option :produce-unsat-cores true)

(declare-const select_step Int)
(declare-const close_step Int)
(declare-const reuse_step Int)
(declare-const syscall_step Int)
(declare-const selected_fd Int)
(declare-const current_fd Int)
(declare-const selected_generation Int)
(declare-const current_generation Int)
(declare-const stale_syscall Bool)

(assert (and (<= 0 select_step) (<= select_step 3)))
(assert (and (<= 0 close_step) (<= close_step 3)))
(assert (and (<= 0 reuse_step) (<= reuse_step 3)))
(assert (and (<= 0 syscall_step) (<= syscall_step 3)))
(assert (distinct select_step close_step reuse_step syscall_step))

(assert (= selected_fd 8))
(assert (= current_fd 8))
(assert (= selected_generation 9))
(assert (= current_generation 10))

(assert
  (! (= stale_syscall
        (and (< select_step close_step)
             (< close_step reuse_step)
             (< reuse_step syscall_step)
             (= selected_fd current_fd)
             (distinct selected_generation current_generation)))
     :named stale_syscall_iff_unsafe_schedule))

; Negation of the safety claim: require the stale syscall to exist.
(assert (! stale_syscall :named property_violated))

(check-sat)
(get-model)
