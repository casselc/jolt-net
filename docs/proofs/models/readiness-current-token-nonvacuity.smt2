; Non-vacuity control for both readiness mismatch models.
;
; A current open registration with an admitted snapshot lease, native readiness,
; and matching fd/generation/revision must remain dispatchable. This rules out a
; deny-all token model.
;
; Expected: sat, with dispatch_allowed=true for fd 8, generation 10, revision 3.

(set-option :produce-unsat-cores true)

(declare-const snapshot_fd Int)
(declare-const current_fd Int)
(declare-const snapshot_generation Int)
(declare-const current_generation Int)
(declare-const snapshot_revision Int)
(declare-const current_revision Int)
(declare-const registration_exists Bool)
(declare-const socket_open Bool)
(declare-const snapshot_lease_admitted Bool)
(declare-const native_ready Bool)
(declare-const dispatch_allowed Bool)

(assert (= snapshot_fd 8))
(assert (= current_fd 8))
(assert (= snapshot_generation 10))
(assert (= current_generation 10))
(assert (= snapshot_revision 3))
(assert (= current_revision 3))
(assert registration_exists)
(assert socket_open)
(assert snapshot_lease_admitted)
(assert native_ready)

(assert
  (! (= dispatch_allowed
        (and native_ready
             registration_exists
             socket_open
             snapshot_lease_admitted
             (= snapshot_fd current_fd)
             (= snapshot_generation current_generation)
             (= snapshot_revision current_revision)))
     :named dispatch_iff_current_complete_token))

(assert (! dispatch_allowed :named valid_dispatch_reachable))

(check-sat)
(get-model)
