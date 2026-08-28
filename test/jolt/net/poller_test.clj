(ns jolt.net.poller-test
  "Real POSIX non-blocking I/O and poller lifecycle checks."
  (:require [jolt.net :as net]
            [jolt.net.check :as c]
            [jolt.net.ffi :as nffi]
            [jolt.net.handle :as h]
            [jolt.net.nonblocking :as nb]
            [jolt.net.poller :as poller]
            [jolt.net.target :as target]))

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

(defn- listener-reusing-raw!
  "Obtain a distinct owned listener with target-raw, or fail after a bounded
  number of real kernel allocations. Reuse is the premise of the regression,
  so an environment that does not exercise it is a test failure, not a skip."
  [target-raw attempts history]
  (loop [remaining attempts]
    (when (zero? remaining)
      (throw (ex-info "bounded descriptor-reuse premise was not exercised"
                      {:jolt.net/op :descriptor-reuse-regression
                       :jolt.net/kind :test-non-vacuity
                       :jolt.net/target-raw target-raw
                       :jolt.net/history @history})))
    (let [candidate (net/listen (net/endpoint "127.0.0.1" 0)
                                {:reuse-address? true})]
      (if (= target-raw (net/native-handle candidate))
        candidate
        (do
          (swap! history conj
                 {:event :reuse-miss
                  :candidate-generation (h/generation candidate)
                  :candidate-raw (net/native-handle candidate)})
          (net/close! candidate)
          (recur (dec remaining)))))))

(defn- remaining-ms [deadline]
  (let [remaining (- deadline (System/nanoTime))]
    (if (pos? remaining)
      ;; ceil(ns / 1e6): truncating here could turn a still-live sub-millisecond
      ;; deadline into an accidental zero-timeout busy loop.
      (quot (+ remaining 999999) 1000000)
      0)))

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
        (if (<= deadline (System/nanoTime))
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
      (let [flags (nffi/invoke :fcntl
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
            [0 nil])]
         (nb/set-raw! 73)))
    (c/check "the fail-closed transition verifies after setting the flag"
             [getfl setfl getfl]
             (mapv #(nth % 2) @calls)))

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
        deadline (+ (System/nanoTime) 2000000000)]
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
                      #(< % deadline) (System/nanoTime)))
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
            deadline (+ (System/nanoTime) 2000000000)
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
  (let [[client server] (connected-pair)]
    (try
      (let [dest (byte-array 6)
            src (byte-array [10 20 30 40 50])]
        (c/check "empty non-blocking read reports would-block"
                 net/would-block (net/try-read-bytes! server dest 0 6))
        (c/check "zero-length read is the only read that returns zero"
                 0 (net/try-read-bytes! server dest 0 0))
        (c/check "slice write reports its byte count"
                 3 (net/try-write-bytes! client src 1 3))
        (c/check "slice read reports its byte count"
                 3 (net/try-read-bytes! server dest 2 3))
        (c/check "read and write honor both array offsets"
                 [0 0 20 30 40 0] (vec dest))
        (net/shutdown! client :write)
        (c/check "shutdown-write is observed as the EOF value"
                 net/eof (net/try-read-bytes! server dest 0 1)))
      (finally (net/close! client) (net/close! server))))

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

  (let [listener (net/listen (net/endpoint "127.0.0.1" 0))
        lease-a (h/acquire! listener)
        lease-b (h/acquire! listener)
        lease-b-released? (atom false)]
    (try
      (h/release! lease-a)
      (c/check-throws "a lease token cannot be released twice"
                      {:jolt.net/kind :invalid
                       :jolt.net/op :release
                       :jolt.net/reason :already-released}
                      #(h/release! lease-a))
      (net/close! listener)
      (c/check "double release cannot close beneath another live lease"
               :closing (:phase @(:jolt.net/state listener)))
      (h/release! lease-b)
      (reset! lease-b-released? true)
      (c/check "the distinct final lease still owns native close"
               :closed (:phase @(:jolt.net/state listener)))
      (finally
        (when-not @lease-b-released?
          (try (h/release! lease-b) (catch :default _ nil)))
        (net/close! listener))))

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
  (let [p (net/open-poller)
        history (atom [])
        old-listener (net/listen (net/endpoint "127.0.0.1" 0)
                                 {:reuse-address? true})
        old-raw (net/native-handle old-listener)
        old-generation (h/generation old-listener)
        old-token (net/register! p old-listener #{:read})
        old-port (:jolt.net/port (net/local-endpoint old-listener))
        old-client (net/connect (net/endpoint "127.0.0.1" old-port))
        replacement (atom nil)
        replacement-client (atom nil)]
    (try
      (swap! history conj
             {:seq 1 :resource-id old-generation :event :registered
              :raw-evidence old-raw :token old-token})
      ;; Leave real accept readiness pending, then retire the complete logical
      ;; registration before permitting the kernel to recycle its descriptor.
      (net/close! old-listener)
      (swap! history conj
             {:seq 2 :resource-id old-generation :event :closed
              :raw-evidence old-raw})
      (reset! replacement (listener-reusing-raw! old-raw 64 history))
      (let [new-listener @replacement
            new-generation (h/generation new-listener)
            new-token (net/register! p new-listener #{:read})
            new-port (:jolt.net/port (net/local-endpoint new-listener))]
        (swap! history conj
               {:seq 3 :resource-id new-generation :event :registered
                :raw-evidence (net/native-handle new-listener)
                :token new-token})
        (c/check "descriptor reuse uses two distinct logical generations"
                 [old-raw true]
                 [(net/native-handle new-listener)
                  (not= old-generation new-generation)])
        (c/check "reuse history is partitioned by logical handle generation"
                 #{old-generation new-generation}
                 (set (map :resource-id
                           (filter :resource-id @history))))
        (c/check-throws "the old token cannot remove the replacement handle"
                        {:jolt.net/kind :invalid :jolt.net/op :remove}
                        #(net/remove-registration! p old-token))
        (c/check "the old close-removal message cannot remove the replacement"
                 false
                 (@#'poller/submit!
                   p {:op :remove-closed
                      :registration (:jolt.net/registration old-token)
                      :generation old-generation}))
        (reset! replacement-client
                (net/connect (net/endpoint "127.0.0.1" new-port)))
        (let [ready (net/await-ready p 1000)]
          (swap! history conj
                 {:seq 4 :resource-id new-generation :event :ready
                  :raw-evidence (net/native-handle new-listener)
                  :tokens (mapv :token ready)})
          (c/check "reused descriptor readiness belongs only to replacement"
                   [new-token]
                   (mapv :token ready))
          (c/check "stale token is absent from stable bounded history"
                   false
                   (boolean
                     (some #(and (= :ready (:event %))
                                 (some #{old-token} (:tokens %)))
                           @history)))))
      (finally
        (when @replacement-client (net/close! @replacement-client))
        (when @replacement (net/close! @replacement))
        (net/close! old-client)
        (net/close! old-listener)
        (net/close! p))))

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
              (h/closed? (:wake-write poller))
              (h/closed? (:wake-read poller))])
    (let [start (System/nanoTime)
          result (net/close! poller)
          elapsed (- (System/nanoTime) start)]
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
        eintr (get-in (net/target-descriptor) [:errno :eintr])
        p (assoc base
                 :jolt.net/poll-call
                 (fn [_ _ wait-ms]
                   (let [calls (swap! waits conj wait-ms)]
                     (if (= 1 (count calls))
                       (do
                         (Thread/sleep 60)
                         {:result -1 :code eintr})
                       (do
                         (Thread/sleep wait-ms)
                         {:result 0})))))]
    (try
      (c/check "EINTR retry preserves the await result"
               [] (net/await-ready p 120))
      (c/check-pred "EINTR retry uses the remaining absolute deadline"
                    (fn [observed]
                      (and (<= 2 (count observed))
                           (< (apply max (rest observed))
                              (first observed))))
                    @waits)
      (finally (net/close! p))))

  (let [base (net/open-poller)
        calls (atom 0)
        eintr (get-in (net/target-descriptor) [:errno :eintr])
        p (assoc base
                 :jolt.net/poll-call
                 (fn [_ _ _]
                   (swap! calls inc)
                   ;; Cross the caller's absolute deadline inside poll(2).
                   (Thread/sleep 20)
                   {:result -1 :code eintr}))]
    (try
      (c/check "deadline-expired EINTR is an empty timeout"
               [] (net/await-ready p 1))
      (c/check "deadline-expired EINTR is not retried forever"
               1 @calls)
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
               false (h/closed? (:wake-read p)))
      (poller/release-wake-write! p writer)
      (reset! released? true)
      (c/check "close completes after the admitted writer releases"
               true (deref closing 500 ::timed-out))
      (c/check "write end retires before the read end"
               [true true]
               [(h/closed? (:wake-write p)) (h/closed? (:wake-read p))])
      (finally
        (when-not @released?
          (poller/release-wake-write! p writer))
        (net/close! p)))))

(defn run! []
  (if (contains? #{:linux :darwin} (:os (target/current-target)))
    (run-posix!)
    (do
      (c/section "non-blocking I/O and poller platform gate")
      (c/check-throws "the readiness runtime fails closed off POSIX"
                      {:jolt.net/kind :unsupported-target}
                      #(net/open-poller))
      (c/skip "non-blocking I/O and poller runtime checks"
              "this slice has a POSIX runtime implementation only"))))
