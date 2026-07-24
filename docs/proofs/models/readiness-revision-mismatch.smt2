; Claim: an acknowledged interest update invalidates an older native readiness
; snapshot even when the fd and ownership generation are unchanged.
;
; The source-side oracle is jolt.net.poller/await-ready's complete-token
; comparison after it drains acknowledged mutations.
;
; Expected: unsat. The queried violation requires dispatch of snapshot revision
; 2 after current revision advanced to 3.

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
(declare-const revision_mismatch Bool)
(declare-const dispatch_allowed Bool)
(declare-const stale_revision_dispatch Bool)

(assert (= snapshot_fd 8))
(assert (= current_fd 8))
(assert (= snapshot_generation 10))
(assert (= current_generation 10))
(assert
  (! (= snapshot_revision 2)
     :named snapshot_revision_is_2))
(assert
  (! (= current_revision 3)
     :named current_revision_is_3))
(assert registration_exists)
(assert socket_open)
(assert snapshot_lease_admitted)
(assert native_ready)

(assert
  (! (= revision_mismatch
        (distinct snapshot_revision current_revision))
     :named revision_mismatch_iff_tokens_differ))

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

(assert
  (! (= stale_revision_dispatch
        (and revision_mismatch dispatch_allowed))
     :named violation_iff_mismatch_is_dispatched))

(assert (! stale_revision_dispatch :named property_violated))

(check-sat)
(get-unsat-core)
