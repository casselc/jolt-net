(ns jolt.net.poller-wake-test-main
  "Dependency-free entry point for `bin/jnc -M:poller-wake-test` -- task W4.

  Like the W1, W2, and W3 mains, this alias resolves no Git dependency before
  running. Unlike them, it drives the PUBLIC `jolt.net/open-poller` over real
  platform sockets, because task W4's whole claim is that the public poller now
  works on Windows: an owner-independent wake transport, a close that is a
  completion boundary rather than a refusal, and readiness-driven blocking
  accept.

  How this suite decides something passed
  ---------------------------------------

  No elapsed time is ever a success oracle, and no `sleep` synchronizes
  anything. Three honest oracles are used instead:

    1. Deadline separation. A blocked await is given a timeout far LARGER than
       the watchdog used to observe it (`wake-await-ms` vs `watchdog-ms`). If
       the await returns at all within the watchdog, it cannot have returned by
       expiry -- its own deadline could not possibly have arrived yet -- so the
       wake demonstrably worked. This is a statement about which deadline is
       reachable, not about how fast anything was.

    2. Atomic state. `:awaiting?` in the poller's own lifecycle is the exact
       fact \"an await has been admitted\", so entry is observed by spinning on
       it rather than guessed at with a delay.

    3. Promises and deterministic hooks, for interleavings the scheduler will
       not reliably produce on its own -- an await admission that wins between
       close's read and its CAS, a wake landing inside the drain/reset window,
       and close running while a wait is provably parked.

  Every `deref` timeout below is a WATCHDOG: exceeding one is reported as a
  failure, never as a pass.

  Where a test replaces the native call with a hook, it says so and says why.
  Those cases are lifecycle and ownership gates; the WSAPoll ABI itself is
  established by the W3 gate and by the real-readiness cases here, not by a
  hooked one."
  (:require [jolt.net.check :as c]
            [jolt.net.ffi :as nffi]
            [jolt.net.handle :as h]
            [jolt.net.poller :as poller]
            [jolt.net.readiness :as r]
            [jolt.net.wake :as wake]
            [jolt.net.windows-handle-count :as whc]
            [jolt.net :as net]))

(def ^:private d nffi/descriptor)
(defn- windows? [] (= :windows (:os (jolt.net.target/current-target))))

(def ^:private suite-timeout-ms 180000)
(def ^:private timeout-token ::timeout)

;; A blocked await is given this timeout. It is deliberately far beyond any
;; watchdog below, so "it returned" can only mean "something woke it".
(def ^:private wake-await-ms 120000)
;; The observation watchdog. Exceeding it is a FAILURE.
(def ^:private watchdog-ms 15000)
;; A watchdog for something that is expected NOT to complete. Short, because a
;; false negative here only weakens the test, never fakes a pass.
(def ^:private blocked-probe-ms 250)

(defn- deadline-ns [ms] (+ (jolt.net.target/monotonic-nanos) (* ms 1000000)))
(defn- expired? [deadline] (< deadline (jolt.net.target/monotonic-nanos)))

(defn- spin-until!
  "Busy-wait on an atomic predicate until it holds, or the budget expires.
  Returns true if it held. This observes STATE, never elapsed time: the
  deadline exists only so a broken run fails instead of hanging."
  ([pred] (spin-until! pred watchdog-ms))
  ([pred budget-ms]
   (let [deadline (deadline-ns budget-ms)]
     (loop []
       (cond
         (pred) true
         (expired? deadline) false
         :else (do (Thread/yield) (recur)))))))

(defn- awaiting? [p] (:awaiting? @(:lifecycle p)))
(defn- phase [p] (:phase @(:lifecycle p)))

(defn- blocked-await!
  "Start an await with the long wake timeout and return its future only once the
  poller's own lifecycle says the await has been admitted."
  ([p] (blocked-await! p wake-await-ms))
  ([p timeout-ms]
   (let [f (future (try {:ok (poller/await-ready p timeout-ms)}
                        (catch :default e {:ex (ex-data e)})))]
     (if (spin-until! #(awaiting? p))
       f
       (do (c/check "PRECONDITION: an await was admitted" true false) f)))))

(defn- woke?
  "Did this await return before its own deadline could have arrived?"
  [f]
  (let [r (deref f watchdog-ms timeout-token)]
    (if (= timeout-token r) timeout-token r)))

;; --- fixtures ----------------------------------------------------------------

(defn- with-poller! [f]
  (let [p (net/open-poller)]
    (try (f p) (finally (net/close! p)))))

(defn- with-listener! [f]
  (let [l (net/listen (net/endpoint "127.0.0.1" 0) {:reuse-address? true})]
    (try (f l) (finally (net/close! l)))))

(defn- connect-client!
  "Initiate a client connection to this listener. Returns the owned socket."
  [listener]
  (let [port (:jolt.net/port (net/local-endpoint listener))]
    (:jolt.net/socket (net/try-connect (net/endpoint "127.0.0.1" port)))))

(defn- with-pair!
  "A connected client/server pair. The blocking accept is the synchronization
  point -- it returns only after the handshake -- so no sleep stands in for it."
  [f]
  (with-listener!
    (fn [l]
      (let [client (connect-client! l)
            server (net/accept l)]
        (net/finish-connect! client)
        (try (f client server)
             (finally (net/close! server) (net/close! client)))))))

;; --- transport facts ---------------------------------------------------------

(defn- transport-suite! []
  (c/section "w4: the wake transport this target actually got")
  (c/check "the readiness backend names a wake transport"
           :windows-datagram (r/wake-transport))
  (c/check "the transport module agrees" :windows-datagram wake/kind)
  ;; The single most important behavioral fact about this transport, and the
  ;; reason close publishes a byte BEFORE retiring sends.
  (c/check "a datagram peer close is not a hangup oracle"
           :byte-only wake/terminal-wake)
  (with-poller!
    (fn [p]
      (c/check "the public Windows poller has a transport"
               true (poller/wake-transport? p))
      (let [t (:wake p)]
        (c/check "both wake handles are owned" [true true]
                 [(h/handle? (:read t)) (h/handle? (:write t))])
        ;; Both ends must be non-blocking BEFORE ownership is published, or an
        ;; admitted wake send could park inside close's drain wait.
        (c/check "both wake sockets were transitioned before publication"
                 [true true]
                 [(h/nonblocking? (:read t)) (h/nonblocking? (:write t))])
        (c/check-pred "the wake handles are distinct sockets"
                      #(not= (first %) (second %))
                      [(h/raw (:read t)) (h/raw (:write t))])))))

;; --- wake ---------------------------------------------------------------------

(defn- wake-suite! []
  (c/section "w4: explicit wake over the public poller")
  ;; An EMPTY poller. On the wake-less W3 adapter this was a hard error, because
  ;; nothing could ever complete. With a transport the wake socket is always
  ;; slot 0, so an empty poller is an ordinary wait -- and wakeable.
  (with-poller!
    (fn [p]
      (let [f (blocked-await! p)
            _ (net/wake! p)
            r (woke? f)]
        (c/check "an empty poller's await is woken, not refused" {:ok []} r))))

  ;; A poller with a real, NOT-ready registration. The only thing that can
  ;; complete this wait is the wake byte.
  (with-pair!
    (fn [_client server]
      (with-poller!
        (fn [p]
          (poller/register! p server #{:read})
          (let [f (blocked-await! p)
                _ (net/wake! p)
                r (woke? f)]
            (c/check "a blocked await over a live registration is woken"
                     {:ok []} r))))))

  ;; Coalescing: several wakes outstanding must not desynchronize the gate.
  (with-poller!
    (fn [p]
      (let [f (blocked-await! p)]
        (dotimes [_ 8] (net/wake! p))
        (c/check "repeated wakes coalesce into one completed await"
                 {:ok []} (woke? f)))
      ;; And the poller is still usable afterwards: the gate was reset, not left
      ;; latched by the coalesced burst.
      (let [f (blocked-await! p)
            _ (net/wake! p)]
        (c/check "the poller still wakes after a coalesced burst"
                 {:ok []} (woke? f)))))

  (c/section "w4: a wake inside the drain/reset coalescing window")
  ;; The window: the consumer has drained the byte but not yet cleared the
  ;; gate, so a producer can coalesce against a gate that is about to become
  ;; stale. The epoch counter closes it -- the consumer notices the epoch moved
  ;; and restores a byte after clearing the gate. Forced here with the drain
  ;; hook, because the scheduler will not reliably produce it.
  (let [p0 (net/open-poller)
        drains (atom 0)
        raced (atom false)
        p (assoc p0
                 :jolt.net/wake-read-call
                 (fn [raw buf len]
                   ;; Inject exactly one concurrent wake DURING the first drain,
                   ;; then perform the real drain call underneath it.
                   (when (compare-and-set! raced false true)
                     (net/wake! p0))
                   (swap! drains inc)
                   ((:drain! (:wake p0)) raw buf len)))]
    (try
      (let [f (blocked-await! p)
            _ (net/wake! p)
            r (woke? f)]
        (c/check "the await woken during the drain race completed" {:ok []} r)
        (c/check "the drain hook actually ran" true (pos? @drains))
        (c/check "the injected in-window wake was delivered" true @raced))
      ;; The wake injected inside the window must not have been swallowed: a
      ;; following await must still be wakeable, and the restored byte must not
      ;; have latched the gate permanently either.
      (let [f (blocked-await! p)
            _ (net/wake! p)]
        (c/check "the poller remains wakeable after the drain race"
                 {:ok []} (woke? f)))
      (finally (net/close! p0)))))

;; --- acknowledged mutations against a running wait ----------------------------

(defn- mutation-suite! []
  (c/section "w4: acknowledged mutations interrupt a running wait")
  ;; On the W3 wake-less adapter a mutation was only visible to the NEXT await.
  ;; With a transport it must interrupt the one already parked.
  (with-pair!
    (fn [_client server]
      (with-poller!
        (fn [p]
          (let [token (poller/register! p server #{:read})
                f (blocked-await! p)
                token' (poller/update! p token #{:read :write})
                r (woke? f)]
            ;; `:awaiting?` marks admission, not snapshot capture, so this
            ;; await may legitimately have taken its snapshot either BEFORE or
            ;; AFTER the update landed. Both outcomes are correct, and the
            ;; contract that actually matters is the same either way: the await
            ;; returned long before its own 120 s deadline could arrive, and
            ;; every event it dispatched carries a CURRENT token. Demanding the
            ;; empty vector would be asserting one scheduling order, not the
            ;; contract -- and the socket is genuinely writable, so the
            ;; post-update snapshot has a real :write event to report.
            (c/check-pred "update acknowledged while a wait was parked woke it"
                          #(vector? (:ok %)) r)
            (c/check "no event outlived its token"
                     true
                     (every? #(poller/current-token? p (:token %))
                             (:ok r)))
            (c/check "the successor token advanced its revision"
                     1 (:jolt.net/revision token'))
            (c/check "the old token is now stale"
                     false (poller/current-token? p token))
            (c/check "the successor token is current"
                     true (poller/current-token? p token')))))))

  (with-pair!
    (fn [_client server]
      (with-poller!
        (fn [p]
          (let [token (poller/register! p server #{:read})
                f (blocked-await! p)
                _ (poller/remove! p token)
                r (woke? f)]
            (c/check "removal acknowledged while a wait was parked woke it"
                     {:ok []} r)
            (c/check "the removed token is stale"
                     false (poller/current-token? p token)))))))

  (c/section "w4: a registered socket's own close interrupts a running wait")
  (with-listener!
    (fn [l]
      (let [client (connect-client! l)
            server (net/accept l)]
        (net/finish-connect! client)
        (with-poller!
          (fn [p]
            (let [token (poller/register! p server #{:read})
                  f (blocked-await! p)
                  _ (net/close! server)
                  r (woke? f)]
              (c/check "closing a registered socket woke the parked wait"
                       {:ok []} r)
              ;; The close listener's acknowledged :remove-closed ran before the
              ;; descriptor could be closed, so no stale event can be dispatched
              ;; for a handle that may already have been recycled.
              (c/check "the closed socket's token was retired, not dispatched"
                       false (poller/current-token? p token)))))
        (net/close! client)))))

;; --- close as a completion boundary -------------------------------------------

(defn- close-suite! []
  (c/section "w4: terminal close against a blocked await")
  ;; The W3 adapter REFUSED this case, because nothing could force the parked
  ;; WSAPoll to return. It is now a completion boundary.
  (with-pair!
    (fn [_client server]
      (let [p (net/open-poller)]
        (poller/register! p server #{:read})
        (let [f (blocked-await! p)
              closed (future (poller/close! p))
              close-result (deref closed watchdog-ms timeout-token)
              r (woke? f)]
          (c/check "close returned rather than refusing" true close-result)
          (c/check "the blocked await exited" {:ok []} r)
          (c/check "close means the lifecycle reads :closed" :closed (phase p))
          (c/check "close means BOTH wake handles are retired"
                   [true true]
                   [(h/closed? (:write (:wake p)))
                    (h/closed? (:read (:wake p)))])
          (c/check "a registered socket is borrowed, so it stays open"
                   false (h/closed? server))))))

  (c/section "w4: repeated and concurrent close")
  (let [p (net/open-poller)]
    (c/check "the first close wins" true (poller/close! p))
    (c/check "a repeated close is false, not an error" false (poller/close! p))
    (c/check "close stays idempotent" false (net/close! p)))
  (let [p (net/open-poller)
        racers 8
        results (mapv (fn [_] (future (poller/close! p))) (range racers))
        observed (mapv #(deref % watchdog-ms timeout-token) results)]
    (c/check "no concurrent close timed out" false
             (boolean (some #(= timeout-token %) observed)))
    (c/check "exactly one concurrent close reported the win"
             1 (count (filter true? observed)))
    (c/check "the poller is closed once, by whoever won" :closed (phase p)))

  (c/section "w4: close against an ADMITTED wake sender")
  ;; Admission is a CAS counter shared with close, and the handle lease is taken
  ;; while admission is held. Close must not be able to retire the sender out
  ;; from under a writer that already passed admission.
  (let [p (net/open-poller)
        writer (poller/acquire-wake-write! p)
        released? (atom false)]
    (try
      (c/check-pred "a wake write was admitted" some? writer)
      (let [closing (future (poller/close! p))]
        (c/check "close cannot pass the admitted writer"
                 timeout-token (deref closing blocked-probe-ms timeout-token))
        ;; Step ordering: the terminal byte is published while sends are still
        ;; admitted, so the receiver must still be open here.
        (c/check "the receiver stays open while a writer is admitted"
                 false (h/closed? (:read (:wake p))))
        (poller/release-wake-write! p writer)
        (reset! released? true)
        (c/check "close completes once the admitted writer releases"
                 true (deref closing watchdog-ms timeout-token))
        (c/check "the sender retires before the receiver"
                 [true true]
                 [(h/closed? (:write (:wake p)))
                  (h/closed? (:read (:wake p)))]))
      (finally
        (when-not @released? (poller/release-wake-write! p writer))
        (net/close! p))))

  (c/section "w4: an exiting await must not itself retire the receiver")
  ;; The proven race this section regresses: close's own step 2 (the
  ;; acknowledged :clear mutation) publishes an ORDINARY wake that releases a
  ;; parked native wait. The awakened await's own finally then runs
  ;; exit-await!, on its own thread, concurrently with close's thread walking
  ;; steps 3-6. If exit-await! retired the receiver itself -- rather than
  ;; leaving that to the winning close!, only after close's own sender
  ;; retirement -- it could close the read end while a still-admitted wake
  ;; writer (this section's `writer`, held exactly like the admitted-sender
  ;; section above) has not yet issued its native write. Forcing that writer
  ;; to stay admitted across the whole hand-off makes any premature
  ;; retirement observable directly, instead of only as an intermittent EPIPE
  ;; on the writer's send.
  (let [p (net/open-poller)
        writer (poller/acquire-wake-write! p)
        released? (atom false)]
    (try
      (c/check-pred "a wake write was admitted before the await started"
                    some? writer)
      (let [f (blocked-await! p)
            closing (future (poller/close! p))]
        ;; Close's step 2 wake releases the parked native wait. Future
        ;; completion proves exit-await! returned; merely observing its
        ;; :awaiting? CAS would leave a preemption window before the old
        ;; implementation's erroneous finish-close! call.
        (c/check "the awaited call exited on the ordinary wake"
                 {:ok []} (woke? f))
        ;; Still true: retire-wake-writes! (close's steps 4-6) cannot pass the
        ;; admitted writer, so close has not reached finish-close! itself yet.
        (c/check "close is still blocked behind the admitted writer"
                 timeout-token (deref closing blocked-probe-ms timeout-token))
        ;; The load-bearing assertion. Because the future above completed,
        ;; exit-await! has returned; old code has already retired the receiver
        ;; at this point, while fixed code leaves it to the blocked closer.
        (c/check "the receiver stays open while the writer is still admitted"
                 false (h/closed? (:read (:wake p))))
        (poller/release-wake-write! p writer)
        (reset! released? true)
        (c/check "close completes once the held writer is released"
                 true (deref closing watchdog-ms timeout-token))
        (c/check "both wake handles are retired once close completes"
                 [true true]
                 [(h/closed? (:write (:wake p)))
                  (h/closed? (:read (:wake p)))]))
      (finally
        (when-not @released? (poller/release-wake-write! p writer))
        (net/close! p))))

  (c/section "w4: an await admission racing close")
  ;; Force the exact interleaving: close reads an unawaited lifecycle value, a
  ;; REAL await is admitted before close's CAS, and close's CAS therefore fails
  ;; and retries against the newer value. With a wake transport this must not
  ;; refuse -- it must proceed and complete the boundary, waking the await it
  ;; just lost the race to.
  (with-pair!
    (fn [_client server]
      (let [p0 (net/open-poller)
            waiter (atom nil)
            interleaved? (atom false)
            fired? (atom false)
            p (assoc p0
                     :jolt.net/before-close-cas
                     (fn [_old]
                       (when (compare-and-set! fired? false true)
                         (reset! waiter (blocked-await! p0))
                         (reset! interleaved? true))))]
        (poller/register! p server #{:read})
        (let [close-result (deref (future (poller/close! p))
                                  watchdog-ms timeout-token)]
          (c/check "the forced await admission won the race"
                   true @interleaved?)
          (c/check "close completed anyway, rather than refusing"
                   true close-result)
          (c/check "the await that won the race was still woken"
                   {:ok []} (woke? @waiter))
          (c/check "the raced close still reached :closed" :closed (phase p0))))))

  (c/section "w4: an await admitted AFTER close is rejected")
  (let [p (net/open-poller)]
    (poller/close! p)
    (c/check-throws "a post-close await is refused, not parked"
                    {:jolt.net/kind :invalid}
                    #(poller/await-ready p 10))
    (c/check-throws "a post-close wake is refused"
                    {:jolt.net/kind :invalid}
                    #(net/wake! p)))

  (c/section "w4: only one thread may await")
  (with-poller!
    (fn [p]
      (let [f (blocked-await! p)
            second-result (try {:ok (poller/await-ready p 10)}
                               (catch :default e {:ex (ex-data e)}))]
        (c/check "concurrent admission is refused, not silently shared"
                 :invalid (:jolt.net/kind (:ex second-result)))
        (net/wake! p)
        (c/check "the admitted await still completes" {:ok []} (woke? f))))))

;; --- receiver lifetime across the native wait ---------------------------------

(defn- receiver-lifetime-suite! []
  (c/section "w4: the receiver outlives the sender, across the native wait")
  ;; This one hooks the native call. It is a LIFECYCLE and OWNERSHIP gate, not
  ;; another WSAPoll ABI oracle -- the real waits everywhere else in this suite
  ;; and in the W3 gate establish the backend. The hook is the only way to
  ;; observe handle state from a point that is provably INSIDE the wait while
  ;; close runs concurrently.
  (let [entered (promise)
        release (promise)
        observed (atom nil)
        p0 (net/open-poller)
        p (assoc p0
                 :jolt.net/poll-call
                 (fn [_buf _n _wait-ms]
                   (deliver entered true)
                   (deref release watchdog-ms nil)
                   ;; Sample ownership from inside the wait, after close has
                   ;; been released to run.
                   (reset! observed
                           {:sender (h/closed? (:write (:wake p0)))
                            :receiver (h/closed? (:read (:wake p0)))})
                   {:result 0}))
        waiting (atom nil)]
    (try
      (reset! waiting (future (try {:ok (poller/await-ready p 1000)}
                                   (catch :default e {:ex (ex-data e)}))))
      (c/check "the wait was entered" true (deref entered watchdog-ms false))
      (let [closing (future (poller/close! p))]
        ;; Close is now past its terminal wake and blocked at step 7, waiting
        ;; for this wait to exit. Let the wait sample and finish.
        (c/check "close cannot complete while the wait is parked"
                 timeout-token (deref closing blocked-probe-ms timeout-token))
        (deliver release true)
        (c/check "close then completed" true
                 (deref closing watchdog-ms timeout-token)))
      (deref @waiting watchdog-ms timeout-token)
      (c/check "the receiver was STILL OWNED while the wait was inside WSAPoll"
               false (:receiver @observed))
      (c/check "the sender had already been retired at that moment"
               true (:sender @observed))
      (c/check "the receiver is retired only after the wait exits"
               true (h/closed? (:read (:wake p0))))
      (finally
        (deliver release true)
        (when-let [w @waiting] (deref w watchdog-ms timeout-token))
        (net/close! p0)))))

;; --- readiness-driven blocking accept -----------------------------------------

(defn- accept-suite! []
  (c/section "w4: blocking accept is readiness-driven on this target")
  (with-listener!
    (fn [l]
      ;; The listener is non-blocking from construction now, because `listen`
      ;; transitions it wherever a readiness runtime exists.
      (c/check "listen transitioned the listener at construction"
               true (h/nonblocking? l))
      (let [accepting (future (try {:ok (net/accept l)}
                                   (catch :default e {:ex (ex-data e)})))
            client (connect-client! l)
            r (deref accepting watchdog-ms timeout-token)]
        (try
          (c/check-pred "a blocked accept completes when a client arrives"
                        #(h/handle? (:ok %)) r)
          (c/check "the accepted socket is non-blocking"
                   true (h/nonblocking? (:ok r)))
          (finally
            (when (h/handle? (:ok r)) (net/close! (:ok r)))
            (net/close! client))))))

  (c/section "w4: blocking accept after a prior try-accept")
  (with-listener!
    (fn [l]
      (c/check "try-accept first reports would-block"
               net/would-block (net/try-accept l))
      (let [accepting (future (try {:ok (net/accept l)}
                                   (catch :default e {:ex (ex-data e)})))
            client (connect-client! l)
            r (deref accepting watchdog-ms timeout-token)]
        (try
          (c/check-pred "blocking accept still works after try-accept"
                        #(h/handle? (:ok %)) r)
          (finally
            (when (h/handle? (:ok r)) (net/close! (:ok r)))
            (net/close! client))))))

  ;; Mixed order too: a real accept, then a try-accept, then a real accept.
  (with-listener!
    (fn [l]
      (let [c1 (connect-client! l)
            s1 (net/accept l)
            _ (c/check "would-block is still a value between blocking accepts"
                       net/would-block (net/try-accept l))
            accepting (future (try {:ok (net/accept l)}
                                   (catch :default e {:ex (ex-data e)})))
            c2 (connect-client! l)
            r (deref accepting watchdog-ms timeout-token)]
        (try
          (c/check-pred "accept and try-accept interleave honestly"
                        #(h/handle? (:ok %)) r)
          (c/check-pred "the two accepted sockets are distinct handles"
                        #(not= (first %) (second %))
                        [(h/raw s1) (when (h/handle? (:ok r)) (h/raw (:ok r)))])
          (finally
            (when (h/handle? (:ok r)) (net/close! (:ok r)))
            (net/close! s1) (net/close! c1) (net/close! c2))))))

  (c/section "w4: listener close is a bounded completion boundary for accept")
  ;; No client ever arrives. Without cancellation this would park forever, so
  ;; "it returned at all" is the oracle -- and it must return the ordinary
  ;; use-after-close error rather than a synthetic one or a recycled handle.
  (let [l (net/listen (net/endpoint "127.0.0.1" 0) {:reuse-address? true})
        accepting (future (try {:ok (net/accept l)}
                               (catch :default e {:ex (ex-data e)})))]
    ;; Wait for accept to have entered its poller-backed path before closing
    ;; underneath it. The oracle is atomic state on the listener itself: accept
    ;; installs a close listener before it registers or waits, so the presence
    ;; of one is the exact fact "accept is past its entry". Polling try-accept
    ;; here instead would race the accept thread for the same listener and
    ;; perturb the very interleaving under test.
    (c/check "accept installed its terminal close listener"
             true
             (spin-until! #(seq (:listeners @(:jolt.net/state l)))))
    (net/close! l)
    (let [r (deref accepting watchdog-ms timeout-token)]
      ;; Close can land anywhere in accept's loop -- before try-accept's lease,
      ;; between it and the wait, or inside the wait -- and each lands on a
      ;; different message. Pinning one would be pinning a scheduling order.
      ;; The CONTRACT is what is asserted: accept terminated instead of
      ;; parking, it failed on ownership rather than natively (so no syscall
      ;; reached a closing descriptor), and it produced no socket.
      (c/check-pred "closing the listener released the blocked accept"
                    #(some? (:ex %)) r)
      (c/check "the release was an ownership failure, not a native one"
               :invalid (:jolt.net/kind (:ex r)))
      (c/check "and it did not return a socket from a recycled handle"
               nil (:ok r)))))

;; --- leak and stress ----------------------------------------------------------

;; GetProcessHandleCount measures the resource, not an allocator position. A
;; systematic leak therefore adds at least one count per cycle, independent of
;; how many handles that operation normally owns or how Windows chooses their
;; numeric values. Keep a visible noise budget and refuse the oracle if it ever
;; overlaps half of that minimum leak signal.
(def ^:private observed-count-noise 10)

(defn- check-no-leak! [label attempts minimum-leaks-per-cycle before after]
  (let [signed (- after before)
        leak-signal (* attempts minimum-leaks-per-cycle)
        leak-floor (quot leak-signal 2)]
    (c/check-pred
     (str label " (" attempts " cycles, before " before ", after " after
          ", signed delta " signed ", allowed absolute noise "
          observed-count-noise ", a systematic leak would add at least "
          leak-signal ")")
     #(<= (abs %) observed-count-noise) signed)
    (c/check-pred
     (str label ": the oracle still separates noise from a leak (noise budget "
          observed-count-noise ", leak floor " leak-floor ")")
     #(< observed-count-noise %) leak-floor)))

(defn- check-handle-count-nonvacuity! []
  (let [before (whc/current!)
        sockets (atom [])]
    (try
      (dotimes [_ 6]
        (swap! sockets conj
               (net/listen (net/endpoint "127.0.0.1" 0))))
      (c/check-pred
       "GetProcessHandleCount observes deliberately open sockets"
       #(<= 6 %) (- (whc/current!) before))
      (finally
        (doseq [s @sockets] (net/close! s))))))

(defn- stress-suite! []
  (c/section "w4: repeated construction and close leaks no handle")
  (check-handle-count-nonvacuity!)
  (let [attempts 60
        before (whc/current!)
        closed (atom 0)]
    (dotimes [_ attempts]
      (let [p (net/open-poller)]
        (net/wake! p)
        (when (poller/close! p) (swap! closed inc))))
    (c/check "every poller in the loop was closed by its own close"
             attempts @closed)
    ;; A poller owns two wake sockets, but the oracle must reject even if only
    ;; one side leaks per cycle.
    (check-no-leak! "poller open/close cycles leak no handles"
                    attempts 1 before (whc/current!)))

  (c/section "w4: repeated accept construction and close leaks no handle")
  ;; `accept` builds and tears down its own poller, and therefore its own wake
  ;; pair, on every call -- plus the accepted socket itself.
  (let [attempts 40
        before (whc/current!)
        accepted (atom 0)]
    (with-listener!
      (fn [l]
        (dotimes [_ attempts]
          (let [client (connect-client! l)
                server (net/accept l)]
            (when (h/handle? server) (swap! accepted inc))
            (net/close! server)
            (net/close! client)))))
    (c/check "every accept in the loop returned a socket" attempts @accepted)
    ;; Each cycle owns two wake sockets, the accepted socket, and the client
    ;; socket. Use the minimum systematic defect -- one leaked handle/cycle --
    ;; rather than allowing three of those four leaks to hide below a
    ;; complete-cycle threshold.
    (check-no-leak! "accept cycles leak no handles"
                    attempts 1 before (whc/current!))))

;; --- entry point --------------------------------------------------------------

(defn- run-suite! []
  (println "jolt-net public poller wake suite (dependency-free, task W4)")
  (println (str "target: " (jolt.net.target/current-target)))
  (println (str "readiness backend: " (:kind r/backend)))
  (println (str "wake transport: " (pr-str (r/wake-transport))))
  (println (str "terminal wake: " (pr-str wake/terminal-wake)))

  (c/section "scaffold")
  (c/monotonic-clock-facts!)

  (if-not (windows?)
    ;; This is the WINDOWS gate. The equivalent POSIX coverage is the full
    ;; :test suite's poller tests, which run the same shared state machine over
    ;; the self-pipe transport. Running this file elsewhere and counting it
    ;; would be claiming Windows evidence from a POSIX run.
    (c/skip "the W4 public poller suite"
            "not a Windows target; the POSIX poller suite covers poll(2)")
    (do
      (transport-suite!)
      (wake-suite!)
      (mutation-suite!)
      (close-suite!)
      (receiver-lifetime-suite!)
      (accept-suite!)
      (stress-suite!))))

(defn -main [& _]
  (let [result (deref (future (run-suite!)) suite-timeout-ms timeout-token)]
    (if (= timeout-token result)
      (do
        (println)
        (println (str "FAIL  W4 suite timed out after " suite-timeout-ms " ms"))
        (flush)
        (System/exit 124))
      (do
        (flush)
        (System/exit (c/summary))))))
