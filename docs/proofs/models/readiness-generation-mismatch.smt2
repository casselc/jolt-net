; Claim: a readiness result captured for one descriptor generation is never
; dispatched to a later owner that reused the same fd integer.
;
; The source-side oracle is jolt.net.poller/current-token?, which
; jolt.net.poller/await-ready applies to every decoded entry: it looks up the
; current registration and compares the complete captured token. This model
; isolates the generation arm of that equality. Both readiness backends -- POSIX
; poll and Windows WSAPoll -- funnel through that one function, so the claim is
; not per-platform.
;
; Expected: unsat. The queried violation requires dispatch even though snapshot
; generation 9 differs from current generation 10.

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
(declare-const generation_mismatch Bool)
(declare-const dispatch_allowed Bool)
(declare-const stale_generation_dispatch Bool)

(assert (= snapshot_fd 8))
(assert (= current_fd 8))
(assert
  (! (= snapshot_generation 9)
     :named snapshot_generation_is_9))
(assert
  (! (= current_generation 10)
     :named current_generation_is_10))
(assert (= snapshot_revision 3))
(assert (= current_revision 3))
(assert registration_exists)
(assert socket_open)
(assert snapshot_lease_admitted)
(assert native_ready)

(assert
  (! (= generation_mismatch
        (distinct snapshot_generation current_generation))
     :named generation_mismatch_iff_tokens_differ))

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
  (! (= stale_generation_dispatch
        (and generation_mismatch dispatch_allowed))
     :named violation_iff_mismatch_is_dispatched))

(assert (! stale_generation_dispatch :named property_violated))

(check-sat)
(get-unsat-core)
