(ns jolt.net
  "A portable socket substrate: endpoints, resolution, owned sockets, and
  numeric address inspection.

  The only namespace consumers require. Everything under jolt.net.* is private
  support, so moving this into the jolt stdlib is a file copy.

  Error contract:
    - expected states are VALUES -- ::would-block, ::eof, ::in-progress;
    - genuine failures throw ExceptionInfo carrying :jolt.net/op, /kind, /code,
      /platform and /message, where :kind is drawn from a small closed set and
      :code is the native code, ALWAYS preserved even when no kind maps to it.

  Blocking connect and accept remain available and take no deadline. A correct
  connect timeout needs a non-blocking connect completed through SO_ERROR plus a
  poller, and shipping a :timeout-ms that silently did not bound the syscall
  would be worse than not offering one."
  (:require [jolt.ffi :as ffi]
            [jolt.net.target :as t]
            [jolt.net.ffi :as nffi]
            [jolt.net.error :as err]
            [jolt.net.address :as addr]
            [jolt.net.resolver :as res]
            [jolt.net.handle :as h]
            [jolt.net.poller :as poller]))

(def ^:private d nffi/descriptor)

;; --- re-exported vocabulary -------------------------------------------------
(def would-block err/would-block)
(def eof err/eof)
(def in-progress err/in-progress)
(def would-block? err/would-block?)
(def eof? err/eof?)
(def eof-value? err/eof?)

(defn target-descriptor
  "This host's socket ABI facts, for diagnostics and tests."
  [] d)

(defn endpoint
  "Build an endpoint value. nil host means wildcard."
  ([host port] (addr/endpoint host port {}))
  ([host port opts] (addr/endpoint host port opts)))

(defn resolve
  "Resolve an endpoint to resolved-address values, in resolver order."
  ([ep] (res/resolve ep {}))
  ([ep opts] (res/resolve ep opts)))

;; --- socket options ---------------------------------------------------------
(defn- setsockopt-int! [raw level opt v ctx]
  (let [p (ffi/alloc 4)]
    (try
      (ffi/write p :int 0 v)
      (err/checked :setsockopt neg? #(nffi/invoke :setsockopt raw level opt p 4) ctx)
      (finally (ffi/free p)))))

(defn- apply-options! [raw opts ctx]
  (when (:reuse-address? opts)
    (setsockopt-int! raw (t/const d :sol-socket) (t/const d :so-reuseaddr) 1 ctx))
  (when (contains? opts :ipv6-only?)
    (setsockopt-int! raw (t/const d :ipproto-ipv6) (t/const d :ipv6-v6only)
                     (if (:ipv6-only? opts) 1 0) ctx))
  (when (:no-delay? opts)
    (setsockopt-int! raw (t/const d :ipproto-tcp) (t/const d :tcp-nodelay) 1 ctx))
  (when-let [size (:recv-buffer-size opts)]
    (when-not (and (integer? size) (pos? size))
      (throw (err/invalid-ex :setsockopt
                             ":recv-buffer-size must be a positive integer"
                             {:jolt.net/recv-buffer-size size})))
    (setsockopt-int! raw (t/const d :sol-socket) (t/const d :so-rcvbuf)
                     size ctx))
  ;; BSD suppresses SIGPIPE per socket; Linux does it per send() instead, and
  ;; Windows has no SIGPIPE at all. Sending to a closed peer must never kill the
  ;; process -- the JVM masks this signal, jolt does not.
  (when-let [nosig (t/const d :so-nosigpipe)]
    (setsockopt-int! raw (t/const d :sol-socket) nosig 1 ctx)))

(defn- with-sockaddr
  "Materialize a resolved address into scratch native memory, call f with
  [ptr len], and always free. f must not retain the pointer."
  [resolved f]
  (let [len (:jolt.net/sockaddr-len resolved)
        p (ffi/alloc (max len (addr/max-sockaddr-size d)))]
    (try
      (ffi/write-array p (:jolt.net/sockaddr resolved))
      (f p len)
      (finally (ffi/free p)))))

(defn- socket-for [resolved ctx]
  (nffi/ensure-subsystem!)
  (err/checked :socket #(not (nffi/handle-valid? %))
               #(nffi/invoke :socket
                             (if (= :inet6 (:jolt.net/family resolved))
                               (t/const d :af-inet6) (t/const d :af-inet))
                             (t/const d :sock-stream)
                             0)
               ctx))

(def ^:private posix-nonblocking-targets #{:linux :darwin})
(def ^:private poller-targets #{:linux :darwin})

(defn- posix-nonblocking-runtime? []
  (contains? posix-nonblocking-targets (:os (jolt.host/target))))

(defn- poller-runtime? []
  (contains? poller-targets (:os (jolt.host/target))))

(defn- require-posix-nonblocking-runtime! [op]
  (when-not (posix-nonblocking-runtime?)
    (throw (ex-info (str "jolt.net " (name op)
                         ": the non-blocking runtime is POSIX-only in this slice")
                    {:jolt.net/op op
                     :jolt.net/kind :unsupported-target
                     :jolt.net/target (jolt.host/target)}))))

(defn- set-nonblocking-raw! [raw ctx]
  (require-posix-nonblocking-runtime! :set-nonblocking)
  (let [flags (err/checked :fcntl-getfl neg?
                           #(nffi/invoke :fcntl raw
                                         (t/const d :f-getfl) 0)
                           ctx)]
    (err/checked :fcntl-setfl neg?
                 #(nffi/invoke :fcntl raw
                               (t/const d :f-setfl)
                               (bit-or flags (t/const d :o-nonblock)))
                 ctx))
  nil)

(defn- with-nonblocking-lease [sock f]
  (h/with-lease
    sock
    (fn [raw generation]
      (when-not (h/nonblocking? sock)
        ;; Two first users can race here; duplicate F_GETFL/F_SETFL operations
        ;; are harmless and the successful transition is monotonic.
        (set-nonblocking-raw! raw nil)
        (h/mark-nonblocking! sock))
      (f raw generation))))

;; --- inspection -------------------------------------------------------------
(defn- endpoint-of [op raw]
  (let [sz (addr/max-sockaddr-size d)
        sa (ffi/alloc sz)
        lenp (ffi/alloc 4)]
    (try
      (ffi/write lenp :int 0 sz)
      (err/checked op neg? #(nffi/invoke op raw sa lenp) nil)
      (addr/decode-sockaddr d sa)
      (finally (ffi/free sa) (ffi/free lenp)))))

(defn local-endpoint
  "The address this socket is actually bound to, read from getsockname.

  For a listener bound to port 0 this reports the KERNEL-SELECTED port, not the
  0 that was requested. Numeric only -- never triggers reverse DNS."
  [sock]
  (h/with-lease sock (fn [raw _] (endpoint-of :getsockname raw))))

(defn peer-endpoint
  "The connected peer's address, from getpeername. Numeric only."
  [sock]
  (h/with-lease sock (fn [raw _] (endpoint-of :getpeername raw))))

;; --- listen / connect / accept ----------------------------------------------
(defn listen
  "Bind and listen. Returns an owned listener usable with with-open.

  Options: :reuse-address? :backlog :ipv6-only? :no-delay? :recv-buffer-size

  Acquisition is incremental and every failure path rolls back what it already
  acquired, capturing the native error BEFORE the rollback close so the reported
  code is the bind/listen failure and not whatever close() set."
  ([ep] (listen ep {}))
  ([ep opts]
   (let [ctx {:jolt.net/endpoint ep}
         resolved (first (res/resolve ep (assoc opts :passive? true)))]
     (when-not resolved
       (throw (err/invalid-ex :listen "endpoint resolved to no addresses" ctx)))
     (let [raw (socket-for resolved ctx)]
       (try
         (apply-options! raw opts ctx)
         (with-sockaddr resolved
           (fn [p len] (err/checked :bind neg? #(nffi/invoke :bind raw p len) ctx)))
         (err/checked :listen neg?
                      #(nffi/invoke :listen raw (or (:backlog opts) 128)) ctx)
         (when (poller-runtime?)
           (set-nonblocking-raw! raw ctx))
         ;; ownership transfers only on success
         (let [listener
               (h/own raw :listener
                      {:jolt.net/family (:jolt.net/family resolved)})]
           (when (poller-runtime?)
             (h/mark-nonblocking! listener))
           listener)
         (catch :default e
           (h/raw-close! raw)      ;; strictly after the capture inside `checked`
           (throw e)))))))

(defn- native-blocking-accept
  [listener]
  (let [raw (h/raw-open listener)
        c (err/checked :accept #(not (nffi/handle-valid? %))
                       #(nffi/invoke :accept raw ffi/null ffi/null) nil)]
    (try
      ;; the accepted socket does not inherit SO_NOSIGPIPE on BSD
      (when-let [nosig (t/const d :so-nosigpipe)]
        (setsockopt-int! c (t/const d :sol-socket) nosig 1 nil))
      (h/own c :socket {})
      (catch :default e (h/raw-close! c) (throw e)))))

(declare try-accept)

(defn accept
  "Accept one connection, blocking until one arrives.

  On supported POSIX targets this is built from short non-blocking accept leases
  plus the close-wakeable poller, so concurrent listener close cannot race a recycled
  descriptor or deadlock behind an uninterruptible lease. Other existing
  platform paths retain their native blocking implementation in this slice."
  [listener]
  (if-not (poller-runtime?)
    (native-blocking-accept listener)
    (let [p (poller/open)
          terminal-listener (atom nil)]
      (try
        ;; Registration already gives the poller a best-effort mutation wake,
        ;; but listener close can publish that wake just before accept enters
        ;; await-ready; it is then a pre-entry epoch and not a cancellation
        ;; boundary. Close the accept-owned poller instead. An active await must
        ;; exit before this callback returns, while a later await is rejected by
        ;; terminal lifecycle. Darwin CI exposed the pre-entry race.
        (when-not (reset! terminal-listener
                          (h/on-close! listener #(poller/close! p)))
          (throw (err/invalid-ex :accept "listener is closed" nil)))
        (poller/register! p listener #{:read})
        (loop []
          (let [accepted (try-accept listener)]
            (if (= would-block accepted)
              (do (poller/await-ready p 1000) (recur))
              accepted)))
        (finally
          (when-let [id @terminal-listener]
            (h/remove-close-listener! listener id))
          (poller/close! p))))))

(defn set-nonblocking!
  "Put a socket into non-blocking mode. This slice supports POSIX fcntl targets;
  Windows fails closed until its ioctlsocket path is implemented."
  [sock]
  (with-nonblocking-lease sock (fn [_ _] nil))
  sock)

(defn- would-block-code? [code]
  (or (= code (t/errno-code d :eagain))
      (= code (t/errno-code d :ewouldblock))))

(defn try-accept
  "Accept one connection without waiting. This switches the listener and the
  accepted socket to non-blocking mode. Returns an owned socket or
  ::would-block; genuine failures throw."
  ([listener] (try-accept listener {}))
  ([listener opts]
   (require-posix-nonblocking-runtime! :try-accept)
   (with-nonblocking-lease
     listener
     (fn [raw _]
       (let [c (nffi/invoke :try-accept raw ffi/null ffi/null)]
         (if-not (nffi/handle-valid? c)
           (let [code (err/capture)]
             (if (would-block-code? code)
               would-block
               (throw (err/native-ex :accept code nil))))
           (try
             (apply-options! c opts nil)
             (set-nonblocking-raw! c nil)
             (let [socket (h/own c :socket {})]
               (h/mark-nonblocking! socket)
               socket)
             (catch :default e
               (h/raw-close! c)
               (throw e)))))))))

(defn connect
  "Connect to the first address that works, in resolver order. BLOCKING.

  If every address fails, the LAST error is raised rather than a synthetic one,
  so the native code the caller sees is a real code from a real attempt."
  ([ep] (connect ep {}))
  ([ep opts]
   (let [ctx {:jolt.net/endpoint ep}
         addrs (res/resolve ep opts)]
     (when (empty? addrs)
       (throw (err/invalid-ex :connect "endpoint resolved to no addresses" ctx)))
     (loop [[a & more] addrs last-ex nil]
       (if-not a
         (throw last-ex)
         (let [raw (socket-for a ctx)
               r (try
                   (apply-options! raw opts ctx)
                   (with-sockaddr a
                     (fn [p len]
                       (err/checked :connect neg? #(nffi/invoke :connect raw p len) ctx)))
                   (when (poller-runtime?)
                     (set-nonblocking-raw! raw ctx))
                   (let [socket
                         (h/own raw :socket
                                {:jolt.net/family (:jolt.net/family a)})]
                     (when (poller-runtime?)
                       (h/mark-nonblocking! socket))
                     socket)
                   (catch :default e
                     (h/raw-close! raw)
                     {::failed e}))]
           (if (and (map? r) (::failed r))
             (recur more (::failed r))
             r)))))))

;; --- lifecycle --------------------------------------------------------------
(defn shutdown!
  "Shut down one or both directions. `dir` is :read, :write, or :both.

  shutdown(:write) means no more bytes will be SENT; it does not close the read
  side, and the peer sees EOF while this socket stays readable."
  [sock dir]
  (let [how (case dir
              :read (t/const d :shut-rd)
              :write (t/const d :shut-wr)
              :both (t/const d :shut-rdwr)
              (throw (err/invalid-ex :shutdown "dir must be :read, :write or :both"
                                     {:jolt.net/dir dir})))]
    (h/with-lease
      sock
      (fn [raw _]
        (err/checked :shutdown neg? #(nffi/invoke :shutdown raw how) nil)))
    nil))

;; --- readiness-oriented byte I/O -------------------------------------------
(defn- check-slice! [op bytes off len]
  (let [n (alength bytes)]
    (when (or (neg? off) (neg? len) (> (+ off len) n))
      (throw (err/invalid-ex op "byte-array slice is out of bounds"
                             {:jolt.net/offset off
                              :jolt.net/length len
                              :jolt.net/capacity n})))))

(defn try-read-bytes!
  "Read into dest[off,off+len) without waiting.

  Returns a positive byte count, 0 only for a zero-length request,
  ::would-block, or ::eof. Genuine failures throw a structured exception."
  [sock dest off len]
  (require-posix-nonblocking-runtime! :read)
  (check-slice! :read dest off len)
  (if (zero? len)
    0
    (with-nonblocking-lease
      sock
      (fn [raw _]
        (ffi/with-byte-array-pointer
          dest off len
          (fn [ptr nbytes]
            (let [n (nffi/invoke :try-recv raw ptr nbytes 0)]
              (cond
                (pos? n) n
                (zero? n) eof
                :else (let [code (err/capture)]
                        (if (would-block-code? code)
                          would-block
                          (throw (err/native-ex :read code nil))))))))))))

(defn try-write-bytes!
  "Write from src[off,off+len) without waiting.

  Returns a positive byte count, 0 only for a zero-length request, or
  ::would-block. The target's SIGPIPE suppression prevents a closed peer from
  terminating the process; genuine failures throw a structured exception."
  [sock src off len]
  (require-posix-nonblocking-runtime! :write)
  (check-slice! :write src off len)
  (if (zero? len)
    0
    (with-nonblocking-lease
      sock
      (fn [raw _]
        (ffi/with-byte-array-pointer
          src off len
          (fn [ptr nbytes]
            (let [n (nffi/invoke :try-send
                                 raw ptr nbytes
                                 (or (t/const d :msg-nosignal) 0))]
              (cond
                (pos? n) n
                (zero? n)
                (throw (err/invalid-ex
                         :write
                         "send made no progress for a non-empty slice"
                         {:jolt.net/length len}))
                :else (let [code (err/capture)]
                        (if (would-block-code? code)
                          would-block
                          (throw (err/native-ex :write code nil))))))))))))

(defn close!
  "Close a socket or poller. Idempotent: returns true if this call closed it."
  [owned]
  (if (:jolt.net/poller owned)
    (poller/close! owned)
    (h/close! owned)))

(defn closed? [sock] (h/closed? sock))

(defn native-handle
  "The raw descriptor, for diagnostics only. Conveys no ownership."
  [sock] (h/raw sock))

;; --- readiness poller -------------------------------------------------------
(defn open-poller
  "Open a POSIX poll(2) poller. The returned owned value works with with-open."
  []
  (poller/open))

(defn register!
  "Register a borrowed socket for #{:read :write}; return its current token."
  [p socket interests]
  (poller/register! p socket interests))

(defn update-registration!
  "Replace a current token's interests and return its successor token."
  [p token interests]
  (poller/update! p token interests))

(defn remove-registration!
  "Remove a current registration token."
  [p token]
  (poller/remove! p token))

(defn wake!
  "Wake a blocked await on p."
  [p]
  (poller/wake! p))

(defn await-ready
  "Wait up to timeout-ms and return readiness maps carrying current tokens."
  [p timeout-ms]
  (poller/await-ready p timeout-ms))
