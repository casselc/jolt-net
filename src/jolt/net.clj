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

  In this slice connect and accept are BLOCKING and take no deadline. A correct
  connect timeout needs a non-blocking connect completed through SO_ERROR plus a
  poller, and shipping a :timeout-ms that silently did not bound the syscall
  would be worse than not offering one."
  (:require [jolt.ffi :as ffi]
            [jolt.net.target :as t]
            [jolt.net.ffi :as nffi]
            [jolt.net.error :as err]
            [jolt.net.address :as addr]
            [jolt.net.resolver :as res]
            [jolt.net.handle :as h]))

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
  (endpoint-of :getsockname (h/raw-open sock)))

(defn peer-endpoint
  "The connected peer's address, from getpeername. Numeric only."
  [sock]
  (endpoint-of :getpeername (h/raw-open sock)))

;; --- listen / connect / accept ----------------------------------------------
(defn listen
  "Bind and listen. Returns an owned listener usable with with-open.

  Options: :reuse-address? :backlog :ipv6-only? :no-delay?

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
         ;; ownership transfers only on success
         (h/own raw :listener {:jolt.net/family (:jolt.net/family resolved)})
         (catch :default e
           (h/raw-close! raw)      ;; strictly after the capture inside `checked`
           (throw e)))))))

(defn accept
  "Accept one connection. BLOCKING in this slice."
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
                   (h/own raw :socket {:jolt.net/family (:jolt.net/family a)})
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
    (err/checked :shutdown neg? #(nffi/invoke :shutdown (h/raw-open sock) how) nil)
    nil))

(defn close!
  "Close the socket. Idempotent: returns true if this call closed it."
  [sock] (h/close! sock))

(defn closed? [sock] (h/closed? sock))

(defn native-handle
  "The raw descriptor, for diagnostics only. Conveys no ownership."
  [sock] (h/raw sock))
