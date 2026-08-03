(ns jolt.net.poller
  "Native readiness with a non-blocking, owner-independent waker.

  Registration mutations are values in one acknowledged queue. The caller does
  not return until its mutation has been applied, and every mutation wakes a
  native poll snapshot that may now be stale. Tokens carry both socket ownership
  generation and registration revision; events from an old snapshot are dropped
  unless its complete token is still current.

  There is exactly ONE of each of these, shared by every target: lifecycle,
  registration/token state machine, acknowledged mutation queue, wake
  epoch/coalescing state, stale-event gate, readiness decoder, and
  caller-owned absolute deadline. Two things vary, and only these two:

    - the native wait, in jolt.net.readiness (poll(2) vs WSAPoll); and
    - the wake transport, in jolt.net.wake (self-pipe vs connected loopback
      datagram pair).

  Neither seam owns any registration, token, generation, revision, deadline, or
  lifecycle state. A second copy of that machinery would be a second set of
  bugs."
  (:require [jolt.ffi :as ffi]
            [jolt.net.error :as err]
            [jolt.net.ffi :as nffi]
            [jolt.net.handle :as h]
            [jolt.net.readiness :as r]
            [jolt.net.target :as t]
            [jolt.net.wake :as wake]))

(def ^:private d nffi/descriptor)
(def ^:private next-poller (atom 0))
(def ^:private max-native-wait-ms r/max-native-wait-ms)

(declare submit! signal-wake! wake-transport?)

;; Every target with both a readiness backend and a wake transport. Windows
;; joined in task W4, when the loopback datagram waker made close a completion
;; boundary there rather than a refusal.
(def ^:private poller-targets #{:linux :darwin :windows})

(defn- unsupported! [op]
  (throw (ex-info (str "jolt.net " (name op)
                       ": this target has no readiness runtime")
                  {:jolt.net/op op
                   :jolt.net/kind :unsupported-target
                   :jolt.net/target (jolt.host/target)})))

(defn- require-poller-target! [op]
  (when-not (contains? poller-targets (:os (jolt.host/target)))
    (unsupported! op)))

(defn- take-mutations! [poller]
  (let [q (:mutations poller)]
    (loop []
      (let [old @q]
        (if (empty? old)
          []
          (if (compare-and-set! q old [])
            old
            (recur)))))))

(defn- invalid-token [op token message]
  (err/invalid-ex op message {:jolt.net/token token}))

(defn- current-registration [poller token op]
  (when-not (= (:jolt.net/poller-id token) (:id poller))
    (throw (invalid-token op token "token belongs to another poller")))
  (let [registration (get @(:registrations poller)
                          (:jolt.net/registration token))]
    (when-not (and registration (= token (:token registration)))
      (throw (invalid-token op token "registration token is stale")))
    registration))

(defn current-token?
  "Is a token captured in an earlier snapshot still the current token for its
  registration?

  This is the shared stale-event gate, and the ONLY thing standing between a
  native readiness result and a caller. A token carries both the socket's
  ownership generation and the registration's revision, and a snapshot is taken
  BEFORE the native wait -- so by the time events come back, the registration
  may have been updated (new revision), removed, or replaced by a different
  socket that reused the same descriptor value (new generation). Equality
  against the registration's live token rejects all three, because the token is
  compared whole rather than by descriptor.

  Both backends funnel through this. Neither the POSIX nor the Windows
  readiness path may dispatch an event whose token this rejects."
  [poller token]
  (let [current (get @(:registrations poller)
                     (:jolt.net/registration token))]
    (and (some? current) (= token (:token current)))))

(defn- remove-registration! [poller registration]
  (swap! (:registrations poller) dissoc
         (:jolt.net/registration (:token registration)))
  (h/remove-close-listener! (:socket registration) (:listener registration))
  true)

(defn- apply-mutation! [poller mutation]
  (case (:op mutation)
    :register
    (let [_ (when-not (= :open (:phase @(:lifecycle poller)))
              (throw (err/invalid-ex :register "poller is closed" nil)))
          socket (:socket mutation)
          generation (h/generation socket)
          rid (swap! (:next-registration poller) inc)
          token {:jolt.net/poller-id (:id poller)
                 :jolt.net/registration rid
                 :jolt.net/generation generation
                 :jolt.net/revision 0}
          listener
          (h/on-close!
           socket
           (fn []
              ;; Close owns the socket state before invoking this callback. The
              ;; mutation is therefore ordered before native descriptor close.
             (try
               (submit! poller {:op :remove-closed
                                :registration rid
                                :generation generation})
               (catch :default _ nil))))]
      (when-not listener
        (throw (err/invalid-ex :register "socket is already closed" nil)))
      (swap! (:registrations poller)
             assoc rid {:socket socket
                        :interests (:interests mutation)
                        :token token
                        :listener listener})
      token)

    :update
    (let [_ (when-not (= :open (:phase @(:lifecycle poller)))
              (throw (err/invalid-ex :update "poller is closed" nil)))
          old (current-registration poller (:token mutation) :update)
          token (update (:token old) :jolt.net/revision inc)
          registration (assoc old
                              :interests (:interests mutation)
                              :token token)]
      (swap! (:registrations poller)
             assoc (:jolt.net/registration token) registration)
      token)

    :remove
    (do
      (when-not (= :open (:phase @(:lifecycle poller)))
        (throw (err/invalid-ex :remove "poller is closed" nil)))
      (remove-registration!
       poller
       (current-registration poller (:token mutation) :remove)))

    :remove-closed
    (let [registration (get @(:registrations poller)
                            (:registration mutation))]
      (if (and registration
               (= (:generation mutation)
                  (:jolt.net/generation (:token registration))))
        (remove-registration! poller registration)
        false))

    :clear
    (do
      (doseq [[_ registration] @(:registrations poller)]
        (h/remove-close-listener! (:socket registration)
                                  (:listener registration)))
      (reset! (:registrations poller) {})
      true)

    (throw (err/invalid-ex :poller-mutation "unknown poller mutation"
                           {:jolt.net/mutation (:op mutation)}))))

(defn- drain-mutations! [poller]
  (when (compare-and-set! (:draining poller) false true)
    (try
      (loop []
        (let [batch (take-mutations! poller)]
          (when (seq batch)
            (doseq [mutation batch]
              (try
                (deliver (:ack mutation)
                         {:ok (apply-mutation! poller mutation)})
                (catch :default e
                  (deliver (:ack mutation) {:error e}))))
            (recur))))
      (finally
        (reset! (:draining poller) false)
        ;; A producer can append after the final empty take but before the gate
        ;; is released. Re-acquire in that case so every caller receives an ack.
        (when (seq @(:mutations poller))
          (drain-mutations! poller))))))

(defn acquire-wake-write!
  "Internal forced-interleaving seam. Admit one short wake write and return its
  handle lease, or nil after close has retired wake writes.

  Admission linearizes on a CAS state shared with close. The write-handle lease
  is acquired while admission is held, so close cannot pass the drain point
  between admission and descriptor use.

  Nil means only that admission was already retired before this caller's CAS.
  Once the CAS increments :writers, close cannot retire the sender until this
  caller releases, so a failed handle acquisition is an invariant violation
  and propagates after rolling back the admission count."
  [poller]
  (let [admission (:wake-admission poller)
        unadmit! (fn []
                   (loop []
                     (let [s @admission]
                       (if (compare-and-set! admission s (update s :writers dec))
                         nil
                         (recur)))))]
    (loop []
      (let [old @admission]
        (if-not (= :open (:phase old))
          nil
          (if (compare-and-set! admission old (update old :writers inc))
            (try
              (h/acquire! (:write (:wake poller)))
              (catch :default e
                (unadmit!)
                (throw e)))
            (recur)))))))

(defn release-wake-write!
  "Release an admitted wake write. The handle lease is released BEFORE the
  poller admission count, so close cannot close the pipe read end while a writer
  can still issue write(2)."
  [poller lease]
  (h/release! lease)
  (let [admission (:wake-admission poller)]
    (loop []
      (let [old @admission]
        (if (compare-and-set! admission old (update old :writers dec))
          nil
          (recur))))))

(defn- retire-wake-writes! [poller]
  (let [admission (:wake-admission poller)]
    (loop []
      (let [old @admission]
        (if (= :retired (:phase old))
          nil
          (if (compare-and-set! admission old (assoc old :phase :retired))
            nil
            (recur)))))
    ;; Writers are non-blocking and allocation-bounded. No thread-owned lock is
    ;; involved: any future that releases the last CAS-counted admission makes
    ;; progress even if it shares host thread bookkeeping with this closer.
    (loop []
      (when (pos? (:writers @admission))
        (Thread/yield)
        (recur)))
    ;; Retiring the sender first cannot SIGPIPE an admitted writer: there are
    ;; none left. Keep the receiver open until await has released its lease.
    (wake/retire-sender! (:wake poller)))
  nil)

(defn- wake-call
  "Invoke one non-blocking wake-transport call with its captured native error.

  The direction (:signal! or :drain!) selects the transport's own operation, so
  the POSIX write/read pair and the Winsock send/recv pair -- which differ in
  argument count and in length/result width -- never leak into this shared
  layer. The keyed hook is a deterministic test seam with the same result-map
  shape."
  [poller hook-key direction raw buf len]
  (if-let [hook (get poller hook-key)]
    (hook raw buf len)
    ((direction (:wake poller)) raw buf len)))

(defn- send-wake-byte!
  "Write one byte through the transport's admitted sender lease.

  Returns nil on success or on a would-block, which is not a lost wake: a full
  send buffer means undrained wake data is already queued, so the receiver is
  readable either way. Any other native error throws."
  [poller lease]
  (let [raw (:raw lease)
        buf (ffi/alloc 1)]
    (try
      (ffi/write buf :uint8 0 1)
      (loop []
        (let [{:keys [result code]}
              (wake-call poller :jolt.net/wake-write-call :signal! raw buf 1)]
          (when (neg? result)
            (cond
              (= code (t/errno-code d :eintr))
              (recur)

              (or (= code (t/errno-code d :eagain))
                  (= code (t/errno-code d :ewouldblock)))
              nil

              :else
              (throw (err/native-ex :wake code nil))))))
      (finally (ffi/free buf)))))

(defn- ensure-wake-byte! [poller]
  (when (compare-and-set! (:wake-pending poller) false true)
    (try
      (if-let [lease (acquire-wake-write! poller)]
        (try
          (send-wake-byte! poller lease)
          (finally (release-wake-write! poller lease)))
        ;; Close retired admission after the caller observed an open poller.
        (reset! (:wake-pending poller) false))
      (catch :default e
        (reset! (:wake-pending poller) false)
        ;; Closing can race a redundant ordinary wake. Only propagate while
        ;; still open; the winning close publishes and reports its own terminal
        ;; wake failure after completing teardown.
        (when (= :open (:phase @(:lifecycle poller)))
          (throw e)))))
  nil)

(defn- publish-terminal-wake!
  "Publish close's terminal wake byte, unconditionally.

  This deliberately does NOT go through `ensure-wake-byte!`'s coalescing gate.
  That gate is correct for ordinary wakes, where a pending byte is as good as a
  new one -- but close needs a byte that is definitely IN the receiver, and the
  gate can be observed `true` by a producer whose own send has not landed yet.
  One extra datagram is a trivial price for making cancellation unconditional.

  It runs while sends are still admitted, which is the whole point of the
  ordering: on Windows the published byte is the ONLY thing that forces a parked
  WSAPoll to return, because retiring a datagram SENDER is invisible to its
  peer. POSIX additionally gets POLLHUP from the retired write end, but this
  path does not depend on that.

  Returns nil, or the exception if sender acquisition/publication failed or
  admission was already retired unexpectedly. Close carries that value to the
  end of its sequence and throws it only after the boundary has completed, so
  a transport or invariant fault is reported rather than silently swallowed AND
  rather than leaving the poller stuck in :closing."
  [poller]
  (when (wake-transport? poller)
    ;; Advance the epoch first, exactly as an ordinary wake does, so a drain
    ;; already in flight observes the change and restores a byte after clearing
    ;; the gate. That restoration is best-effort by design -- it cannot run once
    ;; admission is retired -- which is why it is a supplement to await-ready's
    ;; pre-entry terminal check rather than the guarantee itself.
    (swap! (:wake-sequence poller) inc)
    (try
      (if-let [lease (acquire-wake-write! poller)]
        (try
          (send-wake-byte! poller lease)
          ;; A real byte now exists, so any coalesced producer that already set
          ;; the gate is satisfied by it too.
          (reset! (:wake-pending poller) true)
          nil
          (catch :default e e)
          (finally (release-wake-write! poller lease)))
        ;; The winning close invokes this before it retires admission. Nil is
        ;; therefore not a normal lost race: accepting it would let Windows
        ;; report a completed close without publishing its only cancellation.
        (err/invalid-ex
         :wake
         "terminal wake admission retired before publication"
         {:jolt.net/invariant :terminal-wake-before-admission-retirement}))
      (catch :default e
        ;; Sender acquisition itself can fail only if internal ownership was
        ;; corrupted. Defer that exact exception through close's normal failure
        ;; path so both handles and lifecycle still reach their terminal state.
        e))))

(defn wake-transport?
  "Does this poller have a wake transport beneath it?

  False only for the internal Windows readiness adapter, which task W3 used to
  exercise WSAPoll under the shared token machinery before a Windows waker
  existed. It is the single fact every wake-dependent contract is gated on.
  Nothing degrades quietly when it is false; the affected operations refuse."
  [poller]
  (some? (:wake poller)))

(defn- require-wake-transport! [poller op]
  (when-not (wake-transport? poller)
    (throw (err/invalid-ex
            op
            "this readiness adapter has no wake transport"
            {:jolt.net/requires :windows-wake-transport}))))

(defn- signal-wake! [poller]
  ;; The sequence closes the drain/reset coalescing window: if a producer sees
  ;; :wake-pending while the consumer has already drained the byte, the consumer
  ;; observes this increment and restores a byte after clearing the gate.
  (swap! (:wake-sequence poller) inc)
  ;; No transport means no byte to write. The epoch still advances so the
  ;; sequence stays meaningful, but a mutation cannot interrupt an ALREADY
  ;; RUNNING native wait on that target -- which is exactly why the public
  ;; Windows poller stays fail-closed and only the internal adapter, whose
  ;; awaits are sequential, is allowed to run this path.
  (when (wake-transport? poller)
    (ensure-wake-byte! poller)))

(defn- submit! [poller mutation]
  (let [ack (promise)
        entry (assoc mutation :ack ack)]
    (swap! (:mutations poller) conj entry)
    (drain-mutations! poller)
    (let [result (deref ack)]
      (if-let [e (:error result)]
        (throw e)
        (do
          ;; Publish state before its wake epoch. A pre-snapshot drain may then
          ;; consume this wake only after the acknowledged mutation is visible.
          (signal-wake! poller)
          (:ok result))))))

(defn- finish-close! [poller]
  (let [lifecycle (:lifecycle poller)]
    (loop []
      (let [old @lifecycle]
        (cond
          (= :closed (:phase old)) false
          (and (= :closing (:phase old)) (not (:awaiting? old)))
          (if (compare-and-set! lifecycle old
                                (assoc old :phase :closed))
            (do
              ;; Step 8. The sender was already retired before this can run, and
              ;; `awaiting?` is false, so the active native wait has exited and
              ;; released its receiver lease. Retiring the receiver here is the
              ;; last thing that happens before the poller reads :closed.
              (wake/retire-receiver! (:wake poller))
              true)
            (recur))
          :else false)))))

(defn close!
  "Close a poller. Registered sockets are borrowed and remain open.

  With a wake transport, a blocked await is woken and the winning close does
  not return until that await exits and both wake handles are retired. The
  internal wake-less Windows readiness adapter instead refuses atomically while
  an await is active, because nothing could force that await to exit.

  The ordering below is load-bearing, not incidental:

    1. atomically win the lifecycle transition;
    2. clear registrations through the acknowledged mutation queue;
    3. publish a real terminal wake byte, while sends are still admitted;
    4. retire wake-send admission, so no later sender can enter;
    5. wait for already-admitted sends to release their handle leases and
       their CAS-counted writer admission;
    6. retire the sender;
    7. keep the receiver alive and leased until the active native wait exits;
    8. retire the receiver; and
    9. return only once the lifecycle reads :closed.

  Steps 3 and 4 cannot be swapped on Windows. Retiring a datagram SENDER is
  invisible to its connected peer -- there is no hangup to observe -- so the
  byte published at step 3 is the only thing that can force a parked WSAPoll to
  return. POSIX gets the retired pipe's POLLHUP as a second, independent
  guarantee, but this sequence does not rely on having one."
  [poller]
  (let [lifecycle (:lifecycle poller)
        wake? (wake-transport? poller)]
    (loop []
      (let [old @lifecycle]
        (case (:phase old)
          :open
          ;; The wake-less refusal and the transition to :closing must inspect
          ;; the SAME lifecycle value. A separate precheck admits this race:
          ;; close reads awaiting=false, await CASes it true, then close CASes
          ;; that newer value to :closing and waits forever with nothing able
          ;; to interrupt WSAPoll.
          (if (and (not wake?) (:awaiting? old))
            (throw (err/invalid-ex
                    :close
                    "this readiness adapter cannot terminate an active await without a wake transport"
                    {:jolt.net/requires :windows-wake-transport}))
            (do
              ;; Deterministic forced-interleaving seam. Production pollers do
              ;; not carry this key; the Windows W3 gate uses it to make an
              ;; await admission win after this read and before the CAS.
              (when-let [before-cas (:jolt.net/before-close-cas poller)]
                (before-cas old))
              (if (compare-and-set! lifecycle old (assoc old :phase :closing))
                ;; Step 1 is won. Everything below runs exactly once.
                (let [_ (submit! poller {:op :clear})           ; step 2
                      failure (publish-terminal-wake! poller)]  ; step 3
                  (retire-wake-writes! poller)                  ; steps 4, 5, 6
                  ;; Step 7. Wait for the awaiting caller's finally to release
                  ;; the receiver lease; the winning close is a COMPLETION
                  ;; boundary, not merely a stop request.
                  (loop []
                    (when (:awaiting? @lifecycle)
                      (Thread/yield)
                      (recur)))
                  (finish-close! poller)                        ; steps 8, 9
                  ;; A terminal-wake transport fault is reported only here, once
                  ;; the boundary is complete and the poller reads :closed.
                  ;; Throwing at step 3 would strand it in :closing; swallowing
                  ;; it would hide a broken waker behind a bounded-latency
                  ;; native wait cap.
                  (when failure (throw failure))
                  true)
                (recur))))
          :closing false
          :closed false)))))

(defn- poller-value
  "Build the poller value shared by every backend.

  There is exactly ONE registration and token state machine, and this is it.
  The only thing a backend varies is the wake transport value: pass one built by
  jolt.net.wake, or nil for the internal wake-less readiness adapter. Everything
  else -- generation-bearing and revision-bearing tokens, the acknowledged
  mutation queue, snapshot capture, stale-event rejection, wake epoch, writer
  admission, lifecycle -- is identical on every target, so neither a Windows
  poller nor the readiness adapter can drift from the POSIX poller's semantics."
  [transport adapter?]
  (let [poller
        {:jolt.net/poller true
         :jolt.net/readiness-adapter adapter?
         :id (swap! next-poller inc)
         :lifecycle (atom {:phase :open :awaiting? false})
         :registrations (atom {})
         :mutations (atom [])
         :draining (atom false)
         :wake-pending (atom false)
         :wake-sequence (atom 0)
         :wake-admission (atom {:phase :open :writers 0})
         :next-registration (atom 0)
         :wake transport}]
    (assoc poller :close (fn [] (close! poller) nil))))

(defn open-readiness-adapter
  "INTERNAL, Windows only. A poller running the WSAPoll readiness backend with
  NO wake transport.

  This is not `open` and is deliberately not reachable through the public
  jolt.net API. It exists because task W3 exercised real WSAPoll readiness under
  the shared token machinery BEFORE task W4 supplied an owner-independent
  Windows wake transport, and it is retained so that evidence stays
  reproducible: the wake-less refusals below are still the honest behavior of a
  poller constructed without a waker, and they are what the W3 gate proves.
  Production Windows callers get `open`, which always has a transport. What this
  adapter does NOT provide, and refuses rather than fakes:

    - `wake!` throws; there is no byte to write.
    - `close!` throws while an await is running; terminal close against a
      blocked await needs the wake transport.
    - a mutation acknowledged during a running native wait is visible to the
      NEXT await, not to the one already parked. Awaits here are sequential.

  Stale-event rejection is NOT weakened: `current-token?` gates this backend
  exactly as it gates POSIX, which is the invariant W3 proves."
  []
  (when-not (= :windows (:os (jolt.host/target)))
    (unsupported! :open-readiness-adapter))
  ;; Winsock must be up before WSAPoll, as for every other Winsock entry point.
  (nffi/ensure-subsystem!)
  (poller-value nil true))

(defn open
  "Open a poller with this target's owner-independent, non-blocking waker.

  POSIX gets a self-pipe; Windows gets a connected loopback datagram pair. The
  choice, its native calls, and its rollback all live in jolt.net.wake -- this
  function only pairs a transport with the shared state machine."
  []
  (require-poller-target! :open-poller)
  ;; Winsock must be up before any socket call, including the waker's own.
  ;; A no-op elsewhere.
  (nffi/ensure-subsystem!)
  (poller-value (wake/open) false))

(def ^:private valid-interests #{:read :write})

(defn- check-interests! [op interests]
  (when-not (and (set? interests)
                 (every? valid-interests interests))
    (throw (err/invalid-ex op "interests must be a set of :read and/or :write"
                           {:jolt.net/interests interests})))
  interests)

(defn- require-open! [poller op]
  (when-not (= :open (:phase @(:lifecycle poller)))
    (throw (err/invalid-ex op "poller is closed" nil))))

(defn register!
  "Register a borrowed socket and return its generation-bearing token. Returns
  only after the registration is visible to subsequent awaits."
  [poller socket interests]
  (require-open! poller :register)
  (check-interests! :register interests)
  (submit! poller {:op :register :socket socket :interests interests}))

(defn update!
  "Replace a token's interests and return its successor revision. The old token
  becomes stale as soon as this acknowledged call returns."
  [poller token interests]
  (require-open! poller :update)
  (check-interests! :update interests)
  (submit! poller {:op :update :token token :interests interests}))

(defn remove!
  "Remove a current token. Stale or foreign tokens are rejected."
  [poller token]
  (require-open! poller :remove)
  (submit! poller {:op :remove :token token}))

(defn wake-cursor
  "Read this poller's monotonic wake cursor.

  This is the consumer's half of the publication contract, and it exists because
  \"the await had not started yet\" is NOT the same fact as \"the consumer had
  already seen the state behind that wake\".

  A producer publishes in one fixed order: it makes its state visible, and only
  then calls `wake!`, which advances this cursor. A consumer that samples the
  cursor BEFORE it reads the producer-owned state therefore holds a sound
  boundary: every wake at or below the sampled value corresponds to state the
  consumer's own subsequent read was guaranteed to observe, and every wake above
  it belongs to a publication the consumer may have missed and must not park
  through. Passing that sample to `await-ready` is what makes the wait
  undiscardable in the second case.

  Deliberately total: it neither refuses a closing poller nor enters the await
  lifecycle. Consumers sample it once per reactor turn, before any work that
  could observe a close, and a refusal here would only move the race rather than
  remove it. `await-ready` remains the single lifecycle gate."
  [poller]
  @(:wake-sequence poller))

(defn wake!
  "Wake one blocked await. Multiple outstanding wakes are coalesced."
  [poller]
  (require-open! poller :wake)
  ;; Explicit wake is a W4 obligation on Windows. Refusing here keeps the
  ;; contract honest: there is no byte to write, and finite polling is not
  ;; cancellation.
  (require-wake-transport! poller :wake)
  (signal-wake! poller))

;; Interest masking and event normalization moved to jolt.net.readiness, which
;; reads both platforms' flag values from the probed table. They are not
;; reimplemented here: the shared layer must not carry one target's constants.

(defn- drain-wake! [poller raw]
  (let [buf (ffi/alloc 64)
        before @(:wake-sequence poller)
        benign? (:benign-drain-code? (:wake poller))]
    (try
      (loop []
        (let [{:keys [result code]}
              (wake-call poller :jolt.net/wake-read-call :drain! raw buf 64)
              n result]
          (cond
            (pos? n) (recur)
            ;; Zero terminates on both transports: an emptied pipe reports EOF
            ;; that way, and this protocol never sends a zero-length datagram.
            (zero? n) nil
            (= code (t/errno-code d :eintr)) (recur)
            ;; Which drain errors are "nothing more to drain" is a TRANSPORT
            ;; fact, not a shared one -- a connected datagram receiver can also
            ;; surface WSAECONNRESET from a retired peer. See jolt.net.wake.
            :else (when-not (benign? code)
                    (throw (err/native-ex :wake-read code nil))))))
      (finally
        (reset! (:wake-pending poller) false)
        (ffi/free buf)
        ;; A producer that incremented the epoch while the old byte was being
        ;; drained may have coalesced against the old true gate. Restore a byte
        ;; for that later epoch; a producer after the reset writes it itself.
        (when (not= before @(:wake-sequence poller))
          (ensure-wake-byte! poller))))))

(defn- acquire-snapshot [poller]
  (loop [registrations (vals @(:registrations poller))
         entries []
         leases []]
    (if-let [registration (first registrations)]
      (let [lease (try (h/acquire! (:socket registration))
                       (catch :default _ nil))]
        (if lease
          (recur (next registrations)
                 (conj entries
                       {:raw (:raw lease)
                        :token (:token registration)
                        :interests (:interests registration)})
                 (conj leases lease))
          ;; Socket close won the race. Its close-listener mutation removes the
          ;; registration; this snapshot simply omits it.
          (recur (next registrations) entries leases)))
      {:entries entries :leases leases})))

(defn- enter-await! [poller]
  (let [lifecycle (:lifecycle poller)]
    (loop []
      (let [old @lifecycle]
        (when-not (= :open (:phase old))
          (throw (err/invalid-ex :await-ready "poller is closed" nil)))
        (when (:awaiting? old)
          (throw (err/invalid-ex :await-ready
                                 "only one thread may await a poller" nil)))
        (if (compare-and-set! lifecycle old (assoc old :awaiting? true))
          true
          (recur))))))

(defn- exit-await! [poller]
  ;; Clears :awaiting? only -- it must NOT also call finish-close!. The winning
  ;; close! is the sole transport finalizer: it spins on this flag (its step 7)
  ;; and calls finish-close! itself, only after its own sender retirement
  ;; (steps 4-6) has completed. If this function raced ahead and retired the
  ;; receiver here instead, an already-admitted wake writer -- including
  ;; close's own terminal publish, which runs concurrently with this await
  ;; exiting -- could still be holding its write-handle lease with the
  ;; native write(2) not yet issued. Retiring the receiver closes the read end
  ;; immediately (it holds no lease at this point, since the wake lease was
  ;; already released just above in await-ready's finally); the pending
  ;; writer then hits EPIPE against a reader that is already gone.
  (let [lifecycle (:lifecycle poller)]
    (loop []
      (let [old @lifecycle
            next (assoc old :awaiting? false)]
        (if (compare-and-set! lifecycle old next)
          nil
          (recur))))))

(defn- poll-once
  "Invoke poll with its atomically captured errno. The optional map hook is an
  internal deterministic EINTR test seam."
  [poller buf n wait-ms]
  (if-let [hook (:jolt.net/poll-call poller)]
    (hook buf n wait-ms)
    (r/wait! buf n wait-ms)))

(defn- monotonic-nanos
  "Read the deadline clock. The optional map hook is an internal deterministic
  EINTR/deadline test seam; production pollers always use jolt.host."
  [poller]
  (if-let [clock (:jolt.net/monotonic-nanos poller)]
    (clock)
    (jolt.host/monotonic-nanos)))

(defn- native-wait!
  "Drive the native readiness wait against ONE caller-owned absolute monotonic
  deadline. Returns the native ready count, or nil when a signal interrupted the
  wait after the deadline had already passed.

  Each individual native wait is capped at max-native-wait-ms so a missed
  platform wake costs latency rather than making close unbounded; the ceiling is
  a safety net, never an early return from the caller's timeout. An interruption
  does not consume the timeout either -- it retries against the SAME absolute
  deadline rather than restarting a relative one. A deadline-expired
  interruption returns nil instead of a count because the native call may leave
  revents undefined when it fails, so there is nothing safe to decode."
  [poller buf n deadline]
  (loop []
    (let [remaining (max 0 (- deadline (monotonic-nanos poller)))
          remaining-ms (quot (+ remaining 999999) 1000000)
          wait-ms (min remaining-ms max-native-wait-ms)
          outcome (poll-once poller buf n wait-ms)
          result (:result outcome)]
      (cond
        (neg? result)
        (let [code (:code outcome)]
          (if (= code (t/errno-code d :eintr))
            (if (< (monotonic-nanos poller) deadline)
              (recur)
              nil)
            (throw (err/native-ex (:op r/backend) code nil))))

        (and (zero? result)
             (pos? remaining)
             (< (monotonic-nanos poller) deadline))
        (recur)

        :else result))))

(defn await-ready
  "Wait up to timeout-ms for readiness and return
  [{:token token :events #{:read ...}} ...].

  The native wait is capped at one second, so even a missed platform wake cannot
  make close unbounded. Error and hangup are reported independently of requested
  interests; readable data is never discarded merely because hangup is also set.

  The three-argument arity takes a `cursor` from `wake-cursor`, sampled by the
  caller BEFORE it read the producer-owned state it is about to park on. Wakes
  above that cursor cannot be discarded as stale, so a publication that lands
  after the caller's read but before this call still arms the wait.

  The two-argument arity samples the cursor here instead. That is only sound
  when nothing the caller observed could have been published between its read
  and this call -- it makes THIS CALL the boundary, which is exactly the
  assumption a reactor that drains its own queue first must not make. Reactors
  pass an explicit cursor; one-shot waiters with no prior read may omit it."
  ([poller timeout-ms] (await-ready poller timeout-ms nil))
  ([poller timeout-ms cursor]
  ;; The public POSIX poller is target-gated at `open`. The Windows readiness
  ;; adapter is a separate, internal constructor that has already established
  ;; its own preconditions, so it carries a marker rather than re-deriving a
  ;; target check that would reject it.
  (when-not (:jolt.net/readiness-adapter poller)
    (require-poller-target! :await-ready))
  (when-not (and (integer? timeout-ms) (not (neg? timeout-ms)))
    (throw (err/invalid-ex :await-ready
                           "timeout-ms must be a non-negative integer"
                           {:jolt.net/timeout-ms timeout-ms})))
  (when-not (or (nil? cursor) (integer? cursor))
    (throw (err/invalid-ex :await-ready
                           "cursor must be nil or a wake-cursor value"
                           {:jolt.net/wake-cursor cursor})))
  ;; Linearization boundary for explicit wake. A caller-supplied cursor moves
  ;; that boundary back to the caller's own read; nil keeps it here.
  ;;
  ;; A cursor ABOVE the live sequence cannot have come from this poller -- the
  ;; sequence only grows -- so it is a foreign or corrupted value. Refusing is
  ;; the only safe answer: silently honouring it would mark real, undelivered
  ;; wakes as stale and reintroduce exactly the lost-wake park this argument
  ;; exists to prevent.
  (let [live @(:wake-sequence poller)
        _ (when (and cursor (> cursor live))
            (throw (err/invalid-ex
                    :await-ready
                    "cursor is ahead of this poller's wake sequence"
                    {:jolt.net/wake-cursor cursor
                     :jolt.net/wake-sequence live})))
        entry-wake-sequence (or cursor live)]
    (enter-await! poller)
    (let [wake-lease (atom nil)
          socket-leases (atom [])]
      (try
        (drain-mutations! poller)
        (let [;; Whether this poller has a wake transport beneath it. POSIX
              ;; always does. The Windows readiness adapter does not until W4,
              ;; and `base` is what keeps that difference to an array offset
              ;; instead of a second copy of this function.
              wake? (wake-transport? poller)
              base (if wake? 1 0)
              wl (when wake? (h/acquire! (:read (:wake poller))))
              _ (reset! wake-lease wl)
              ;; A mutation completed before this await may have left a
              ;; coalesced byte in the pipe. Its state is already acknowledged,
              ;; so consume it before taking the native snapshot.
              _ (when (and wake? @(:wake-pending poller))
                  (drain-wake! poller (:raw wl)))
              _ (when (and wake?
                           (not= entry-wake-sequence
                                 @(:wake-sequence poller)))
                  (ensure-wake-byte! poller))
              snapshot (acquire-snapshot poller)
              _ (reset! socket-leases (:leases snapshot))
              entries (:entries snapshot)
              n (+ base (count entries))]
          ;; With a wake transport the array always holds at least the pipe, so
          ;; this can only trip on the wake-less adapter. It is a hard error
          ;; rather than a timed sleep: with nothing registered and no wake,
          ;; nothing could ever become ready, and WSAPoll rejects an empty array
          ;; with WSAEINVAL anyway. Sleeping out the deadline here would be
          ;; exactly the emulated wait this slice must not invent.
          (when (zero? n)
            (throw (err/invalid-ex
                    :await-ready
                    "this readiness adapter has no wake transport, so an await with no registrations can never complete"
                    {:jolt.net/requires :windows-wake-transport})))
          (let [buf (r/alloc-entries n)]
            (try
              ;; Slot 0 is the wake pipe's read end wherever one exists.
              (when wake? (r/encode! buf 0 (:raw wl) #{:read}))
              (doseq [[idx entry] (map-indexed vector entries)]
                (r/encode! buf (+ base idx) (:raw entry) (:interests entry)))
              ;; The pre-entry terminal check, and the ONLY reason a Windows
              ;; close cannot lose its cancellation to a race.
              ;;
              ;; Close publishes its terminal byte AFTER winning the :closing
              ;; transition. If that byte is still in the receiver it makes the
              ;; wait below return at once. But this await's own pre-snapshot
              ;; drain, just above, may legitimately have consumed it -- and
              ;; the drain's epoch-restore cannot help once close has retired
              ;; wake-send admission. Ordering saves it: for the byte to have
              ;; been drained here, the publish (and therefore the :closing
              ;; transition that precedes it) must already have happened, so
              ;; this read cannot still see :open. Parking is refused in
              ;; exactly the case where nothing could wake it.
              ;;
              ;; This sits after the last drain and before the native call for
              ;; that reason. Moving it earlier would reopen the window.
              (if-not (= :open (:phase @(:lifecycle poller)))
                []
                (let [deadline (+ (monotonic-nanos poller)
                                  (* timeout-ms 1000000))
                      poll-result (native-wait! poller buf n deadline)]
                  (if (nil? poll-result)
                    []
                    (do
                      (when wake?
                        (let [wake-bits (r/revents buf 0)]
                          (when-not (zero? wake-bits)
                            (drain-wake! poller (:raw wl)))))
                      (drain-mutations! poller)
                      ;; Every event is gated on current-token?: a snapshot taken
                      ;; before the native wait may name a registration that has
                      ;; since been updated, removed, or replaced by a different
                      ;; socket reusing the descriptor.
                      (reduce
                       (fn [ready [idx entry]]
                         (let [bits (r/revents buf (+ base idx))
                               events (r/event-set bits)]
                           (if (and (seq events)
                                    (current-token? poller (:token entry)))
                             (conj ready {:token (:token entry) :events events})
                             ready)))
                       []
                       (map-indexed vector entries))))))
              (finally (ffi/free buf)))))
        (finally
          (doseq [lease @socket-leases] (h/release! lease))
          (when-let [lease @wake-lease] (h/release! lease))
          (exit-await! poller)))))))
