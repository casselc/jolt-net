(ns jolt.net.wsapoll-test-main
  "Dependency-free entry point for `bin/jnc -M:wsapoll-test` -- task W3.

  Like the W1 and W2 mains, this alias resolves no Git dependency before running.
  It exercises the REAL Windows readiness backend: actual WSAPoll calls over
  actual Winsock handles.

  What this suite does and does not claim
  ---------------------------------------

  W3 is the readiness slice. There is still no owner-independent Windows wake
  transport, so the PUBLIC jolt.net poller remains fail-closed on Windows and
  this suite drives `jolt.net.poller/open-readiness-adapter` -- the same
  registration and token state machine, constructed without a wake. Explicit
  `wake!`, cancellation of a blocked await, and terminal close against a blocked
  await are W4 obligations and are asserted here only as REFUSALS.

  No sleep stands in for readiness anywhere below. Where this suite waits, it
  waits inside WSAPoll against an absolute monotonic deadline and fails loudly
  when the budget is exhausted, so a missing event is a failure rather than a
  quiet pass."
  (:require [jolt.net.check :as c]
            [jolt.net.error :as err]
            [jolt.net.ffi :as nffi]
            [jolt.net.handle :as h]
            [jolt.net.nonblocking :as nb]
            [jolt.net.poller :as poller]
            [jolt.net.readiness :as r]
            [jolt.net.target :as t]
            [jolt.net :as net]))

(def ^:private d nffi/descriptor)
(defn- windows? [] (= :windows (:os (jolt.host/target))))

(def ^:private suite-timeout-ms 120000)
(def ^:private timeout-token ::timeout)
;; A refused loopback connect was observed taking about 2.5 s to become
;; readable as POLLERR on this host, so the readiness budget is generously
;; above it. The oracle is still the EVENT, never elapsed time.
(def ^:private connect-budget-ms 20000)
(def ^:private io-budget-ms 5000)

(defn- deadline-ns [ms] (+ (jolt.host/monotonic-nanos) (* ms 1000000)))
(defn- expired? [deadline] (< deadline (jolt.host/monotonic-nanos)))

;; --- low-level single-handle readiness --------------------------------------
;; Deliberately bypasses the registration machinery: these cases are about the
;; native ABI and Winsock's own behavior, and mixing token bookkeeping into them
;; would make a failure ambiguous.

(defn- poll1
  "One WSAPoll over a single raw handle. Returns {:result :revents :code}."
  [raw interests timeout-ms]
  (let [buf (r/alloc-entries 1)]
    (try
      (r/encode! buf 0 raw interests)
      (let [{:keys [result code]} (r/wait! buf 1 timeout-ms)]
        {:result result :revents (r/revents buf 0) :code code})
      (finally (jolt.ffi/free buf)))))

(defn- await-events!
  "Poll one handle until `pred` accepts the normalized event set, or the budget
  is exhausted. Returns the event set, or ::timeout. The wait happens INSIDE
  WSAPoll -- there is no sleep-and-retry loop here."
  [raw interests budget-ms pred]
  (let [deadline (deadline-ns budget-ms)]
    (loop []
      (let [remaining (- deadline (jolt.host/monotonic-nanos))]
        (if (neg? remaining)
          timeout-token
          (let [slice (min 1000 (quot (+ remaining 999999) 1000000))
                {:keys [result revents]} (poll1 raw interests (max 1 slice))
                events (if (pos? result) (r/event-set revents) #{})]
            (if (pred events) events (recur))))))))

;; --- backend and descriptor facts -------------------------------------------

(defn- backend-facts! []
  (c/section "wsapoll: backend selection and probed descriptor facts")
  (c/check "this target selects the WSAPoll backend"
           :windows-wsapoll (:kind r/backend))
  (c/check "the backend calls WSAPoll, not poll"
           :wsapoll (:op r/backend))
  (c/check "the backend reads WSAPOLLFD, keyed apart from POSIX pollfd"
           :wsapollfd (:struct r/backend))
  (c/check "there is no POSIX pollfd layout on this target to reach by mistake"
           nil (get-in d [:layout :pollfd]))
  (c/check "there is no POSIX poll binding on this target to reach by mistake"
           false (contains? nffi/captured-call :poll))
  (c/check "WSAPoll is on the captured surface, so its error is atomic"
           true (contains? nffi/captured-call :wsapoll))

  (c/section "wsapoll: WSAPOLLFD layout is the probed one, not pollfd's")
  (let [l (t/layout d :wsapollfd)]
    (c/check "WSAPOLLFD is 16 bytes" 16 (:size l))
    (c/check "fd is at offset 0" 0 (:fd l))
    ;; These two are the whole reason this struct cannot be shared: a POSIX
    ;; pollfd would put them at 4 and 6.
    (c/check "events is at offset 8, not 4" 8 (:events l))
    (c/check "revents is at offset 10, not 6" 10 (:revents l))
    (c/check "the encoder's entry stride is the probed struct size"
             16 r/entry-size)
    (c/check "the handle member is written as a pointer-width SOCKET"
             :uptr (:fd-type r/backend)))

  (c/section "wsapoll: readiness flag values are Winsock's, not POSIX's")
  (c/check "POLLIN is 768" 768 (t/const d :pollin))
  (c/check "POLLOUT is 16" 16 (t/const d :pollout))
  (c/check "POLLERR is 1" 1 (t/const d :pollerr))
  (c/check "POLLHUP is 2" 2 (t/const d :pollhup))
  (c/check "POLLNVAL is 4" 4 (t/const d :pollnval))
  ;; The composition, as probed. If POLLIN were carried over from POSIX as 1,
  ;; the interest word would request POLLERR and never ask for readable at all.
  (c/check "POLLIN is exactly POLLRDNORM|POLLRDBAND"
           (t/const d :pollin)
           (bit-or (t/const d :pollrdnorm) (t/const d :pollrdband)))
  (c/check "POLLOUT is exactly POLLWRNORM"
           (t/const d :pollout) (t/const d :pollwrnorm))
  (c/check "no POSIX flag value collides with Winsock's POLLIN"
           false (= 1 (t/const d :pollin)))

  (c/section "wsapoll: the probed WSAPoll signature")
  (let [sig (:wsapoll d)]
    (c/check "the fds count is a 32-bit ULONG, not pointer-width nfds_t"
             4 (:fds-bytes sig))
    (c/check "the timeout is a 32-bit signed INT" 4 (:timeout-bytes sig))
    (c/check "the result is a 32-bit int" 4 (:result-bytes sig))
    (c/check "POSIX nfds_t is absent on this target" nil (:nfds-type d))))

;; --- pure encoding and normalization ----------------------------------------

(defn- encoding-facts! []
  (c/section "wsapoll: interest masking and event normalization")
  (c/check "read interest requests POLLIN" 768 (r/interest-mask #{:read}))
  (c/check "write interest requests POLLOUT" 16 (r/interest-mask #{:write}))
  (c/check "both interests request POLLIN|POLLOUT"
           784 (r/interest-mask #{:read :write}))
  (c/check "no interests request nothing" 0 (r/interest-mask #{}))
  ;; Error, hangup and invalid-handle are reported unrequested on both targets,
  ;; and Winsock rejects them as inputs, so they must never enter `events`.
  (c/check "error is never requested" 0 (bit-and (r/interest-mask #{:read :write})
                                                 (t/const d :pollerr)))
  (c/check "hangup is never requested" 0 (bit-and (r/interest-mask #{:read :write})
                                                  (t/const d :pollhup)))

  (c/check "POLLRDNORM alone normalizes to :read"
           #{:read} (r/event-set (t/const d :pollrdnorm)))
  (c/check "POLLWRNORM alone normalizes to :write"
           #{:write} (r/event-set (t/const d :pollwrnorm)))
  (c/check "POLLERR normalizes to :error"
           #{:error} (r/event-set (t/const d :pollerr)))
  (c/check "POLLHUP normalizes to :hangup"
           #{:hangup} (r/event-set (t/const d :pollhup)))
  (c/check "POLLNVAL folds into :error"
           #{:error} (r/event-set (t/const d :pollnval)))
  ;; The observed Winsock combination for a dead connection. Note POLLWRNORM
  ;; arrives WITH POLLHUP here, which the Microsoft documentation says does not
  ;; happen; the suite asserts observed behavior.
  (c/check "POLLWRNORM|POLLERR|POLLHUP normalizes to all three"
           #{:write :error :hangup}
           (r/event-set (bit-or (t/const d :pollwrnorm)
                                (t/const d :pollerr)
                                (t/const d :pollhup))))
  ;; Data that arrived before a FIN must not be discarded because hangup is set.
  (c/check "readable survives a simultaneous hangup"
           #{:read :hangup}
           (r/event-set (bit-or (t/const d :pollrdnorm) (t/const d :pollhup))))

  (c/section "wsapoll: the encoder writes the probed offsets")
  (let [buf (r/alloc-entries 2)]
    (try
      (r/encode! buf 0 12345 #{:read})
      (r/encode! buf 1 67890 #{:write})
      (c/check "entry 0 requested POLLIN" 768 (r/events-of buf 0))
      (c/check "entry 1 requested POLLOUT" 16 (r/events-of buf 1))
      (c/check "entry 0 revents is explicitly zeroed" 0 (r/revents buf 0))
      (c/check "entry 1 revents is explicitly zeroed" 0 (r/revents buf 1))
      (c/check "the handle round-trips through the pointer-width fd member"
               12345 (jolt.ffi/read buf :uptr 0))
      (c/check "the second entry begins one probed stride later"
               67890 (jolt.ffi/read (+ buf 16) :uptr 0))
      (finally (jolt.ffi/free buf))))

  (c/section "wsapoll: an empty wait is rejected before any native call")
  ;; POSIX poll accepts nfds 0 and sleeps; WSAPoll rejects it with WSAEINVAL.
  ;; The seam refuses on both rather than letting the platforms diverge.
  (c/check-throws "a zero-entry wait fails closed"
                  {:jolt.net/kind :invalid :jolt.net/backend :windows-wsapoll}
                  #(r/wait! 0 0 0)))

;; --- fixtures ---------------------------------------------------------------

(defn- with-listener! [f]
  (let [l (net/listen (net/endpoint "127.0.0.1" 0) {:reuse-address? true})]
    (try (f l) (finally (net/close! l)))))

(defn- connected-pair!
  "A client/server pair. The blocking accept is a real synchronization point."
  [listener]
  (let [port (:jolt.net/port (net/local-endpoint listener))
        attempt (net/try-connect (net/endpoint "127.0.0.1" port))
        client (:jolt.net/socket attempt)
        server (net/accept listener)]
    (net/finish-connect! client)
    {:client client :server server}))

(defn- with-pair! [f]
  (with-listener!
    (fn [l]
      (let [{:keys [client server]} (connected-pair! l)]
        (try (f client server)
             (finally (net/close! server) (net/close! client)))))))

;; --- real readiness over real sockets ---------------------------------------

(defn- readiness-suite! []
  (c/section "wsapoll: real read, write, and timeout behavior")
  (with-pair!
    (fn [client server]
      (let [craw (net/native-handle client)
            sraw (net/native-handle server)]
        ;; Zero timeout, nothing to read: an immediate, empty answer.
        (let [{:keys [result revents]} (poll1 sraw #{:read} 0)]
          (c/check "a zero timeout with nothing ready reports no handles"
                   0 result)
          (c/check "and writes no revents bits" 0 revents))
        ;; An established connection is immediately writable.
        (let [{:keys [result revents]} (poll1 craw #{:write} 0)]
          (c/check "an established socket is immediately writable" 1 result)
          (c/check "write readiness is POLLWRNORM"
                   (t/const d :pollwrnorm) revents)
          (c/check "write readiness normalizes to :write"
                   #{:write} (r/event-set revents)))
        ;; A positive timeout that genuinely waits for a real event.
        (c/check "3 bytes were sent" 3
                 (net/try-write-bytes! client (byte-array [65 66 67]) 0 3))
        (c/check "a positive timeout returns real read readiness"
                 #{:read}
                 (await-events! sraw #{:read} io-budget-ms
                                #(contains? % :read)))
        ;; Readable and writable simultaneously.
        (c/check-pred "a socket with pending data is readable and writable"
                      #(= #{:read :write} %)
                      (await-events! sraw #{:read :write} io-budget-ms
                                     #(= #{:read :write} %))))))

  ;; This is the sharpest observed divergence from POSIX poll, and it is a
  ;; BEHAVIORAL one rather than a numbering one. Linux reports a peer FIN as
  ;; POLLIN, so a reader watching only :read still observes EOF. Winsock reports
  ;; a FIN with NO pending data as POLLHUP ALONE -- POLLRDNORM is not set. A
  ;; Windows caller that acts only on :read therefore never learns the stream
  ;; ended. Both shapes are pinned below so a regression in either direction is
  ;; a failure.
  (c/section "wsapoll: hangup and EOF -- FIN with no pending data")
  (with-pair!
    (fn [client server]
      (let [sraw (net/native-handle server)]
        (net/shutdown! client :write)
        (let [events (await-events! sraw #{:read} io-budget-ms
                                    #(contains? % :hangup))]
          (c/check-pred "a peer FIN raises hangup" #(contains? % :hangup) events)
          (c/check "an empty FIN reports hangup WITHOUT readable on Winsock"
                   #{:hangup} events))
        ;; The bytes are still retrievable through the ordinary EOF value, so
        ;; the hangup is what a caller must act on to reach it.
        (c/check "reading after that hangup yields the EOF value"
                 net/eof (net/try-read-bytes! server (byte-array 4) 0 4)))))

  (c/section "wsapoll: hangup and EOF -- FIN behind unread data")
  (with-pair!
    (fn [client server]
      (let [sraw (net/native-handle server)]
        (net/try-write-bytes! client (byte-array [90 91]) 0 2)
        (net/shutdown! client :write)
        (let [events (await-events! sraw #{:read} io-budget-ms
                                    #(contains? % :hangup))]
          ;; Here POLLRDNORM DOES accompany POLLHUP. Readable must not be
          ;; discarded because hangup is also set, or the last bytes a peer sent
          ;; before closing would be lost.
          (c/check "a FIN behind unread data reports readable AND hangup"
                   #{:read :hangup} events))
        (let [dest (byte-array 4)]
          (c/check "the bytes that preceded the FIN are still delivered"
                   2 (net/try-read-bytes! server dest 0 4))
          (c/check "and they are the exact bytes sent"
                   [90 91] (mapv #(bit-and % 0xFF) (take 2 (vec dest)))))
        (c/check "only then does the stream report EOF"
                 net/eof (net/try-read-bytes! server (byte-array 4) 0 4)))))

  (c/section "wsapoll: error readiness on a reset connection")
  (with-listener!
    (fn [l]
      (let [{:keys [client server]} (connected-pair! l)
            sraw (net/native-handle server)]
        (try
          ;; Leave a large unread payload and abort the peer: Winsock resets.
          (net/try-write-bytes! server (byte-array (repeat 4096 7)) 0 4096)
          (net/close! client)
          (let [events (await-events! sraw #{:read :write} io-budget-ms
                                      #(contains? % :error))]
            (c/check-pred "a reset connection raises error readiness"
                          #(contains? % :error) events)
            (c/check-pred "the reset also raises hangup"
                          #(contains? % :hangup) events))
          (finally (net/close! server))))))

  (c/section "wsapoll: a captured native failure, not a post-hoc error read")
  ;; A handle Winsock does not recognize fails the WHOLE call on Windows with
  ;; WSAENOTSOCK. POSIX poll would instead return a positive count and mark only
  ;; that entry POLLNVAL. This is a real behavioral divergence, not a numbering
  ;; one, and it is why the poller holds a handle lease across the native wait.
  (let [l (net/listen (net/endpoint "127.0.0.1" 0))
        raw (net/native-handle l)]
    (net/close! l)
    (let [{:keys [result code]} (poll1 raw #{:read} 0)]
      (c/check "a closed handle fails the entire WSAPoll call" -1 result)
      (c/check "the failure carries WSAENOTSOCK, captured with the result"
               10038 code)))
  (let [{:keys [result code]} (poll1 (:invalid-handle d) #{:read} 0)]
    (c/check "INVALID_SOCKET fails the entire call too" -1 result)
    (c/check "and carries the same captured code" 10038 code)))

;; --- connect completion driven by readiness ---------------------------------

(defn- connect-suite! []
  (c/section "wsapoll: an in-progress connect is completed after readiness")
  (with-listener!
    (fn [l]
      (let [port (:jolt.net/port (net/local-endpoint l))
            attempt (net/try-connect (net/endpoint "127.0.0.1" port))
            client (:jolt.net/socket attempt)
            craw (net/native-handle client)]
        (try
          (c/check-pred "initiation reports connected or in-progress"
                        #(or (net/connected? %) (net/in-progress? %))
                        (:jolt.net/status attempt))
          (let [events (await-events! craw #{:write} connect-budget-ms
                                      #(seq %))]
            (c/check-pred "the pending connect becomes write-ready"
                          #(contains? % :write) events))
          ;; Readiness alone does not decide the outcome. SO_ERROR does.
          (c/check "SO_ERROR decides success after readiness"
                   net/connected (net/finish-connect! client))
          (c/check "completion neither closed nor transferred the socket"
                   false (net/closed? client))
          (finally (net/close! client))))))

  (c/section "wsapoll: a refused connect keeps its exact code after readiness")
  ;; Bind and release a port, so nothing is listening where something could be.
  (let [probe (net/listen (net/endpoint "127.0.0.1" 0))
        port (:jolt.net/port (net/local-endpoint probe))
        _ (net/close! probe)
        expected (t/errno-code d :econnrefused)
        outcome (try {:attempt (net/try-connect (net/endpoint "127.0.0.1" port))}
                     (catch :default e {:error e}))]
    (if-let [e (:error outcome)]
      (let [data (ex-data e)]
        (c/check "a synchronously refused initiation is :connection-refused"
                 :connection-refused (:jolt.net/kind data))
        (c/check "a synchronously refused initiation keeps its exact code"
                 expected (:jolt.net/code data)))
      (let [client (:jolt.net/socket (:attempt outcome))
            craw (net/native-handle client)]
        (try
          ;; Winsock signals a doomed connect as POLLWRNORM|POLLERR|POLLHUP,
          ;; and only after the refusal actually lands -- roughly 2.5 s on
          ;; loopback here. Waiting for the EVENT is what makes the SO_ERROR
          ;; read below meaningful: before readiness, SO_ERROR still reads 0 and
          ;; would report a connection that does not exist.
          (let [events (await-events! craw #{:read :write} connect-budget-ms
                                      #(contains? % :error))]
            (c/check-pred "the refused connect becomes error-ready"
                          #(contains? % :error) events)
            (c/check-pred "the refusal is reported with hangup as well"
                          #(contains? % :hangup) events))
          (let [data (try (net/finish-connect! client) nil
                          (catch :default ex (ex-data ex)))]
            (c/check-pred "completion threw instead of reporting connected"
                          map? data)
            (when (map? data)
              (c/check "completion failure names connect"
                       :connect (:jolt.net/op data))
              (c/check "completion failure is :connection-refused"
                       :connection-refused (:jolt.net/kind data))
              (c/check "SO_ERROR preserves the exact refusal code after readiness"
                       expected (:jolt.net/code data))))
          (c/check "a failed completion does not take caller ownership"
                   false (net/closed? client))
          (c/check "the caller closes the failed attempt exactly once"
                   true (net/close! client))
          (finally (net/close! client)))))))

;; --- byte progress driven by readiness --------------------------------------

(defn- short-read-suite! []
  (c/section "wsapoll: a forced short read after readiness")
  (with-pair!
    (fn [client server]
      (let [sraw (net/native-handle server)]
        ;; Send FEWER bytes than the reader asks for. After readiness the read
        ;; must return the 3 bytes actually available rather than blocking for
        ;; the 16 requested or reporting would-block.
        (c/check "the sender wrote 3 bytes" 3
                 (net/try-write-bytes! client (byte-array [10 11 12]) 0 3))
        (c/check-pred "the reader becomes readable"
                      #(contains? % :read)
                      (await-events! sraw #{:read} io-budget-ms
                                     #(contains? % :read)))
        (let [dest (byte-array (repeat 16 0x77))
              n (net/try-read-bytes! server dest 4 12)]
          (c/check "a 12-byte request returns only the 3 available bytes" 3 n)
          (c/check "the short read landed at the destination offset, and nowhere else"
                   (vec (concat (repeat 4 0x77) [10 11 12] (repeat 9 0x77)))
                   (mapv #(bit-and % 0xFF) (vec dest)))))))

  (c/section "wsapoll: offsets, counts, and partial-progress composition")
  (with-pair!
    (fn [client server]
      (let [sraw (net/native-handle server)
            src (byte-array (concat (repeat 3 0x5A) [65 66 67 68 69]
                                    (repeat 8 0x5A)))
            dest (byte-array (repeat 16 0x77))]
        ;; Two sliced sends composed into one contiguous receive, each leg
        ;; gated by real readiness rather than by a sleep.
        (c/check "the first slice sent 2 bytes" 2
                 (net/try-write-bytes! client src 3 2))
        (c/check-pred "readable after the first slice"
                      #(contains? % :read)
                      (await-events! sraw #{:read} io-budget-ms
                                     #(contains? % :read)))
        (c/check "the first leg read 2 bytes" 2
                 (net/try-read-bytes! server dest 5 2))
        (c/check "the second slice sent 3 bytes" 3
                 (net/try-write-bytes! client src 5 3))
        (c/check-pred "readable after the second slice"
                      #(contains? % :read)
                      (await-events! sraw #{:read} io-budget-ms
                                     #(contains? % :read)))
        (c/check "the second leg read 3 bytes" 3
                 (net/try-read-bytes! server dest 7 3))
        (c/check "partial progress composed into one contiguous slice"
                 (vec (concat (repeat 5 0x77) [65 66 67 68 69] (repeat 6 0x77)))
                 (mapv #(bit-and % 0xFF) (vec dest)))
        (c/check-throws "an out-of-bounds read slice is still rejected"
                        {:jolt.net/kind :invalid}
                        #(net/try-read-bytes! server dest 12 8))))))

;; --- the shared token machinery over the WSAPoll backend --------------------

(defn- with-adapter! [f]
  (let [p (poller/open-readiness-adapter)]
    (try (f p) (finally (poller/close! p)))))

(defn- token-suite! []
  (c/section "wsapoll: the shared registration and token machinery")
  (with-adapter!
    (fn [p]
      (with-pair!
        (fn [client server]
          ;; Make the server readable so an await has something real to report.
          (net/try-write-bytes! client (byte-array [1 2 3]) 0 3)
          (let [token (poller/register! p server #{:read})]
            (c/check-pred "registration returns a generation-bearing token"
                          #(integer? (:jolt.net/generation %)) token)
            (c/check "a fresh registration starts at revision 0"
                     0 (:jolt.net/revision token))
            (c/check "the token is current immediately after registration"
                     true (poller/current-token? p token))

            ;; NON-VACUITY: a current token really is delivered.
            (let [ready (poller/await-ready p 5000)]
              (c/check "exactly one registration reported readiness"
                       1 (count ready))
              (c/check "the delivered token is the current one"
                       token (:token (first ready)))
              (c/check "and it reports real read readiness"
                       #{:read} (:events (first ready))))

            ;; REVISION INVALIDATION: the pre-update token must not dispatch.
            (let [token2 (poller/update! p token #{:read})]
              (c/check "an update returns the successor revision"
                       1 (:jolt.net/revision token2))
              (c/check "the superseded token is no longer current"
                       false (poller/current-token? p token))
              (c/check "the successor token is current"
                       true (poller/current-token? p token2))
              (let [ready (poller/await-ready p 5000)]
                (c/check "the still-readable socket dispatches its NEW token"
                         token2 (:token (first ready)))
                (c/check-pred "no stale-revision token is ever delivered"
                              #(not-any? (fn [e] (= token (:token e))) %)
                              ready))

              ;; REMOVAL INVALIDATION.
              (c/check "removal is acknowledged" true (poller/remove! p token2))
              (c/check "the removed token is no longer current"
                       false (poller/current-token? p token2))))))))

  (c/section "wsapoll: generation invalidation across a closed socket")
  (with-adapter!
    (fn [p]
      (with-listener!
        (fn [l]
          (let [{:keys [client server]} (connected-pair! l)
                token (poller/register! p server #{:read})
                generation (:jolt.net/generation token)]
            (net/close! server)
            (net/close! client)
            ;; Close ordered its removal mutation before the native close. The
            ;; captured token cannot dispatch afterwards under ANY revents.
            (c/check "a closed socket's token is no longer current"
                     false (poller/current-token? p token))
            ;; A fabricated successor generation must not resurrect it either:
            ;; the token is compared whole, not by descriptor.
            (c/check "a token bearing a different generation is not current"
                     false
                     (poller/current-token?
                      p (assoc token :jolt.net/generation (inc generation))))
            (c/check "a token bearing a different revision is not current"
                     false
                     (poller/current-token?
                      p (assoc token :jolt.net/revision 99))))))))

  (c/section "wsapoll: an await with nothing registered fails closed")
  ;; WSAPoll rejects an empty array, and with no wake transport there is nothing
  ;; that could ever complete such a wait. Sleeping out the deadline here would
  ;; be precisely the emulated wait this slice must not invent.
  (with-adapter!
    (fn [p]
      (c/check-throws "an empty adapter await refuses instead of sleeping"
                      {:jolt.net/kind :invalid
                       :jolt.net/requires :windows-wake-transport}
                      #(poller/await-ready p 50)))))

;; --- the W4 boundary, asserted as refusals ----------------------------------

(defn- fail-closed-suite! []
  (c/section "wsapoll: the wake and close lifecycle remains W4's, and refuses")
  (c/check "this backend reports no wake transport" nil (r/wake-transport))
  (c/check-throws
   "the PUBLIC Windows poller is still fail-closed"
   {:jolt.net/kind :unsupported-target}
   #(net/open-poller))
  (with-adapter!
    (fn [p]
      (c/check "the adapter knows it has no wake transport"
               false (poller/wake-transport? p))
      (c/check-throws
       "explicit wake! refuses rather than pretending"
       {:jolt.net/kind :invalid
        :jolt.net/requires :windows-wake-transport}
       #(poller/wake! p))))

  (c/section "wsapoll: W2 contracts are preserved, not resurrected")
  ;; WSAPoll must not become a blocking-mode getter. The W2 boundary stands.
  (with-listener!
    (fn [l]
      (c/check "accept before any client is still ::would-block"
               net/would-block (net/try-accept l))
      (c/check "try-accept marked the listener non-blocking"
               true (h/nonblocking? l))
      (c/check-throws
       "blocking accept after the transition is still explicitly rejected"
       {:jolt.net/op :accept
        :jolt.net/kind :invalid
        :jolt.net/state :nonblocking
        :jolt.net/requires :windows-readiness}
       #(net/accept l))))
  (c/check "readiness did not invent a nonblocking-mode getter"
           :call-status (nb/postcondition-kind))
  (c/check "a success still ignores stale native-error state"
           7 (err/checked-captured :test neg? [7 999999])))

;; --- entry point -------------------------------------------------------------

(defn- run-suite! []
  (println "jolt-net WSAPoll readiness suite (dependency-free, task W3)")
  (println (str "target: " (jolt.host/target)))
  (println (str "errno-source: " (jolt.ffi/errno-source)))
  (println (str "readiness backend: " (:kind r/backend)))
  (println (str "wake transport: " (pr-str (r/wake-transport))))

  (c/section "scaffold")
  (c/check-pred "fork prerequisite: a real monotonic clock bounds these waits"
                #(= :monotonic %) (jolt.host/monotonic-source))

  (if-not (windows?)
    ;; This suite is about the Windows readiness backend. Running it elsewhere
    ;; would be proving POSIX behavior under a Windows name.
    (c/skip "the WSAPoll suite"
            "not a Windows target; the POSIX poller suite covers poll(2)")
    (do
      (backend-facts!)
      (encoding-facts!)
      (readiness-suite!)
      (connect-suite!)
      (short-read-suite!)
      (token-suite!)
      (fail-closed-suite!))))

(defn -main [& _]
  (let [result (deref (future (run-suite!)) suite-timeout-ms timeout-token)]
    (if (= timeout-token result)
      (do
        (println)
        (println (str "FAIL  WSAPoll suite timed out after "
                      suite-timeout-ms " ms"))
        (flush)
        (System/exit 124))
      (do
        (flush)
        (System/exit (c/summary))))))
