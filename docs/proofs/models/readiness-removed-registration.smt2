; Claim: a readiness result captured for a registration that has since been
; REMOVED is never dispatched.
;
; The generation and revision models cover a registration that still exists but
; has been superseded. This one covers the other invalidation the snapshot
; lifecycle admits: an acknowledged remove!, or a socket close whose ordered
; :remove-closed mutation ran while the native wait was parked. In both cases
; the registration entry is simply gone by the time revents are decoded, and the
; captured token has nothing to be equal to.
;
; Source anchor: jolt.net.poller/current-token?, whose (some? current) conjunct
; is what this model isolates -- a map lookup miss must deny dispatch rather
; than fall through to a nil-tolerant comparison. jolt.net.poller/await-ready
; applies it to every decoded entry, for both the POSIX poll and Windows WSAPoll
; backends.
;
; Note what is NOT claimed. This is about DISPATCH, not about cancelling a
; native wait already in progress: on Windows there is no wake transport until
; task W4, so a removal cannot interrupt a parked WSAPoll. It can only fail to
; be delivered afterwards, which is exactly what this model states.
;
; Expected: unsat. The queried violation requires dispatching a token whose
; registration does not exist.

(set-option :produce-unsat-cores true)

(declare-const snapshot_fd Int)
(declare-const current_fd Int)
(declare-const snapshot_generation Int)
(declare-const snapshot_revision Int)
(declare-const registration_exists Bool)
(declare-const socket_open Bool)
(declare-const snapshot_lease_admitted Bool)
(declare-const native_ready Bool)
(declare-const token_matches_live_registration Bool)
(declare-const dispatch_allowed Bool)
(declare-const removed_registration_dispatch Bool)

; The snapshot was taken while the registration was live and its socket open,
; so nothing about the CAPTURE is malformed. Native readiness genuinely arrived.
(assert (= snapshot_fd 8))
(assert (= current_fd 8))
(assert (= snapshot_generation 10))
(assert (= snapshot_revision 3))
(assert socket_open)
(assert snapshot_lease_admitted)
(assert native_ready)

; The registration was removed between snapshot capture and event decoding.
(assert
  (! (not registration_exists)
     :named registration_removed_before_decode))

; With no entry present there is no live token to be equal to. A missing
; registration can never match a captured token.
(assert
  (! (=> (not registration_exists)
         (not token_matches_live_registration))
     :named absent_registration_matches_nothing))

; current-token? requires BOTH that the registration is present and that the
; complete captured token equals the live one.
(assert
  (! (= dispatch_allowed
        (and native_ready
             socket_open
             snapshot_lease_admitted
             registration_exists
             token_matches_live_registration))
     :named dispatch_iff_present_and_token_matches))

(assert
  (! (= removed_registration_dispatch
        (and (not registration_exists) dispatch_allowed))
     :named violation_iff_removed_registration_is_dispatched))

(assert (! removed_registration_dispatch :named property_violated))

(check-sat)
(get-unsat-core)
