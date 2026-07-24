(ns jolt.net.poller-test
  "Real POSIX non-blocking I/O and poller lifecycle checks."
  (:require [jolt.net :as net]
            [jolt.net.check :as c]
            [jolt.net.handle :as h]
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

(defn- run-posix! []
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
              (h/closed? (:wake-write poller))
              (h/closed? (:wake-read poller))])
    (let [start (jolt.host/monotonic-nanos)
          result (net/close! poller)
          elapsed (- (jolt.host/monotonic-nanos) start)]
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
  (if (contains? #{:linux :darwin} (:os (jolt.host/target)))
    (run-posix!)
    (do
      (c/section "non-blocking I/O and poller platform gate")
      (c/check-throws "the readiness runtime fails closed off POSIX"
                      {:jolt.net/kind :unsupported-target}
                      #(net/open-poller))
      (c/skip "non-blocking I/O and poller runtime checks"
              "this slice has a POSIX runtime implementation only"))))
