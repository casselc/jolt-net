(ns jolt.net.nonblocking-test-main
  "Dependency-free entry point for `bin/jnc -M:nonblocking-test` -- task W2.

  Like -M:blocking-test, this alias resolves no Git dependency before running,
  so Windows socket work stays independent of the separate jolt-hegel/Git
  resolution problem in the core fork. It also loads no poller namespace test:
  Windows has no readiness backend until WSAPoll in task W3, and a suite that
  quietly required one would be proving POSIX behavior on a Windows runner.

  Two coordination rules make this honest on a platform with no poller.

  1. Where a real synchronization point exists, it is used. A server's blocking
     accept returns only after the TCP handshake completed, so `finish-connect!`
     is called ONCE against a genuinely completed connection instead of being
     polled toward one.
  2. Where no such point exists -- waiting for a FIN, or for an RST to reach
     SO_ERROR -- the suite waits for a TERMINAL VALUE inside a bounded budget.
     The oracle is the value, never elapsed time; the 1 ms pause is backoff, and
     exhausting the budget yields ::timeout so the assertion fails loudly rather
     than passing quietly on a slow host."
  (:require [jolt.net.check :as c]
            [jolt.net.error :as err]
            [jolt.net.ffi :as nffi]
            [jolt.net.handle :as h]
            [jolt.net.nonblocking :as nb]
            [jolt.net.target :as t]
            [jolt.net.windows-handle-count :as whc]
            [jolt.net :as net]))

(defn- windows? [] (= :windows (:os (jolt.host/target))))

(def ^:private wait-budget-ms 5000)
(def ^:private suite-timeout-ms 60000)
(def ^:private timeout-token ::timeout)

(defn- deadline-ns [ms] (+ (jolt.host/monotonic-nanos) (* ms 1000000)))
(defn- expired? [deadline] (< deadline (jolt.host/monotonic-nanos)))

;; --- probed transition facts ------------------------------------------------
(defn- transition-facts! []
  (c/section "nonblocking: transition mechanism and probed widths")
  (let [d (net/target-descriptor)]
    (if-not (windows?)
      (do
        (c/check "POSIX proves the transition by reading O_NONBLOCK back"
                 :observed-flag (nb/postcondition-kind))
        (c/check "POSIX records no FIONBIO command" nil (t/const d :fionbio))
        (c/check "POSIX records no ioctlsocket argument width"
                 nil (:ioctl-arg-bytes d)))
      (do
        ;; Winsock has no portable getter for a socket's blocking mode, so the
        ;; successful call under the probed ABI and OS contract is the only
        ;; in-process boundary. would-block-suite! supplies later cross-boundary
        ;; conformance evidence; it does not gate each production handle mark.
        (c/check "Winsock has no getter, so call status is the mark boundary"
                 :call-status (nb/postcondition-kind))
        (c/check "ioctlsocket's long command word is 32 bits, not pointer-width"
                 4 (:ioctl-cmd-bytes d))
        (c/check "ioctlsocket's u_long argument is 32 bits, not pointer-width"
                 4 (:ioctl-arg-bytes d))
        (c/check "FIONBIO is the probed signed-long bit pattern"
                 -2147195266 (t/const d :fionbio))
        ;; The same bits read as unsigned: _IOW('f', 126, u_long) == 0x8004667E.
        ;; This is the assertion that catches a sign-convention mistake, which
        ;; would otherwise reach ioctlsocket as a different command entirely.
        (c/check "FIONBIO's unsigned 32-bit value is 0x8004667E"
                 2147772030 (bit-and (t/const d :fionbio) 0xFFFFFFFF))))))

(defn- captured-dispatch-facts! []
  (c/section "nonblocking: every consumed error arrives as a captured pair")
  (c/check "scalar dispatch still owns only error-independent close"
           #{:close} (set (keys nffi/call)))
  (c/check "scalar and captured dispatch stay disjoint"
           true
           (empty? (filter #(contains? nffi/captured-call %) (keys nffi/call))))
  (c/check "every W2 non-blocking call is on the captured surface"
           true
           (every? #(contains? nffi/captured-call %)
                   [:try-accept :try-connect :try-recv :try-send :getsockopt]))
  (if (windows?)
    (c/check "Windows routes its transition through captured ioctlsocket"
             true (contains? nffi/captured-call :ioctlsocket))
    ;; Fail-closed: POSIX must not have a plausible-looking ioctlsocket to call.
    (c/check "POSIX has no ioctlsocket binding, so a mis-selected transition fails closed"
             false (contains? nffi/captured-call :ioctlsocket)))
  (c/check "a success ignores stale native-error state"
           7 (err/checked-captured :test neg? [7 999999])))

;; --- bounded coordination helpers -------------------------------------------
(defn- write-all!
  "Send src[off,off+len) completely, treating ::would-block as 'not yet'."
  [sock src off len]
  (let [deadline (deadline-ns wait-budget-ms)]
    (loop [sent 0]
      (if (= sent len)
        sent
        (let [r (net/try-write-bytes! sock src (+ off sent) (- len sent))]
          (if (net/would-block? r)
            (if (expired? deadline)
              timeout-token
              (do (Thread/sleep 1) (recur sent)))
            (recur (+ sent r))))))))

(defn- read-exactly!
  "Fill dest[off,off+len) completely. An early EOF is reported distinctly so it
  can never be mistaken for a short read."
  [sock dest off len]
  (let [deadline (deadline-ns wait-budget-ms)]
    (loop [got 0]
      (if (= got len)
        got
        (let [r (net/try-read-bytes! sock dest (+ off got) (- len got))]
          (cond
            (net/eof? r) ::eof-before-length
            (net/would-block? r) (if (expired? deadline)
                                   timeout-token
                                   (do (Thread/sleep 1) (recur got)))
            :else (recur (+ got r))))))))

(defn- await-eof!
  "Wait for the peer's FIN to become the EOF value. Any actual byte is returned
  as-is so an unexpected payload cannot be reported as EOF."
  [sock]
  (let [deadline (deadline-ns wait-budget-ms)
        buf (byte-array 1)]
    (loop []
      (let [r (net/try-read-bytes! sock buf 0 1)]
        (cond
          (net/eof? r) net/eof
          (net/would-block? r) (if (expired? deadline)
                                 timeout-token
                                 (do (Thread/sleep 1) (recur)))
          :else r)))))

(defn- await-completion-failure!
  "Re-read SO_ERROR until completion FAILS, returning its ex-data.

  ::connected is deliberately not terminal here: the caller uses this only
  where nothing is listening, so a zero pending error means the RST has not
  landed yet, not that a connection exists. Reading SO_ERROR clears it on
  Windows, which is safe because the first non-zero read is the last one."
  [socket]
  (let [deadline (deadline-ns wait-budget-ms)]
    (loop []
      (let [r (try {:value (net/finish-connect! socket)}
                   (catch :default e {:data (ex-data e)}))]
        (cond
          (:data r) (:data r)
          (expired? deadline) timeout-token
          :else (do (Thread/sleep 1) (recur)))))))

(defn- connected-pair!
  "A client/server pair whose CLIENT came through try-connect.

  The blocking accept is the synchronization point -- it returns only after the
  handshake completed -- so no sleep stands in for readiness."
  [listener]
  (let [port (:jolt.net/port (net/local-endpoint listener))
        attempt (net/try-connect (net/endpoint "127.0.0.1" port))
        client (:jolt.net/socket attempt)
        server (net/accept listener)]
    {:client client :server server :status (:jolt.net/status attempt)}))

(defn- with-listener! [f]
  (let [l (net/listen (net/endpoint "127.0.0.1" 0) {:reuse-address? true})]
    (try (f l) (finally (net/close! l)))))

(defn- with-pair! [f]
  (with-listener!
    (fn [l]
      (let [{:keys [client server status]} (connected-pair! l)]
        (try (f client server status)
             (finally (net/close! server) (net/close! client)))))))

;; --- behavioral conformance --------------------------------------------------
(defn- would-block-suite! []
  (c/section "nonblocking: would-block is a value, before any readiness")
  ;; Winsock exposes no getter. These representative sockets exercise the
  ;; documented FIONBIO contract across the FFI boundary: accept/recv return a
  ;; value rather than parking. Production has already marked each handle after
  ;; the successful call, so this is conformance evidence, not an admission
  ;; guard or a per-handle proof.
  (with-listener!
    (fn [l]
      (c/check "accept before any client is ::would-block, not a blocked call"
               net/would-block (net/try-accept l))
      (c/check "try-accept marked the listener non-blocking"
               true (h/nonblocking? l))
      (c/check-pred "would-block is the tagged value, not -1 or an exception"
                    net/would-block? (net/try-accept l))
      ;; W2 asserted a Windows-only REFUSAL here: blocking accept after a
      ;; listener transition threw, because native blocking accept could not be
      ;; interrupted and mixing the two modes was unsound. Task W4 replaced
      ;; Windows blocking accept with the readiness-driven short-lease path, so
      ;; the mode transition is no longer a hazard and mixing is sound on every
      ;; supported target. This is that assertion's in-place successor -- the
      ;; behavior it describes changed, so it now checks the correct outcome
      ;; rather than a refusal that would today be a bug.
      (when (windows?)
        (let [port (:jolt.net/port (net/local-endpoint l))
              attempt (net/try-connect (net/endpoint "127.0.0.1" port))
              client (:jolt.net/socket attempt)
              accepted (net/accept l)]
          (try
            (c/check-pred
             "blocking accept succeeds after a Windows listener transition"
             h/handle? accepted)
            (finally (net/close! accepted) (net/close! client)))))))
  (with-pair!
    (fn [client server _]
      (net/finish-connect! client)
      (c/check "an accepted socket is non-blocking before its first read"
               net/would-block (net/try-read-bytes! server (byte-array 4) 0 4))
      (c/check "the accepted socket is marked non-blocking"
               true (h/nonblocking? server)))))

;; --- connect initiation and completion --------------------------------------
(defn- connect-suite! []
  (c/section "nonblocking: connect initiation and SO_ERROR completion")
  (c/check "connected and in-progress are distinct values"
           false (= net/connected net/in-progress))
  (with-pair!
    (fn [client server status]
      (c/check-pred "initiation reports exactly one of connected / in-progress"
                    #(or (net/connected? %) (net/in-progress? %)) status)
      (c/check-pred "the two initiation predicates cannot both hold"
                    #(not (and (net/connected? %) (net/in-progress? %))) status)
      ;; accept has already returned, so the handshake is complete and SO_ERROR
      ;; is consulted exactly once rather than polled toward an answer.
      (c/check "SO_ERROR completes a real loopback connect"
               net/connected (net/finish-connect! client))
      (c/check "completion neither closed nor transferred the socket"
               false (net/closed? client))
      (c/check-pred "the completed client has a real peer endpoint"
                    some? (net/peer-endpoint client))
      (c/check "the server sees the client as its peer"
               (let [e (net/local-endpoint client)]
                 [(:jolt.net/host e) (:jolt.net/port e)])
               (let [e (net/peer-endpoint server)]
                 [(:jolt.net/host e) (:jolt.net/port e)])))))

(defn- refused-completion-suite! []
  (c/section "nonblocking: refused completion keeps its code and its owner")
  ;; Bind a port and release it, so nothing is listening on a port that was
  ;; certainly available a moment ago.
  (let [probe (net/listen (net/endpoint "127.0.0.1" 0))
        port (:jolt.net/port (net/local-endpoint probe))
        _ (net/close! probe)
        expected (:econnrefused (:errno (net/target-descriptor)))
        outcome (try {:attempt (net/try-connect (net/endpoint "127.0.0.1" port))}
                     (catch :default e {:error e}))]
    (if-let [e (:error outcome)]
      ;; Refused synchronously at initiation: rollback already closed the
      ;; never-owned socket, and the captured code must still be connect's.
      (let [data (ex-data e)]
        (c/check "a synchronously refused initiation is :connection-refused"
                 :connection-refused (:jolt.net/kind data))
        (c/check "a synchronously refused initiation keeps its exact code"
                 expected (:jolt.net/code data)))
      (let [socket (:jolt.net/socket (:attempt outcome))
            data (await-completion-failure! socket)]
        (try
          (c/check-pred "the refused completion threw instead of reporting connected"
                        map? data)
          (when (map? data)
            (c/check "completion failure names connect" :connect (:jolt.net/op data))
            (c/check "completion failure is :connection-refused"
                     :connection-refused (:jolt.net/kind data))
            (c/check "SO_ERROR preserves the exact refusal code"
                     expected (:jolt.net/code data)))
          (c/check "a failed completion does not take caller ownership"
                   false (net/closed? socket))
          (c/check "the caller closes the failed attempt exactly once"
                   true (net/close! socket))
          (c/check "the second close is a no-op, not a second closesocket()"
                   false (net/close! socket))
          (finally (net/close! socket)))))))

;; --- byte I/O ---------------------------------------------------------------
(defn- sliced-io-suite! []
  (c/section "nonblocking: sliced byte I/O preserves offsets and counts")
  (with-pair!
    (fn [client server _]
      (net/finish-connect! client)
      ;; Both buffers are poisoned with a distinctive filler so an off-by-one
      ;; offset shows up as a wrong byte rather than an incidental zero.
      (let [src (byte-array (concat (repeat 3 0x5A)
                                    [65 66 67 68 69]
                                    (repeat 8 0x5A)))
            dest (byte-array (repeat 16 0x77))]
        (c/check "a zero-length read returns 0"
                 0 (net/try-read-bytes! server dest 0 0))
        (c/check-pred "a zero-length read is NOT the EOF value"
                      #(not (net/eof? %)) (net/try-read-bytes! server dest 0 0))
        (c/check "a zero-length write returns 0"
                 0 (net/try-write-bytes! client src 0 0))
        (c/check "the sender wrote exactly the requested slice length"
                 5 (write-all! client src 3 5))
        (c/check "the receiver filled exactly the requested slice"
                 5 (read-exactly! server dest 7 5))
        (c/check "payload landed at the destination offset, and nowhere else"
                 (vec (concat (repeat 7 0x77)
                              [65 66 67 68 69]
                              (repeat 4 0x77)))
                 (mapv #(bit-and % 0xFF) (vec dest)))
        (c/check-throws "an out-of-bounds read slice is rejected before any call"
                        {:jolt.net/kind :invalid}
                        #(net/try-read-bytes! server dest 12 8))
        (c/check-throws "an out-of-bounds write slice is rejected before any call"
                        {:jolt.net/kind :invalid}
                        #(net/try-write-bytes! client src 12 8))))))

(defn- half-close-suite! []
  (c/section "nonblocking: EOF and half-close")
  (with-pair!
    (fn [client server _]
      (net/finish-connect! client)
      (c/check "a read before any data is ::would-block"
               net/would-block (net/try-read-bytes! server (byte-array 4) 0 4))
      (net/shutdown! client :write)
      ;; No readiness backend on Windows in W2, so wait for the FIN to become a
      ;; terminal VALUE within a bounded budget rather than assuming it landed.
      (c/check "peer half-close is observed as the EOF value"
               net/eof (await-eof! server))
      ;; shutdown(:write) closes one direction only.
      (c/check "the half-closed peer can still be sent to"
               5 (write-all! server (byte-array [1 2 3 4 5]) 0 5))
      (let [back (byte-array 5)]
        (c/check "the half-closed peer can still receive"
                 5 (read-exactly! client back 0 5))
        (c/check "the reverse direction delivered the exact bytes"
                 [1 2 3 4 5] (vec back))))))

;; --- ownership and capture ordering -----------------------------------------
(defn- ownership-suite! []
  (c/section "nonblocking: ownership, idempotent close, and use-after-close")
  (with-listener!
    (fn [l]
      (let [{:keys [client server]} (connected-pair! l)]
        (net/finish-connect! client)
        (net/close! server)
        (c/check "close! reports that it performed the close" true (net/close! client))
        (c/check "a second close! is a no-op, not a double closesocket()"
                 false (net/close! client))
        (c/check "closed? reflects it" true (net/closed? client))
        (c/check-throws "a non-blocking read after close is a clear error"
                        {:jolt.net/kind :invalid :jolt.net/op :use-after-close}
                        #(net/try-read-bytes! client (byte-array 1) 0 1))
        (c/check-throws "a non-blocking write after close is a clear error"
                        {:jolt.net/kind :invalid :jolt.net/op :use-after-close}
                        #(net/try-write-bytes! client (byte-array [1]) 0 1)))))
  ;; with-open must still find and invoke the handle's :close
  (let [captured (atom nil)]
    (with-open [l (net/listen (net/endpoint "127.0.0.1" 0))]
      (reset! captured l))
    (c/check "with-open closes the handle via its :close fn"
             true (net/closed? @captured))))

(defn- capture-before-cleanup! []
  (c/section "nonblocking: errors are captured before rollback cleanup")
  (let [l (net/listen (net/endpoint "127.0.0.1" 0))
        port (:jolt.net/port (net/local-endpoint l))]
    (try
      (let [data (try (net/listen (net/endpoint "127.0.0.1" port))
                      (catch :default e (ex-data e)))]
        (c/check "the error names bind, not the rollback close"
                 :bind (:jolt.net/op data))
        (c/check "the captured code is the real EADDRINUSE, not a cleanup error"
                 (:eaddrinuse (:errno (net/target-descriptor)))
                 (:jolt.net/code data)))
      (finally (net/close! l))))

  ;; A rejected option fails AFTER socket() but BEFORE ownership transfers.
  ;; Repeating it catches any initiation path that throws without rolling the
  ;; raw descriptor back.
  (let [attempts 50
        before (if (windows?)
                 (whc/current!)
                 (let [s (net/listen (net/endpoint "127.0.0.1" 0))
                       fd (net/native-handle s)]
                   (net/close! s)
                   fd))
        threw (atom 0)]
    (dotimes [_ attempts]
      (try
        (net/try-connect (net/endpoint "127.0.0.1" 1) {:recv-buffer-size 0})
        (catch :default _ (swap! threw inc))))
    ;; Deterministic on every platform, and a precondition for the numeric
    ;; check below: an attempt that did NOT throw never took the rollback path,
    ;; so the handle comparison would be measuring nothing.
    (c/check "every rejected initiation failed before ownership transferred"
             attempts @threw)
    (if (windows?)
      (let [after (whc/current!)
            signed-delta (- after before)
            leak-signal attempts
            leak-floor (quot leak-signal 2)
            noise-budget 10
            calibration-before (whc/current!)
            sockets (atom [])]
        ;; A guard on the measurement itself: prove that this runtime's process
        ;; count observes SOCKET handles. Without it, a constant or wrong-process
        ;; counter could make every leak assertion pass.
        (try
          (dotimes [_ 6]
            (swap! sockets conj
                   (net/listen (net/endpoint "127.0.0.1" 0))))
          (c/check-pred
           "GetProcessHandleCount observes deliberately open sockets"
           #(<= 6 %) (- (whc/current!) calibration-before))
          (finally
            (doseq [s @sockets] (net/close! s))))
        (c/check-pred
         (str attempts " failed initiations leak no handles (before "
              before ", after " after ", signed delta " signed-delta
              ", allowed absolute noise " noise-budget
              ", a complete leak would add " leak-signal ")")
         #(<= (abs %) noise-budget) signed-delta)
        (c/check-pred
         (str "the W2 handle-count oracle separates its noise budget "
              noise-budget " from leak floor " leak-floor)
         #(< noise-budget %) leak-floor))
      ;; POSIX allocates the lowest free fd, so a leak shows up immediately and
      ;; the original tight allocator-position check remains the stronger test.
      (let [after-socket (net/listen (net/endpoint "127.0.0.1" 0))
            after (net/native-handle after-socket)
            delta (abs (- after before))]
        (try
          (c/check-pred
           (str attempts " failed initiations leak no descriptors (before "
                before ", after " after ", delta " delta ", allowance 10)")
           #(< % 10) delta)
          (finally (net/close! after-socket)))))))

;; --- entry point -------------------------------------------------------------
(defn- run-suite! []
  (println "jolt-net non-blocking suite (dependency-free, no poller)")
  (println (str "target: " (jolt.host/target)))
  (println (str "errno-source: " (jolt.ffi/errno-source)))
  (println (str "transition postcondition: " (nb/postcondition-kind)))

  (c/section "scaffold")
  (c/check-pred "jolt.host/target resolves an os"
                #(contains? #{:linux :darwin :windows} %)
                (:os (jolt.host/target)))
  (c/check-pred "fork prerequisite: jolt.ffi/errno is available"
                some? (jolt.ffi/errno-source))
  (c/check-pred "fork prerequisite: a real monotonic clock bounds these waits"
                #(= :monotonic %) (jolt.host/monotonic-source))

  (transition-facts!)
  (captured-dispatch-facts!)
  (would-block-suite!)
  (connect-suite!)
  (refused-completion-suite!)
  (sliced-io-suite!)
  (half-close-suite!)
  (ownership-suite!)
  (capture-before-cleanup!))

(defn -main [& _]
  ;; Bound the suite inside Jolt so a regression that actually blocks still
  ;; produces a diagnostic exit code. The PowerShell runner has a longer outer
  ;; watchdog for hangs occurring before this main is reached.
  (let [result (deref (future (run-suite!)) suite-timeout-ms timeout-token)]
    (if (= timeout-token result)
      (do
        (println)
        (println (str "FAIL  non-blocking suite timed out after "
                      suite-timeout-ms " ms"))
        (flush)
        (System/exit 124))
      (do
        (flush)
        (System/exit (c/summary))))))
