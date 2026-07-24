; Semantic control for short-lease-post-close-corrected.smt2.
;
; If an uninterruptible blocking syscall holds the same lease that close waits
; to drain, while only native close can unblock the syscall, the wait-for graph
; contains a real cycle:
;
;   syscall return -> native close -> lease release -> syscall return
;
; This is why jolt.net leases only short nonblocking syscalls. A wakeable poll
; wait must not hold the descriptor-operation lease needed by close.
;
; Expected: sat, with all three wait edges and deadlock=true.

(set-option :produce-unsat-cores true)

(declare-const blocking_syscall_holds_lease Bool)
(declare-const syscall_waits_for_native_close Bool)
(declare-const native_close_waits_for_lease_release Bool)
(declare-const lease_release_waits_for_syscall_return Bool)
(declare-const deadlock Bool)

(assert
  (! blocking_syscall_holds_lease
     :named blocking_syscall_retains_close_lease))
(assert
  (! syscall_waits_for_native_close
     :named native_close_is_only_unblocker))
(assert
  (! native_close_waits_for_lease_release
     :named close_drains_lease))
(assert
  (! lease_release_waits_for_syscall_return
     :named finally_releases_after_return))

(assert
  (! (= deadlock
        (and blocking_syscall_holds_lease
             syscall_waits_for_native_close
             native_close_waits_for_lease_release
             lease_release_waits_for_syscall_return))
     :named deadlock_iff_wait_cycle))

(assert (! deadlock :named deadlock_control_witness))

(check-sat)
(get-model)
