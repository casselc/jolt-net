; Deliberately BUGGY control for the readiness snapshot lifecycle.
;
; This is the same bounded domain as readiness-generation-mismatch.smt2 and
; readiness-revision-mismatch.smt2, with exactly one thing removed: the complete
; token comparison. A backend that decoded native revents and dispatched by
; DESCRIPTOR alone -- "this fd is ready, find whoever is registered on it" --
; would satisfy this model.
;
; The point is that the two corrected models are not vacuously unsat. Their
; unsat depends on the generation and revision conjuncts of
; jolt.net.poller/current-token?, and deleting those conjuncts makes a stale
; dispatch immediately reachable.
;
; Source anchor: jolt.net.poller/current-token?, whose whole-token equality
; against the live registration is the gate this control omits. Both readiness
; backends (POSIX poll and Windows WSAPoll) funnel through that one function, so
; this control speaks for both.
;
; Expected: sat. A witness dispatches snapshot generation 9 to current
; generation 10, and snapshot revision 3 to current revision 4, purely because
; the descriptor number 8 still matches.

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
(declare-const token_is_stale Bool)
(declare-const dispatch_allowed Bool)
(declare-const stale_token_dispatch Bool)

; The descriptor number is reused: this is precisely the case the token exists
; to distinguish, and precisely the one a descriptor-keyed dispatch cannot see.
(assert (= snapshot_fd 8))
(assert (= current_fd 8))

(assert
  (! (= snapshot_generation 9)
     :named snapshot_generation_is_9))
(assert
  (! (= current_generation 10)
     :named current_generation_is_10))
(assert
  (! (= snapshot_revision 3)
     :named snapshot_revision_is_3))
(assert
  (! (= current_revision 4)
     :named current_revision_is_4))

(assert registration_exists)
(assert socket_open)
(assert snapshot_lease_admitted)
(assert native_ready)

; The captured token is stale in BOTH of its bearing fields.
(assert
  (! (= token_is_stale
        (or (distinct snapshot_generation current_generation)
            (distinct snapshot_revision current_revision)))
     :named stale_iff_generation_or_revision_differs))

; THE BUG. Dispatch consults liveness and the descriptor, and never compares the
; complete token. Contrast dispatch_iff_current_complete_token in the corrected
; models, which additionally requires
;   (= snapshot_generation current_generation)
;   (= snapshot_revision   current_revision)
(assert
  (! (= dispatch_allowed
        (and native_ready
             registration_exists
             socket_open
             snapshot_lease_admitted
             (= snapshot_fd current_fd)))
     :named buggy_dispatch_iff_descriptor_matches))

(assert
  (! (= stale_token_dispatch
        (and token_is_stale dispatch_allowed))
     :named violation_iff_stale_token_is_dispatched))

(assert (! stale_token_dispatch :named property_violated))

(check-sat)
(get-model)
