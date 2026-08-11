(ns jolt.net.wake
  "The poller's owner-independent wake transport: one small per-target seam.

  The poller owns exactly one lifecycle, one acknowledged mutation queue, one
  registration/token state machine, one wake epoch, one stale-event gate, one
  readiness decoder, and one caller-owned absolute deadline. None of that is
  duplicated here. This namespace owns only the things that genuinely differ
  between targets:

    - the read and write handles;
    - the non-blocking signal and drain calls, with their exact captured native
      errors and this target's argument/result widths;
    - which drain outcomes are benign rather than failures;
    - construction rollback; and
    - ordered terminal retirement of the sender and then the receiver.

  Two transports:

    :self-pipe          POSIX pipe(2), signalled with write(2), drained with
                        read(2). Closing the write end additionally makes the
                        retained read end report POLLHUP, so POSIX has a second,
                        independent cancellation guarantee.

    :windows-datagram   A connected IPv4 loopback datagram pair. Both ends are
                        real `SOCKET`s, which is the property that matters:
                        WSAPoll accepts only sockets, and an anonymous pipe
                        handle would fail the entire call with WSAENOTSOCK
                        rather than degrade. Signalled with `send`, drained with
                        `recv`, through the captured Winsock bindings whose
                        length and result parameters are `int` -- NOT the
                        size_t/ssize_t POSIX shapes -- and never through POSIX
                        read/write, which are not valid on a Winsock handle.

  The two are NOT interchangeable in their terminal semantics, and the poller
  must not assume they are. Closing a datagram SENDER is not a hangup oracle for
  its peer: the receiver observes no POLLHUP, and any WSAECONNRESET it may later
  surface comes from an ICMP port-unreachable, which is a best-effort artifact
  and not a protocol guarantee. Windows cancellation therefore rests on a real
  terminal BYTE that close publishes while sends are still admitted, never on
  peer close. `terminal-wake` names that difference so it stays a fact of the
  transport rather than an assumption buried in close."
  (:require [jolt.ffi :as ffi]
            [jolt.net.address :as addr]
            [jolt.net.error :as err]
            [jolt.net.ffi :as nffi]
            [jolt.net.handle :as h]
            [jolt.net.nonblocking :as nb]
            [jolt.net.target :as t]))

(def ^:private d nffi/descriptor)
(def ^:private windows? (= :windows (:platform d)))

(def kind
  "This target's wake transport kind. Selected once, at load, from the probed
  descriptor."
  (if windows? :windows-datagram :self-pipe))

(def terminal-wake
  "What actually forces a parked native wait to return when the poller closes.

    :byte-or-hangup -- POSIX. Close publishes a byte AND retiring the write end
                       leaves the retained read end reporting POLLHUP, so either
                       one alone is sufficient.
    :byte-only      -- Windows datagram. Retiring the sender is not observable
                       to the receiver, so the published terminal byte is the
                       ONLY cancellation guarantee and close must publish it
                       before sends are retired."
  (if windows? :byte-only :byte-or-hangup))

;; --- native call surface -----------------------------------------------------
;; Each target names its own captured ops. There is deliberately no fallback: a
;; target reaching for the other's op raises "no captured binding" at
;; invoke-captured rather than calling something plausible on the wrong handle.

(def ^:private signal-op (if windows? :try-send :write))
(def ^:private drain-op (if windows? :try-recv :read))

(defn- captured
  "Normalize a captured `[result code]` pair into the poller's result map."
  [[result code]]
  (if (neg? result) {:result result :code code} {:result result}))

(defn- signal-native
  "Send one wake byte without blocking. Windows passes the extra `flags`
  argument and carries `int` length/result widths; POSIX write(2) does not."
  [raw buf len]
  (captured (if windows?
              (nffi/invoke-captured signal-op raw buf len 0)
              (nffi/invoke-captured signal-op raw buf len))))

(defn- drain-native
  [raw buf len]
  (captured (if windows?
              (nffi/invoke-captured drain-op raw buf len 0)
              (nffi/invoke-captured drain-op raw buf len))))

(def ^:private wsaeconnreset 10054)

(defn- benign-drain-code?
  "Is this captured drain error simply \"nothing more to drain\" rather than a
  failure?

  Both targets accept EAGAIN/EWOULDBLOCK. Windows additionally accepts
  WSAECONNRESET, which a connected UDP receiver reports once after an ICMP
  port-unreachable for a datagram it sent -- an artifact of the retired peer,
  not a transport fault. Treating it as benign is safe precisely because the
  protocol never relies on it: the terminal byte was already published and
  delivered while the sender was still admitted."
  [code]
  (or (= code (t/errno-code d :eagain))
      (= code (t/errno-code d :ewouldblock))
      (and windows? (= code wsaeconnreset))))

;; --- construction ------------------------------------------------------------

(defn- open-self-pipe []
  (let [fds (ffi/alloc 8)]
    (try
      (err/checked-captured :pipe neg? (nffi/invoke-captured :pipe fds))
      (let [read-raw (ffi/read fds :int 0)
            write-raw (ffi/read fds :int 4)]
        (try
          ;; Both ends go non-blocking BEFORE either becomes an owned handle:
          ;; publishing ownership of a still-blocking end would let a wake write
          ;; park a supposedly short lease.
          (nb/set-raw! read-raw)
          (nb/set-raw! write-raw)
          {:read (h/own read-raw :poller-wake-read {})
           :write (h/own write-raw :poller-wake-write {})}
          (catch :default e
            (h/raw-close! read-raw)
            (h/raw-close! write-raw)
            (throw e))))
      (finally (ffi/free fds)))))

(def ^:private loopback-v4
  {:jolt.net/family :inet :jolt.net/host "127.0.0.1" :jolt.net/port 0})

(defn- dgram-socket! [ctx]
  (err/checked-captured
   :socket
   #(not (nffi/handle-valid? %))
   (nffi/invoke-captured :socket
                         (t/const d :af-inet)
                         (t/const d :sock-dgram)
                         0)
   ctx))

(defn- bind-ephemeral! [raw ctx]
  (let [sz (:size (t/layout d :sockaddr-in))
        p (ffi/alloc sz)]
    (try
      (let [len (addr/encode-sockaddr! d p loopback-v4)]
        (err/checked-captured
         :bind neg? (nffi/invoke-captured :bind raw p len) ctx))
      (finally (ffi/free p)))))

(defn- sockname [raw ctx]
  (let [sz (addr/max-sockaddr-size d)
        sa (ffi/alloc sz)
        lenp (ffi/alloc 4)]
    (try
      (ffi/write lenp :int 0 sz)
      (err/checked-captured
       :getsockname neg?
       (nffi/invoke-captured :getsockname raw sa lenp) ctx)
      (addr/decode-sockaddr d sa)
      (finally (ffi/free sa) (ffi/free lenp)))))

(defn- connect-to! [raw peer ctx]
  (let [sz (:size (t/layout d :sockaddr-in))
        p (ffi/alloc sz)]
    (try
      (let [len (addr/encode-sockaddr! d p peer)]
        ;; :try-connect, not :connect. A datagram connect only fixes the peer
        ;; address; it exchanges nothing and cannot block, so parking it on a
        ;; __collect_safe boundary would buy nothing.
        (err/checked-captured
         :connect neg?
         (nffi/invoke-captured :try-connect raw p len) ctx))
      (finally (ffi/free p)))))

(defn- open-windows-datagram []
  (let [ctx {:jolt.net/transport :windows-datagram}
        ;; Every raw handle acquired so far, so a failure at ANY step closes
        ;; each partially constructed one exactly once rather than leaking it.
        acquired (atom [])
        track! (fn [raw] (swap! acquired conj raw) raw)]
    (try
      (let [read-raw (track! (dgram-socket! ctx))
            write-raw (track! (dgram-socket! ctx))]
        (bind-ephemeral! read-raw ctx)
        (bind-ephemeral! write-raw ctx)
        (let [read-addr (sockname read-raw ctx)
              write-addr (sockname write-raw ctx)]
          ;; Connect BOTH ends. The sender gets a fixed destination so `send`
          ;; needs no address; the receiver gets a fixed source so a stray
          ;; datagram from anything other than this pair cannot be mistaken for
          ;; a wake.
          (connect-to! write-raw read-addr ctx)
          (connect-to! read-raw write-addr ctx))
        ;; Non-blocking BEFORE ownership is published, for both sockets. A
        ;; blocking wake sender would park an admitted writer inside close's
        ;; drain wait, which is precisely the deadlock this ordering forbids.
        (nb/set-raw! read-raw ctx)
        (nb/set-raw! write-raw ctx)
        (let [read-h (h/mark-nonblocking!
                      (h/own read-raw :poller-wake-read {}))
              write-h (h/mark-nonblocking!
                       (h/own write-raw :poller-wake-write {}))]
          {:read read-h :write write-h}))
      (catch :default e
        (doseq [raw @acquired] (h/raw-close! raw))
        (throw e)))))

(defn open
  "Construct this target's wake transport.

  Returns {:read owned-handle :write owned-handle ...}. Ownership transfers only
  on success: every partially constructed handle is closed on any failure path,
  so a rollback cannot leak a descriptor."
  []
  (let [handles (if windows? (open-windows-datagram) (open-self-pipe))]
    (assoc handles
           :jolt.net/wake-transport true
           :kind kind
           :terminal-wake terminal-wake
           :signal! signal-native
           :drain! drain-native
           :benign-drain-code? benign-drain-code?)))

;; --- terminal retirement -----------------------------------------------------
;; Ordered, and the order is load-bearing. The sender is retired only after
;; close has published a real terminal byte and drained already-admitted sends;
;; the receiver is retired only after the active native wait has released its
;; lease. Both are idempotent, because close and the exiting waiter race for the
;; final transition and exactly one of them performs it.

(defn retire-sender!
  "Close the wake write handle. No admitted sender remains by construction, so
  on POSIX this cannot SIGPIPE one."
  [transport]
  (when transport (h/close! (:write transport)))
  nil)

(defn retire-receiver!
  "Close the wake read handle. Called only after the active native wait has
  exited and released its lease."
  [transport]
  (when transport
    (h/close! (:write transport))
    (h/close! (:read transport)))
  nil)
