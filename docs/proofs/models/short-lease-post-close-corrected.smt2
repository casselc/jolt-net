; Claim: a syscall admitted by a short nonblocking lease cannot begin after the
; descriptor's native close.
;
; Bounded domain: one candidate operation and the five distinct events in its
; lifetime, numbered 0..4. acquire succeeds iff it linearizes before close
; begins; close rejects later admissions. An admitted syscall completes before
; lease release, and native close waits for that release.
;
; Expected: unsat. This checks the violation, not the desired conclusion.

(set-option :produce-unsat-cores true)

(declare-const acquire_step Int)
(declare-const close_begin_step Int)
(declare-const syscall_step Int)
(declare-const release_step Int)
(declare-const native_close_step Int)
(declare-const lease_admitted Bool)
(declare-const syscall_issued Bool)
(declare-const post_close_syscall Bool)

(assert (and (<= 0 acquire_step) (<= acquire_step 4)))
(assert (and (<= 0 close_begin_step) (<= close_begin_step 4)))
(assert (and (<= 0 syscall_step) (<= syscall_step 4)))
(assert (and (<= 0 release_step) (<= release_step 4)))
(assert (and (<= 0 native_close_step) (<= native_close_step 4)))
(assert (distinct acquire_step close_begin_step syscall_step
                  release_step native_close_step))

; One CAS state controls admission: acquire after :closing is rejected.
(assert
  (! (= lease_admitted (< acquire_step close_begin_step))
     :named admission_gate_rejects_after_close))

; Raw descriptor use is available only through an admitted lease.
(assert
  (! (= syscall_issued lease_admitted)
     :named syscall_requires_admitted_lease))

; The syscall is nonblocking/finite and completes before its finally releases
; the lease.
(assert
  (! (=> syscall_issued
          (and (< acquire_step syscall_step)
               (< syscall_step release_step)))
     :named syscall_is_inside_short_lease))

; Native close is deferred until all pre-close admissions drain.
(assert
  (! (=> lease_admitted (< release_step native_close_step))
     :named drain_before_native_close))

(assert
  (! (= post_close_syscall
        (and syscall_issued (< native_close_step syscall_step)))
     :named violation_iff_syscall_begins_after_native_close))

; Negation of the safety claim.
(assert (! post_close_syscall :named property_violated))

(check-sat)
(get-unsat-core)
