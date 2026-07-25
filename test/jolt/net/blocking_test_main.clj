(ns jolt.net.blocking-test-main
  "Dependency-free entry point for `bin/jnc -M:blocking-test`.

  Exists because the ordinary -M:test alias resolves the jolt-hegel Git
  dependency before any test runs, and that resolution currently hits a
  separate, out-of-scope Windows Git-command problem in the core fork. This
  main pulls in only target, address, resolver, and blocking-socket coverage
  -- no jolt-hegel, no poller, no POSIX nonblocking calls, and no SIGPIPE
  child process -- so socket-runtime work can be proved independently of that
  dependency-resolution path.

  The process-global Winsock once-only stress below is the first real Winsock
  use and runs before any other namespace's resolve/listen call, so it observes
  a genuine first use. The preceding injected controls use fresh private state
  and no native call. Later checks run against the already-memoized subsystem,
  which is exactly the steady-state path production code takes."
  (:require [jolt.net.check :as c]
            [jolt.net.ffi :as nffi]
            [jolt.net.error :as err]
            [jolt.net :as net]
            [jolt.net.target-test :as target-test]
            [jolt.net.address-test :as address-test]
            [jolt.net.resolver-test :as resolver-test]))

(defn- windows? [] (= :windows (:os (jolt.host/target))))

(def ^:private wait-timeout-ms 5000)
(def ^:private suite-timeout-ms 60000)
(def ^:private timeout-token ::timeout)

(defn- capture-call [f]
  (try
    {:value (f)}
    (catch :default e {:error e})))

(defn- injected-init-controls!
  "Exercise both failure shapes against fresh state, without touching the
  process-global Winsock subsystem. A second call is timed so the regression
  where a thrown attempt leaves an unresolved promise fails diagnostically
  instead of hanging the suite."
  []
  (c/section "winsock: deterministic initialization failure controls")
  (doseq [[label make-attempt]
          [["returned native error"
            (fn [attempts expected]
              (fn []
                (swap! attempts inc)
                {:error expected}))]
           ["thrown boundary exception"
            (fn [attempts expected]
              (fn []
                (swap! attempts inc)
                (throw expected)))]]]
    (let [state (atom nil)
          attempts (atom 0)
          expected (ex-info (str "injected " label)
                            {:jolt.net/op :wsa-startup
                             :jolt.net/test-outcome label})
          attempt (make-attempt attempts expected)
          first-result (capture-call #(nffi/ensure-once! state attempt))
          later-result (deref
                         (future
                           (capture-call #(nffi/ensure-once! state attempt)))
                         wait-timeout-ms
                         timeout-token)]
      (c/check (str label ": exactly one initialization attempt")
               1 @attempts)
      (c/check-pred (str label ": first caller observes the injected exception")
                    #(identical? expected (:error %))
                    first-result)
      (c/check-pred (str label ": later caller does not strand on an in-flight promise")
                    #(not= timeout-token %)
                    later-result)
      (when (not= timeout-token later-result)
        (c/check-pred (str label ": later caller observes the same memoized exception")
                      #(identical? expected (:error %))
                      later-result))
      (c/check-pred (str label ": shared state is terminal and retains that exception")
                    #(and (map? %)
                          (identical? expected (:error %)))
                    @state))))

(defn- atomic-error-controls!
  []
  (c/section "native error: fixed captured-pair contract")
  (c/check "scalar dispatch owns only error-independent close"
           #{:close} (set (keys nffi/call)))
  (c/check "scalar and captured dispatch are disjoint"
           true
           (empty?
             (filter #(contains? nffi/captured-call %)
                     (keys nffi/call))))
  (c/check "captured dispatch owns representative sentinel-returning calls"
           true
           (every? #(contains? nffi/captured-call %)
                   [:socket :bind :listen :accept :connect
                    :shutdown :getsockname :getpeername
                    :setsockopt :recv :send :getaddrinfo]))
  (c/check "a success ignores stale native-error state"
           7 (err/checked-captured :test neg? [7 999999]))
  (let [expected (get-in (net/target-descriptor) [:errno :econnrefused])
        data (try
               (err/checked-captured :connect neg? [-1 expected])
               (catch :default e (ex-data e)))]
    (c/check "a failure is classified from the paired code"
             :connection-refused (:jolt.net/kind data))
    (c/check "a failure preserves the exact paired code"
             expected (:jolt.net/code data))))

(defn- winsock-init-stress!
  "Concurrent first callers must perform exactly one WSAStartup and all must
  observe the same memoized success (or the same memoized failure). A
  check-then-reset atom is not a once-only protocol -- see
  jolt.net.ffi/ensure-subsystem!."
  []
  (c/section "winsock: concurrent first-use initialization")
  (if-not (windows?)
    (c/skip "concurrent WSAStartup stress test"
            "ensure-subsystem! is a no-op on POSIX; nothing to race")
    (let [n 32
          ready (java.util.concurrent.CountDownLatch. n)
          start (promise)
          futures
          (doall
            (repeatedly
              n
              #(future
                 (.countDown ready)
                 @start
                 (nffi/ensure-subsystem!))))]
      ;; Every distinct entrant has reached the common gate before any is
      ;; released into ensure-subsystem!, so this actually exercises concurrent
      ;; first use rather than merely launching futures in a loop.
      (.await ready)
      (c/check "every first-use entrant reached the start barrier"
               0 (.getCount ready))
      (deliver start true)
      (let [results
            (mapv #(deref % wait-timeout-ms timeout-token) futures)]
        (c/check-pred "every concurrent caller observed successful initialization"
                      #(every? true? %) results)
        (c/check "exactly one WSAStartup attempt was made for N concurrent first callers"
                 1 (nffi/winsock-startup-attempts))
        ;; a caller arriving strictly after the race must reuse the same
        ;; memoized outcome, not attempt a second WSAStartup
        (c/check "a later caller reuses the memoized outcome" true (nffi/ensure-subsystem!))
        (c/check "the later caller triggered no additional attempt"
                 1 (nffi/winsock-startup-attempts))))))

;; --- blocking socket coverage ------------------------------------------------
;; A bespoke, minimal suite rather than reusing jolt.net.socket-test/run!
;; wholesale: that namespace's wildcard-bind case resolves AF_UNSPEC+
;; AI_PASSIVE+null-node and binds whichever family getaddrinfo returns first.
;; On Windows that can select an IPv6 wildcard candidate, and Windows defaults
;; IPV6_V6ONLY to 1 (unlike the POSIX targets this suite already covers),
;; so an IPv4 loopback connect to that listener is refused rather than
;; accepted -- a real dual-stack-policy question, not part of the blocking
;; socket base this task proves. This suite sticks to exactly the coverage
;; task W1 asks for: explicit-family IPv4/IPv6 listen/connect/accept,
;; port-zero, local/peer agreement, refused-connect, duplicate-bind captured
;; before rollback, and idempotent close/use-after-close.
(defn- ipv6-available? []
  (try
    (let [l (net/listen (net/endpoint "::1" 0))]
      (net/close! l) true)
    (catch :default _ false)))

(defn- round-trip! [label host]
  (let [l (net/listen (net/endpoint host 0) {:reuse-address? true})
        local (net/local-endpoint l)
        port (:jolt.net/port local)]
    (try
      (c/check-pred (str label ": port 0 bind reports a real kernel port")
                    #(and (pos? %) (<= % 65535)) port)
      (c/check (str label ": listener bound the requested host") host (:jolt.net/host local))
      (let [client-f (future (net/connect (net/endpoint host port)))
            server (net/accept l)
            client (deref client-f)]
        (try
          (let [s-peer (net/peer-endpoint server)
                s-local (net/local-endpoint server)
                c-local (net/local-endpoint client)
                c-peer (net/peer-endpoint client)]
            (c/check (str label ": server's peer == client's local address")
                     [(:jolt.net/host c-local) (:jolt.net/port c-local)]
                     [(:jolt.net/host s-peer) (:jolt.net/port s-peer)])
            (c/check (str label ": server's local == client's peer address")
                     [(:jolt.net/host c-peer) (:jolt.net/port c-peer)]
                     [(:jolt.net/host s-local) (:jolt.net/port s-local)])
            (c/check (str label ": accepted socket is on the listening port")
                     port (:jolt.net/port s-local))
            (c/check (str label ": both sides agree on family")
                     (:jolt.net/family c-peer) (:jolt.net/family s-peer)))
          (finally (net/close! server) (net/close! client))))
      (finally (net/close! l)))))

(defn- blocking-socket-suite! []
  (c/section "sockets: IPv4 loopback")
  (round-trip! "ipv4" "127.0.0.1")

  (c/section "sockets: IPv6 loopback")
  (if (ipv6-available?)
    (round-trip! "ipv6" "::1")
    (c/skip "ipv6 loopback round trip" "no ::1 on this host"))

  (c/section "sockets: structured errors")
  ;; port 1 on loopback: nothing listens there and it is refused immediately.
  (let [expected-code (:econnrefused (:errno (net/target-descriptor)))
        outcome (try (net/connect (net/endpoint "127.0.0.1" 1))
                     :unexpected-success
                     (catch :default e (ex-data e)))]
    (c/check-pred "connect to a closed port throws structured data"
                  map? outcome)
    (c/check "connect to a closed port is :connection-refused"
             :connection-refused (:jolt.net/kind outcome))
    (c/check "the refusal carries the real native code"
             expected-code (:jolt.net/code outcome)))

  ;; binding a port that is already bound, WITHOUT reuse
  (let [l (net/listen (net/endpoint "127.0.0.1" 0))
        port (:jolt.net/port (net/local-endpoint l))]
    (try
      (c/check-throws "double bind is :address-in-use"
                      {:jolt.net/kind :address-in-use :jolt.net/op :bind}
                      #(net/listen (net/endpoint "127.0.0.1" port)))
      ;; THE ordering property: the reported code must be bind's failure, not
      ;; whatever the rollback close() left behind.
      (let [data (try (net/listen (net/endpoint "127.0.0.1" port))
                      (catch :default e (ex-data e)))]
        (c/check "the error names bind, not the rollback close"
                 :bind (:jolt.net/op data))
        (c/check "the captured code is EADDRINUSE (10048 on Windows), not a cleanup error"
                 (:eaddrinuse (:errno (net/target-descriptor)))
                 (:jolt.net/code data)))
      (finally (net/close! l))))

  (c/section "sockets: ownership and lifecycle")
  (let [l (net/listen (net/endpoint "127.0.0.1" 0))]
    (c/check "close! reports that it performed the close" true (net/close! l))
    (c/check "a second close! is a no-op, not a double closesocket()" false (net/close! l))
    (c/check "a third close! is still a no-op" false (net/close! l))
    (c/check "closed? reflects it" true (net/closed? l))
    (c/check-throws "using a closed socket is a clear error"
                    {:jolt.net/kind :invalid}
                    #(net/local-endpoint l)))

  ;; with-open must find and invoke the handle's :close
  (let [captured (atom nil)]
    (with-open [l (net/listen (net/endpoint "127.0.0.1" 0))]
      (reset! captured l))
    (c/check "with-open closes the handle via its :close fn" true (net/closed? @captured))))

(defn- run-suite! []
  (println "jolt-net blocking-only suite (dependency-free)")
  (println (str "target: " (jolt.host/target)))
  (println (str "errno-source: " (jolt.ffi/errno-source)))
  (println (str "monotonic-source: " (jolt.host/monotonic-source)))

  (c/section "scaffold")
  (c/check-pred "jolt.host/target resolves an os"
                #(contains? #{:linux :darwin :windows} %)
                (:os (jolt.host/target)))
  (c/check-pred "jolt.host/target reports pointer width"
                #(or (= 32 %) (= 64 %))
                (:pointer-bits (jolt.host/target)))
  (c/check-pred "fork prerequisite: jolt.ffi/errno is available"
                some? (jolt.ffi/errno-source))
  (c/check-pred "fork prerequisite: a real monotonic clock backs deadlines"
                #(= :monotonic %) (jolt.host/monotonic-source))

  ;; Must run before target/address/resolver/socket tests: those namespaces
  ;; call resolve/listen/connect, which would otherwise consume the "first
  ;; use" this stress test needs to observe.
  (injected-init-controls!)
  (atomic-error-controls!)
  (winsock-init-stress!)

  (target-test/run!)
  (address-test/run!)
  ;; The 10093 (WSANOTINITIALISED) witness is proved gone here: resolve now
  ;; runs strictly after the stress test above already initialized Winsock,
  ;; and every resolver-test call re-enters ensure-subsystem! (a fast memoized
  ;; read) before getaddrinfo, per jolt.net.resolver/resolve.
  (resolver-test/run!)
  (blocking-socket-suite!))

(defn -main [& _]
  ;; Bound the suite inside Jolt so a blocking accept/connect regression still
  ;; produces a diagnostic exit. The PowerShell runner has a longer outer
  ;; watchdog for hangs before this main is reached.
  (let [result (deref (future (run-suite!)) suite-timeout-ms timeout-token)]
    (if (= timeout-token result)
      (do
        (println)
        (println (str "FAIL  blocking-only suite timed out after "
                      suite-timeout-ms " ms"))
        (flush)
        (System/exit 124))
      (do
        (flush)
        (System/exit (c/summary))))))
