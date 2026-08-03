(ns jolt.net.poller-test
  "Real POSIX non-blocking I/O and poller lifecycle checks."
  (:require [jolt.net :as net]
            [jolt.net.check :as c]
            [jolt.net.ffi :as nffi]
            [jolt.net.handle :as h]
            [jolt.net.nonblocking :as nb]
            [jolt.net.poller :as poller]))

(defn- connected-pair []
  (let [listener (net/listen (net/endpoint "127.0.0.1" 0)
                             {:reuse-address? true})
        port (:jolt.net/port (net/local-endpoint listener))]
    (try
      (c/check "try-accept reports would-block before a client arrives"
               net/would-block (net/try-accept listener))
      (let [client (net/connect (net/endpoint "127.0.0.1" port))
            server (net/try-accept listener)]
        [client server])
      (finally (net/close! listener)))))

(defn- awaiting? [poller]
  (:awaiting? @(:lifecycle poller)))

(defn- wait-for-await! [poller]
  (loop [remaining 100]
    (cond
      (awaiting? poller) true
      (zero? remaining) false
      :else (do (Thread/sleep 2) (recur (dec remaining))))))

(defn- wait-for-write-retirement! [poller]
  (loop [remaining 100]
    (cond
      (= :retired (:phase @(:wake-admission poller))) true
      (zero? remaining) false
      :else (do (Thread/sleep 2) (recur (dec remaining))))))

(defn- remaining-ms [deadline]
  (let [remaining (- deadline (jolt.host/mono-nanos))]
    (if (pos? remaining)
      ;; ceil(ns / 1e6): truncating here could turn a still-live sub-millisecond
      ;; deadline into an accidental zero-timeout busy loop.
      (quot (+ remaining 999999) 1000000)
      0)))

(defn- read-once-by!
  "Complete one positive-length non-blocking read result, waiting only after
  would-block and retrying under the caller's absolute monotonic deadline."
  [poller socket dest offset length deadline]
  (loop []
    (let [result (net/try-read-bytes! socket dest offset length)]
      (if (= net/would-block result)
        (if (< (jolt.host/mono-nanos) deadline)
          (do
            (net/await-ready poller (remaining-ms deadline))
            (recur))
          net/would-block)
        result))))

(defn- read-exactly-by!
  "Fill one destination window through arbitrary positive short reads."
  [poller socket dest offset length deadline]
  (loop [progress 0]
    (if (= progress length)
      progress
      (let [result (read-once-by! poller socket dest (+ offset progress)
                                  (- length progress) deadline)]
        (cond
          (= net/eof result) net/eof
          (= net/would-block result) net/would-block
          (and (integer? result) (pos? result))
          (recur (+ progress result))
          :else
          (throw (ex-info "positive-length read made no progress"
                          {:result result :progress progress})))))))

(defn- write-exactly-by!
  "Drain one source window through arbitrary positive short writes."
  [socket src offset length deadline]
  (let [poller (net/open-poller)]
    (try
      (net/register! poller socket #{:write})
      (loop [progress 0]
        (if (= progress length)
          progress
          (let [result (net/try-write-bytes! socket src (+ offset progress)
                                             (- length progress))]
            (cond
              (= net/would-block result)
              (if (< (jolt.host/mono-nanos) deadline)
                (do
                  (net/await-ready poller (remaining-ms deadline))
                  (recur progress))
                net/would-block)

              (and (integer? result) (pos? result))
              (recur (+ progress result))

              :else
              (throw (ex-info "positive-length write made no progress"
                              {:result result :progress progress}))))))
      (finally (net/close! poller)))))

(defn- complete-connect-by!
  "The connector-layer composition this substrate is intended to enable:
  register once, retry against ONE absolute monotonic deadline, and resolve
  every write/error/hangup wake through SO_ERROR rather than readiness flags."
  [attempt deadline]
  (let [socket (:jolt.net/socket attempt)
        p (net/open-poller)]
    (try
      (net/register! p socket #{:write})
      (loop []
        (if (<= deadline (jolt.host/mono-nanos))
          ::deadline
          (let [ready (net/await-ready p (remaining-ms deadline))]
            (if (empty? ready)
              (recur)
              (let [status (net/finish-connect! socket)]
                (if (= net/in-progress status)
                  (recur)
                  status))))))
      (finally (net/close! p)))))

(defn- run-posix! []
  (c/section "non-blocking transition contract")
  (let [listener (net/listen (net/endpoint "127.0.0.1" 0))
        d (net/target-descriptor)]
    (try
      (let [[flags _]
            (nffi/invoke-captured :fcntl
                                  (net/native-handle listener)
                                  (get-in d [:const :f-getfl])
                                  0)]
        (c/check "F_GETFL observes O_NONBLOCK before short leases are admitted"
                 true (nb/enabled? flags)))
      (finally (net/close! listener))))

  (let [d (net/target-descriptor)
        getfl (get-in d [:const :f-getfl])
        setfl (get-in d [:const :f-setfl])
        nonblock (get-in d [:const :o-nonblock])
        calls (atom [])]
    (c/check-throws
     "apparent F_SETFL success fails closed when read-back lacks O_NONBLOCK"
     {:jolt.net/op :fcntl-setfl
      :jolt.net/kind :unsupported-target
      :jolt.net/expected-flag nonblock
      :jolt.net/observed-flags 0}
     #(with-redefs
       [nffi/invoke-captured
        (fn [op raw command arg]
          (swap! calls conj [op raw command arg])
            ;; Model the Darwin ABI failure that motivated the guard: F_SETFL
            ;; appears successful, but the third argument never takes effect.
          [0 0])]
        (nb/set-raw! 73)))
    (c/check "the fail-closed transition verifies after setting the flag"
             [getfl setfl getfl]
             (mapv #(nth % 2) @calls)))

  (c/section "poll: atomic native-error pair")
  (let [poll-once @#'poller/poll-once
        expected (get-in (net/target-descriptor) [:errno :eintr])
        invoked (atom nil)
        observed
        (with-redefs
         [nffi/invoke-captured
          (fn [op & args]
            (reset! invoked [op args])
            [-1 expected])]
          (poll-once {} nil 3 25))]
    (c/check "poll-once dispatches through the captured call table"
             [:poll [nil 3 25]] @invoked)
    (c/check "poll-once preserves the code paired with the failed result"
             {:result -1 :code expected} observed))

  (c/section "non-blocking connect decision contract")
  (let [decide @#'net/connect-initiation-status
        errno (:errno (net/target-descriptor))
        ctx {:jolt.net/endpoint (net/endpoint "127.0.0.1" 1)}]
    (c/check "an immediate native success is ::connected"
             net/connected (decide 0 nil ctx))
    (c/check "EINPROGRESS is a value, not an exception"
             net/in-progress (decide -1 (:einprogress errno) ctx))
    (c/check "EWOULDBLOCK is also an in-progress connect value"
             net/in-progress (decide -1 (:ewouldblock errno) ctx))
    (let [data (try
                 (decide -1 (:econnrefused errno) ctx)
                 (catch :default e (ex-data e)))]
      (c/check "a synchronous refusal stays a real connect exception"
               [:connect :connection-refused (:econnrefused errno)]
               [(:jolt.net/op data) (:jolt.net/kind data)
                (:jolt.net/code data)])))

  (let [advance @#'net/try-connect-candidates
        addresses [{:id 1} {:id 2} {:id 3}]
        visited (atom [])
        selected
        (advance
         addresses
         {}
         (fn [address _]
           (swap! visited conj (:id address))
           (if (= 2 (:id address))
             {:jolt.net/socket ::fake-socket
              :jolt.net/status net/in-progress
              :jolt.net/address address}
             (throw (ex-info "candidate failed"
                             {:jolt.net/op :connect
                              :jolt.net/code (:id address)})))))]
    (c/check "candidate attempts preserve resolver order"
             [1 2] @visited)
    (c/check "a selected attempt retains every untried candidate"
             [{:id 3}] (:jolt.net/remaining-addresses selected)))

  (let [advance @#'net/try-connect-candidates
        visited (atom [])
        data
        (try
          (advance
           [{:id 10} {:id 20} {:id 30}]
           {}
           (fn [address _]
             (swap! visited conj (:id address))
             (throw (ex-info "real candidate failure"
                             {:jolt.net/op :connect
                              :jolt.net/code (:id address)}))))
          (catch :default e (ex-data e)))]
    (c/check "all failed candidates are tried in resolver order"
             [10 20 30] @visited)
    (c/check "candidate exhaustion throws the last real error"
             30 (:jolt.net/code data)))

  (let [resolved (first (net/resolve (net/endpoint "127.0.0.1" 1)))]
    (c/check-throws "a malformed resolved sockaddr fails before native use"
                    {:jolt.net/kind :invalid :jolt.net/op :connect}
                    #(net/try-connect
                      (assoc resolved :jolt.net/sockaddr-len 0))))

  (c/section "non-blocking connect completion and deadline composition")
  (let [listener (net/listen (net/endpoint "127.0.0.1" 0)
                             {:reuse-address? true})
        port (:jolt.net/port (net/local-endpoint listener))
        attempt (net/try-connect (net/endpoint "127.0.0.1" port)
                                 {:no-delay? true})
        socket (:jolt.net/socket attempt)
        deadline (+ (jolt.host/mono-nanos) 2000000000)]
    (try
      (c/check-throws "completion rejects a socket with no connect provenance"
                      {:jolt.net/kind :invalid
                       :jolt.net/op :finish-connect}
                      #(net/finish-connect! listener))
      (c/check-pred "initiation returns an owned socket plus an exact status"
                    #(contains? #{net/connected net/in-progress} %)
                    (:jolt.net/status attempt))
      (c/check "the returned socket is already non-blocking"
               true (h/nonblocking? socket))
      (c/check "the selected address is retained for diagnostics"
               port (:jolt.net/port (:jolt.net/address attempt)))
      (c/check "a single-candidate initiation has no hidden candidates"
               [] (:jolt.net/remaining-addresses attempt))
      (let [status (if (= net/in-progress (:jolt.net/status attempt))
                     (complete-connect-by! attempt deadline)
                     (:jolt.net/status attempt))]
        (c/check "write readiness plus SO_ERROR completes the connection"
                 net/connected status)
        (c/check-pred "completion respected the caller's absolute deadline"
                      #(< % deadline) (jolt.host/mono-nanos)))
      (let [server (net/accept listener)]
        (try
          (c/check "completed socket reports the actual peer"
                   port (:jolt.net/port (net/peer-endpoint socket)))
          (finally (net/close! server))))
      (finally
        (net/close! socket)
        (net/close! listener))))

  (c/section "non-blocking connect refusal and retained ownership")
  ;; Existing socket coverage establishes that loopback port 1 is closed on the
  ;; CI hosts. Non-blocking initiation usually reports EINPROGRESS; SO_ERROR
  ;; must then expose ECONNREFUSED rather than treating POLLOUT as success.
  (let [result
        (try
          {:attempt (net/try-connect (net/endpoint "127.0.0.1" 1))}
          (catch :default e {:error e}))]
    (if-let [e (:error result)]
      ;; POSIX permits the refusal to be synchronous. That still exercises the
      ;; same real-code contract; the deterministic decision seam above pins
      ;; both branches independent of scheduler timing.
      (let [data (ex-data e)]
        (c/check "an immediate refusal is the real native connect error"
                 [:connect :connection-refused]
                 [(:jolt.net/op data) (:jolt.net/kind data)]))
      (let [attempt (:attempt result)
            socket (:jolt.net/socket attempt)
            deadline (+ (jolt.host/mono-nanos) 2000000000)
            data (try
                   (complete-connect-by! attempt deadline)
                   (catch :default e (ex-data e)))]
        (try
          (c/check "SO_ERROR reports refusal, not readiness success"
                   [:connect :connection-refused]
                   [(:jolt.net/op data) (:jolt.net/kind data)])
          (c/check "SO_ERROR preserves the real ECONNREFUSED code"
                   (:econnrefused (:errno (net/target-descriptor)))
                   (:jolt.net/code data))
          (c/check "finish failure does not silently take caller ownership"
                   false (net/closed? socket))
          (c/check "the caller can close the failed attempt exactly once"
                   true (net/close! socket))
          (c/check "failed-attempt close is idempotent"
                   false (net/close! socket))
          (finally (net/close! socket))))))

  (c/section "non-blocking connect rollback does not leak descriptors")
  ;; A bad option fails after socket(2) but before ownership transfer. Repeating
  ;; it catches any path that throws without rolling the raw descriptor back.
  (let [before-socket (net/listen (net/endpoint "127.0.0.1" 0))
        before (net/native-handle before-socket)]
    (net/close! before-socket)
    (dotimes [_ 50]
      (try
        (net/try-connect (net/endpoint "127.0.0.1" 1)
                         {:recv-buffer-size 0})
        (catch :default _ nil)))
    (let [after-socket (net/listen (net/endpoint "127.0.0.1" 0))
          after (net/native-handle after-socket)]
      (try
        (c/check-pred
         (str "50 failed initiations leak no descriptors (before " before
              ", after " after ")")
         #(< % 10) (abs (- after before)))
        (finally (net/close! after-socket)))))

  (c/section "non-blocking byte I/O")
  (let [[client server] (connected-pair)
        p (net/open-poller)]
    (try
      (net/register! p server #{:read})
      (let [dest (byte-array 6)
            src (byte-array [10 20 30 40 50])
            transfer-deadline
            (+ (jolt.host/mono-nanos) 2000000000)]
        (c/check "empty non-blocking read reports would-block"
                 net/would-block (net/try-read-bytes! server dest 0 6))
        (c/check "zero-length read is the only read that returns zero"
                 0 (net/try-read-bytes! server dest 0 0))
        (c/check "slice write completes through partial progress"
                 3 (write-exactly-by! client src 1 3 transfer-deadline))
        (c/check "slice read completes through partial progress"
                 3 (read-exactly-by! p server dest 2 3 transfer-deadline))
        (c/check "read and write honor both array offsets"
                 [0 0 20 30 40 0] (vec dest))
        (net/shutdown! client :write)
        ;; A successful peer send or shutdown orders that peer's stream, but
        ;; neither synchronizes receiver readiness. Darwin may still report
        ;; would-block until the bytes or FIN become readable.
        (c/check "shutdown-write is observed as the EOF value"
                 net/eof
                 (read-once-by! p server dest 0 1
                                (+ (jolt.host/mono-nanos) 2000000000))))
      (finally
        (net/close! p)
        (net/close! client)
        (net/close! server))))

  (c/section "short operation leases")
  (let [listener (net/listen (net/endpoint "127.0.0.1" 0))
        lease (h/acquire! listener)]
    (c/check "close wins ownership while an operation lease is held"
             true (net/close! listener))
    (c/check "new operations are rejected as soon as close begins"
             true (net/closed? listener))
    (c/check "native close is deferred while the lease exists"
             :closing (:phase @(:jolt.net/state listener)))
    (h/release! lease)
    (c/check "the final lease release completes native close"
             :closed (:phase @(:jolt.net/state listener))))

  (c/section "poller tokens and readiness")
  (let [[client server] (connected-pair)
        poller (net/open-poller)]
    (try
      (let [token (net/register! poller server #{:read})]
        (c/check "token carries the socket ownership generation"
                 (h/generation server) (:jolt.net/generation token))
        (c/check "zero timeout with no activity is empty"
                 [] (net/await-ready poller 0))
        (net/try-write-bytes! client (byte-array [7]) 0 1)
        (let [ready (net/await-ready poller 1000)]
          (c/check "readiness returns the current registration token"
                   token (:token (first ready)))
          (c/check-pred "read readiness is present"
                        #(contains? % :read) (:events (first ready))))
        (net/try-read-bytes! server (byte-array 1) 0 1)
        (let [successor (net/update-registration! poller token #{:write})]
          (c/check "updating advances the registration revision"
                   1 (:jolt.net/revision successor))
          (c/check-throws "the superseded token is rejected"
                          {:jolt.net/kind :invalid :jolt.net/op :remove}
                          #(net/remove-registration! poller token))
          (let [ready (net/await-ready poller 1000)]
            (c/check "write readiness carries only the successor token"
                     successor (:token (first ready)))
            (c/check-pred "write readiness is present"
                          #(contains? % :write) (:events (first ready))))
          (c/check "remove is acknowledged before returning"
                   true (net/remove-registration! poller successor))
          (c/check "removed registrations produce no readiness"
                   [] (net/await-ready poller 0))))
      (finally
        (net/close! poller)
        (net/close! client)
        (net/close! server))))

  (c/section "poller mutation wake and close safety")
  (let [[client server] (connected-pair)
        poller (net/open-poller)
        token (net/register! poller server #{:read})]
    (try
      (let [waiting (future (net/await-ready poller 1000))]
        (c/check "await entered its bounded native lease"
                 true (wait-for-await! poller))
        (c/check "remove acknowledges while native poll is blocked"
                 true (net/remove-registration! poller token))
        (c/check "the invalidated native snapshot emits no stale event"
                 [] (deref waiting 500 ::timed-out)))
      (let [token2 (net/register! poller server #{:read})
            waiting (future (net/await-ready poller 1000))]
        (c/check "second await entered before socket close"
                 true (wait-for-await! poller))
        (c/check "registered socket close wins ownership"
                 true (net/close! server))
        (c/check "socket close wakes poll and filters its old token"
                 [] (deref waiting 500 ::timed-out))
        (c/check-throws "close-listener removal makes the token stale"
                        {:jolt.net/kind :invalid :jolt.net/op :remove}
                        #(net/remove-registration! poller token2)))
      (finally
        (net/close! poller)
        (net/close! client)
        (net/close! server))))

  (let [poller (net/open-poller)
        waiting (future (net/await-ready poller 1000))]
    (c/check "poller await entered before terminal close"
             true (wait-for-await! poller))
    (c/check "poller close supplies a terminal wake"
             true (net/close! poller))
    (c/check "terminal wake releases the blocked await"
             [] (deref waiting 500 ::timed-out))
    (c/check "close return means lifecycle and both wake handles are closed"
             [:closed true true]
             [(:phase @(:lifecycle poller))
              (h/closed? (:write (:wake poller)))
              (h/closed? (:read (:wake poller)))])
    (let [start (jolt.host/mono-nanos)
          result (net/close! poller)
          elapsed (- (jolt.host/mono-nanos) start)]
      (c/check "poller close is idempotent"
               false result)
      (c/check-pred "repeated close is nonblocking"
                    #(< % 100000000) elapsed)))

  (let [poller (net/open-poller)
        waiting (future (net/await-ready poller 2500))]
    (try
      (c/check "long await entered before the safety interval"
               true (wait-for-await! poller))
      (c/check "finite native safety polls do not shorten caller timeout"
               ::still-waiting (deref waiting 1200 ::still-waiting))
      (net/wake! poller)
      (c/check "explicit wake ends the continued await"
               [] (deref waiting 500 ::timed-out))
      (finally (net/close! poller))))

  (let [base (net/open-poller)
        waits (atom [])
        now (atom 0)
        eintr (get-in (net/target-descriptor) [:errno :eintr])
        p (assoc base
                 :jolt.net/monotonic-nanos #(deref now)
                 :jolt.net/poll-call
                 (fn [_ _ wait-ms]
                   (let [calls (swap! waits conj wait-ms)]
                     (if (= 1 (count calls))
                       (do
                         (reset! now 60000000)
                         {:result -1 :code eintr})
                       (do
                         (reset! now 120000000)
                         {:result 0})))))]
    (try
      (c/check "EINTR retry preserves the await result"
               [] (net/await-ready p 120))
      (c/check-pred "EINTR retry uses the remaining absolute deadline"
                    (fn [observed]
                      (= [120 60] observed))
                    @waits)
      (finally (net/close! p))))

  (let [base (net/open-poller)
        calls (atom 0)
        now (atom 0)
        eintr (get-in (net/target-descriptor) [:errno :eintr])
        p (assoc base
                 :jolt.net/monotonic-nanos #(deref now)
                 :jolt.net/poll-call
                 (fn [_ _ _]
                   (swap! calls inc)
                   ;; Cross the caller's absolute deadline inside poll(2),
                   ;; without depending on scheduler timing.
                   (reset! now 2000000)
                   {:result -1 :code eintr}))]
    (try
      (c/check "deadline-expired EINTR is an empty timeout"
               [] (net/await-ready p 1))
      (c/check "deadline-expired EINTR is not retried forever"
               1 @calls)
      (finally (net/close! p))))

  (c/section "deadline clock discriminator")
  ;; The no-seam fallback must read the CURRENT host clock symbol
  ;; (jolt.host/mono-nanos), not the removed fork-only jolt.host/monotonic-nanos.
  ;; Rebinding the current symbol and driving a deadline through the fallback
  ;; proves which symbol the executable calls: a stale reference would ignore
  ;; the rebinding and read the real clock, so the controlled EINTR retry would
  ;; not observe the remaining-time window below.
  (let [base (net/open-poller)
        waits (atom [])
        now (atom 0)
        eintr (get-in (net/target-descriptor) [:errno :eintr])
        p (assoc base
                 :jolt.net/monotonic-nanos nil  ; force the host fallback
                 :jolt.net/poll-call
                 (fn [_ _ wait-ms]
                   (let [calls (swap! waits conj wait-ms)]
                     (if (= 1 (count calls))
                       (do
                         (reset! now 60000000)
                         {:result -1 :code eintr})
                       (do
                         (reset! now 120000000)
                         {:result 0})))))]
    (try
      (with-redefs [jolt.host/mono-nanos (fn [] @now)]
        (c/check "the deadline fallback reads the current host clock symbol"
                 [] (net/await-ready p 120))
        (c/check-pred "the fallback clock drives the remaining-time window"
                      (fn [observed]
                        (= [120 60] observed))
                      @waits))
      (finally (net/close! p))))

  (let [base (net/open-poller)
        calls (atom 0)
        errno (:errno (net/target-descriptor))
        p (assoc base
                 :jolt.net/wake-write-call
                 (fn [_ _ _]
                   (if (= 1 (swap! calls inc))
                     {:result -1 :code (:eintr errno)}
                     {:result -1 :code (:eagain errno)})))]
    (try
      (net/wake! p)
      (c/check "wake write retries EINTR before accepting EAGAIN"
               2 @calls)
      (finally (net/close! p))))

  (let [base (net/open-poller)
        calls (atom 0)
        errno (:errno (net/target-descriptor))
        p (assoc base
                 :jolt.net/wake-read-call
                 (fn [_ _ _]
                   (if (= 1 (swap! calls inc))
                     {:result -1 :code (:eintr errno)}
                     {:result -1 :code (:eagain errno)})))]
    (try
      ;; Force the pre-snapshot drain path without putting a real byte in the
      ;; pipe; the hook supplies EINTR followed by the non-blocking empty state.
      (reset! (:wake-pending p) true)
      (c/check "wake read retries EINTR before accepting EAGAIN"
               [] (net/await-ready p 0))
      (c/check "wake read made exactly the retry and terminal call"
               2 @calls)
      (finally (net/close! p))))

  (let [poller (net/open-poller)
        all-woke? (atom true)]
    (try
      ;; Seed the old coalesced byte, then race more wake epochs against the
      ;; pre-snapshot drain/reset window. Without the epoch handshake, a producer
      ;; can observe the old true gate after its byte was drained and write
      ;; nothing, allowing the native poll to park.
      (dotimes [_ 100]
        (net/wake! poller)
        (let [waiting (future (net/await-ready poller 500))]
          (when-not (wait-for-await! poller)
            (reset! all-woke? false))
          ;; Exactly one epoch after await admission: repeated wakes would mask
          ;; the enter/drain window by eventually writing after the reset.
          (net/wake! poller)
          (when (= ::timed-out (deref waiting 200 ::timed-out))
            (reset! all-woke? false))))
      (c/check "one admitted wake survives the enter/drain window"
               true @all-woke?)
      (finally (net/close! poller))))

  (let [p (net/open-poller)
        writer (poller/acquire-wake-write! p)
        released? (atom false)
        closing (future (net/close! p))]
    (try
      (c/check "close retires wake admission while an admitted writer is held"
               true (wait-for-write-retirement! p))
      (c/check "close cannot pass an admitted wake writer"
               ::still-closing (deref closing 20 ::still-closing))
      (c/check "pipe read end stays open until admitted wake writers drain"
               false (h/closed? (:read (:wake p))))
      (poller/release-wake-write! p writer)
      (reset! released? true)
      (c/check "close completes after the admitted writer releases"
               true (deref closing 500 ::timed-out))
      (c/check "write end retires before the read end"
               [true true]
               [(h/closed? (:write (:wake p))) (h/closed? (:read (:wake p)))])
      (finally
        (when-not @released?
          (poller/release-wake-write! p writer))
        (net/close! p))))

  ;; Force the terminal-wake/receiver-retirement race that previously escaped
  ;; as EPIPE on Linux.  The held writer keeps the winning close! behind its
  ;; writer-drain boundary, while close's ordinary clear wake lets the active
  ;; await return.  Once that future has returned, exit-await! has completed;
  ;; the read end must nevertheless remain owned until the held writer drains
  ;; and the winning close performs the sole finalization.
  (let [p (net/open-poller)
        writer (poller/acquire-wake-write! p)
        released? (atom false)]
    (try
      (c/check-pred "a wake writer is admitted before the terminal await"
                    some? writer)
      (let [waiting (future (net/await-ready p 2500))]
        (c/check "terminal-race await entered before close"
                 true (wait-for-await! p))
        (let [closing (future (net/close! p))]
          (c/check "the ordinary close wake releases the await"
                   [] (deref waiting 500 ::timed-out))
          (c/check "close remains behind the admitted writer"
                   ::still-closing (deref closing 20 ::still-closing))
          (c/check "an exited await did not retire the pipe receiver"
                   false (h/closed? (:read (:wake p))))
          (poller/release-wake-write! p writer)
          (reset! released? true)
          (c/check "terminal close completes after the writer drains"
                   true (deref closing 500 ::timed-out))
          (c/check "terminal close retires both pipe handles"
                   [true true]
                   [(h/closed? (:write (:wake p)))
                    (h/closed? (:read (:wake p)))])))
      (finally
        (when-not @released?
          (poller/release-wake-write! p writer))
        (net/close! p))))

  (c/section "poller terminal-wake invariant failures")
  ;; Corrupt each internal ownership premise deliberately. Close must still
  ;; finish teardown, but it must not report success after omitting the
  ;; terminal publication that is Windows' only cancellation guarantee.
  (let [p (net/open-poller)]
    (try
      (c/check "the corruption control prematurely retires the sender"
               true (h/close! (:write (:wake p))))
      (c/check-throws
       "close reports an unexpectedly retired sender"
       {:jolt.net/kind :invalid :jolt.net/op :use-after-close}
       #(net/close! p))
      (c/check "sender failure is deferred until teardown completes"
               [:closed true true]
               [(:phase @(:lifecycle p))
                (h/closed? (:write (:wake p)))
                (h/closed? (:read (:wake p)))])
      (finally (net/close! p))))

  (let [p (net/open-poller)]
    (try
      (reset! (:wake-admission p) {:phase :retired :writers 0})
      (c/check-throws
       "close rejects terminal publication after premature admission retirement"
       {:jolt.net/kind :invalid
        :jolt.net/op :wake
        :jolt.net/invariant :terminal-wake-before-admission-retirement}
       #(net/close! p))
      (c/check "admission failure is deferred until teardown completes"
               [:closed true true]
               [(:phase @(:lifecycle p))
                (h/closed? (:write (:wake p)))
                (h/closed? (:read (:wake p)))])
      (finally (net/close! p)))))

(defn run! []
  ;; Windows joined the readiness runtime in task W4. The gate below must name
  ;; the CURRENT set: continuing to assert that Windows fails closed here would
  ;; assert a boundary that no longer exists.
  (if (contains? #{:linux :darwin :windows} (:os (jolt.host/target)))
    (run-posix!)
    (do
      (c/section "non-blocking I/O and poller platform gate")
      (c/check-throws "the readiness runtime fails closed off supported targets"
                      {:jolt.net/kind :unsupported-target}
                      #(net/open-poller))
      (c/skip "non-blocking I/O and poller runtime checks"
              "this target has no readiness runtime"))))
