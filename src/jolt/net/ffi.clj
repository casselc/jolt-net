(ns jolt.net.ffi
  "Raw socket bindings for every supported platform, plus the active call table.

  No policy lives here: no error interpretation, no allocation a caller does not
  free, no ownership. Just typed entry points and the selection of which set is
  live on this target.

  Every platform's bindings are declared unconditionally at top level. That is
  safe because defcfn resolves its C symbol LAZILY, on first call
  (jolt-core/jolt/backend_scheme.clj) -- so `closesocket` never being resolvable
  on Linux costs nothing, as long as nothing calls it. The payoff is that the
  Windows signature set is an ordinary value a Linux test can inspect. This is a
  hard prerequisite; see docs/UPSTREAM-NOTES.md.

  Signatures genuinely differ between POSIX and Winsock and cannot be shared:
  a POSIX socket is an int fd while a Windows SOCKET is a pointer-width unsigned
  handle (probe: 8 bytes on Win64), and the recv/send length and return types are
  size_t/ssize_t on POSIX but int on Winsock."
  (:require [jolt.ffi :as ffi]
            [jolt.net.target :as t]))

;; Resolve the process's own symbols (libc / already-loaded Winsock). Paired with
;; :jolt/native {:process true} in deps.edn.
(ffi/load-library)

;; --- POSIX ------------------------------------------------------------------
;; socklen_t is unsigned int on both Linux and macOS.
(ffi/defcfn p-socket-with-error "socket" [:int :int :int] :int
  {:capture-native-error true})
(ffi/defcfn p-bind-with-error "bind" [:int :pointer :uint] :int
  {:capture-native-error true})
(ffi/defcfn p-listen-with-error "listen" [:int :int] :int
  {:capture-native-error true})
(ffi/defcfn p-accept-with-error "accept" [:int :pointer :pointer] :int
  {:blocking true :capture-native-error true})
(ffi/defcfn p-try-accept-with-error "accept" [:int :pointer :pointer] :int
  {:capture-native-error true})
(ffi/defcfn p-connect-with-error "connect" [:int :pointer :uint] :int
  {:blocking true :capture-native-error true})
(ffi/defcfn p-try-connect-with-error "connect" [:int :pointer :uint] :int
  {:capture-native-error true})
(ffi/defcfn p-close       "close"       [:int] :int)
(ffi/defcfn p-shutdown-with-error "shutdown" [:int :int] :int
  {:capture-native-error true})
(ffi/defcfn p-getsockname-with-error "getsockname" [:int :pointer :pointer] :int
  {:capture-native-error true})
(ffi/defcfn p-getpeername-with-error "getpeername" [:int :pointer :pointer] :int
  {:capture-native-error true})
(ffi/defcfn p-setsockopt-with-error "setsockopt"
  [:int :int :int :pointer :uint] :int
  {:capture-native-error true})
(ffi/defcfn p-getsockopt-with-error "getsockopt"
  [:int :int :int :pointer :pointer] :int
  {:capture-native-error true})
(ffi/defcfn p-recv-with-error "recv" [:int :pointer :size_t :int] :ssize_t
  {:blocking true :capture-native-error true})
(ffi/defcfn p-send-with-error "send" [:int :pointer :size_t :int] :ssize_t
  {:blocking true :capture-native-error true})
(ffi/defcfn p-try-recv-with-error "recv" [:int :pointer :size_t :int] :ssize_t
  {:capture-native-error true})
(ffi/defcfn p-try-send-with-error "send" [:int :pointer :size_t :int] :ssize_t
  {:capture-native-error true})
;; POSIX readiness support. fcntl is variadic in C. Its third argument remains
;; typed :int for this bounded F_GETFL/F_SETFL surface, but the ABI must still
;; name the two-fixed-argument boundary: Apple arm64 places `...` arguments on
;; the stack even when a fixed third argument would have occupied a register.
(ffi/defcfn p-fcntl-with-error "fcntl" [:int :int :int] :int
  {:varargs-after 2 :capture-native-error true})
(ffi/defcfn p-pipe-with-error "pipe" [:pointer] :int
  {:capture-native-error true})
(ffi/defcfn p-poll-size-with-error "poll" [:pointer :size_t :int] :int
  {:blocking true :capture-native-error true})
(ffi/defcfn p-poll-uint-with-error "poll" [:pointer :uint :int] :int
  {:blocking true :capture-native-error true})
(ffi/defcfn p-read-with-error "read" [:int :pointer :size_t] :ssize_t
  {:capture-native-error true})
(ffi/defcfn p-write-with-error "write" [:int :pointer :size_t] :ssize_t
  {:capture-native-error true})

;; --- Winsock ----------------------------------------------------------------
;; SOCKET is :uptr, and socklen is int rather than socklen_t.
(ffi/defcfn w-socket-with-error "socket" [:int :int :int] :uptr
  {:capture-native-error true})
(ffi/defcfn w-bind-with-error "bind" [:uptr :pointer :int] :int
  {:capture-native-error true})
(ffi/defcfn w-listen-with-error "listen" [:uptr :int] :int
  {:capture-native-error true})
(ffi/defcfn w-accept-with-error "accept" [:uptr :pointer :pointer] :uptr
  {:blocking true :capture-native-error true})
(ffi/defcfn w-connect-with-error "connect" [:uptr :pointer :int] :int
  {:blocking true :capture-native-error true})
(ffi/defcfn w-close       "closesocket" [:uptr] :int)
(ffi/defcfn w-shutdown-with-error "shutdown" [:uptr :int] :int
  {:capture-native-error true})
(ffi/defcfn w-getsockname-with-error "getsockname"
  [:uptr :pointer :pointer] :int
  {:capture-native-error true})
(ffi/defcfn w-getpeername-with-error "getpeername"
  [:uptr :pointer :pointer] :int
  {:capture-native-error true})
(ffi/defcfn w-setsockopt-with-error "setsockopt"
  [:uptr :int :int :pointer :int] :int
  {:capture-native-error true})
(ffi/defcfn w-recv-with-error "recv" [:uptr :pointer :int :int] :int
  {:blocking true :capture-native-error true})
(ffi/defcfn w-send-with-error "send" [:uptr :pointer :int :int] :int
  {:blocking true :capture-native-error true})
;; Winsock non-blocking surface. Same C entry points as the blocking bindings
;; above, declared separately WITHOUT :blocking: once the handle is in FIONBIO
;; mode these return immediately, and parking them on a __collect_safe boundary
;; would buy nothing while widening the window in which the runtime may move
;; the payload array behind a pinned interior pointer.
(ffi/defcfn w-try-accept-with-error "accept" [:uptr :pointer :pointer] :uptr
  {:capture-native-error true})
(ffi/defcfn w-try-connect-with-error "connect" [:uptr :pointer :int] :int
  {:capture-native-error true})
(ffi/defcfn w-try-recv-with-error "recv" [:uptr :pointer :int :int] :int
  {:capture-native-error true})
(ffi/defcfn w-try-send-with-error "send" [:uptr :pointer :int :int] :int
  {:capture-native-error true})
;; getsockopt's optlen is an in/out int* here, not socklen_t*.
(ffi/defcfn w-getsockopt-with-error "getsockopt"
  [:uptr :int :int :pointer :pointer] :int
  {:capture-native-error true})
;; int ioctlsocket(SOCKET s, long cmd, u_long *argp). `cmd` is :int because a
;; Windows long is 32 bits even on Win64 (probed: :ioctl-cmd-bytes 4) -- passing
;; it as a pointer-width type would misplace the argument. FIONBIO itself is
;; negative when read as a signed long; see jolt.net.target.
(ffi/defcfn w-ioctlsocket-with-error "ioctlsocket" [:uptr :int :pointer] :int
  {:capture-native-error true})
;; int WSAAPI WSAPoll(LPWSAPOLLFD fdArray, ULONG fds, INT timeout).
;;
;; `fds` is :uint because a Windows ULONG is 32 bits even on Win64 -- it is NOT
;; the pointer-width nfds_t that POSIX poll takes, so p-poll-size-with-error's
;; shape would misplace the timeout. The probe pins this by initializing a typed
;; function pointer from WSAPoll, which does not compile if the declaration
;; differs. :blocking because WSAPoll parks for the whole timeout.
(ffi/defcfn w-wsapoll-with-error "WSAPoll" [:pointer :uint :int] :int
  {:blocking true :capture-native-error true})
(ffi/defcfn w-wsastartup  "WSAStartup"  [:uint16 :pointer] :int)

;; --- resolver (same signature on both) --------------------------------------
;; Blocking and NOT cancellable: there is no portable way to interrupt an
;; in-flight getaddrinfo. Deadlines are applied around it, never inside it.
;;
;; node/service are :pointer, not :string, for two reasons. Chez rejects a string
;; argument on a __collect_safe (:blocking) procedure outright -- a Scheme string
;; may move during a collection while the call is parked. And the resolver has to
;; own and free those C strings anyway, so materializing them explicitly keeps
;; the allocation visible rather than hidden behind a marshaling convention.
(ffi/defcfn c-getaddrinfo-with-error
  "getaddrinfo" [:pointer :pointer :pointer :pointer] :int
  {:blocking true :capture-native-error true})
(ffi/defcfn c-freeaddrinfo "freeaddrinfo" [:pointer] :void)
;; POSIX only -- Winsock's gai_strerror is a non-thread-safe macro, so on Windows
;; messages come from the static table in jolt.net.error instead.
(ffi/defcfn c-gai-strerror "gai_strerror" [:int] :pointer)

;; --- active call table ------------------------------------------------------
;; Built once from the target descriptor. Resolving the descriptor here means an
;; unsupported target fails at namespace load, before any syscall can be reached.
(def descriptor (t/descriptor))

(def ^:private windows? (= :windows (:platform descriptor)))
(def ^:private p-poll-with-error
  (case (:nfds-type descriptor)
    :size_t p-poll-size-with-error
    :uint p-poll-uint-with-error
    nil))

(def call
  (if windows?
    {:close w-close}
    {:close p-close}))

(def captured-call
  (if windows?
    {:socket w-socket-with-error
     :bind w-bind-with-error
     :listen w-listen-with-error
     :accept w-accept-with-error
     :try-accept w-try-accept-with-error
     :connect w-connect-with-error
     :try-connect w-try-connect-with-error
     :shutdown w-shutdown-with-error
     :getsockname w-getsockname-with-error
     :getpeername w-getpeername-with-error
     :setsockopt w-setsockopt-with-error
     :getsockopt w-getsockopt-with-error
     :recv w-recv-with-error
     :send w-send-with-error
     :try-recv w-try-recv-with-error
     :try-send w-try-send-with-error
     ;; the Windows counterpart of POSIX :fcntl; there is deliberately no
     ;; :ioctlsocket entry on POSIX, so a mis-selected transition fails closed
     ;; at invoke-captured rather than calling something plausible
     :ioctlsocket w-ioctlsocket-with-error
     ;; The Windows counterpart of POSIX :poll, keyed apart for the same
     ;; fail-closed reason. There is no `poll` symbol in ws2_32 and no
     ;; :wsapoll entry on POSIX, so a backend that reached for the wrong one
     ;; raises "no captured binding" instead of resolving something plausible
     ;; and handing it a struct laid out for the other platform.
     :wsapoll w-wsapoll-with-error
     :getaddrinfo c-getaddrinfo-with-error}
    {:socket p-socket-with-error
     :bind p-bind-with-error
     :listen p-listen-with-error
     :accept p-accept-with-error
     :try-accept p-try-accept-with-error
     :connect p-connect-with-error
     :try-connect p-try-connect-with-error
     :shutdown p-shutdown-with-error
     :getsockname p-getsockname-with-error
     :getpeername p-getpeername-with-error
     :setsockopt p-setsockopt-with-error
     :getsockopt p-getsockopt-with-error
     :recv p-recv-with-error
     :send p-send-with-error
     :try-recv p-try-recv-with-error
     :try-send p-try-send-with-error
     :fcntl p-fcntl-with-error
     :pipe p-pipe-with-error
     :poll p-poll-with-error
     :read p-read-with-error
     :write p-write-with-error
     :getaddrinfo c-getaddrinfo-with-error}))

(defn invoke
  "Call the platform's scalar implementation of `op`. Named ops rather than
  direct vars so no call site has to know which platform it is on."
  [op & args]
  (apply (or (get call op)
             (throw (ex-info (str "jolt.net: no binding for " op)
                             {:jolt.net/kind :invalid :jolt.net/op op})))
         args))

(defn invoke-captured
  "Call a sentinel-returning binding whose error is consumed, atomically
  returning
  `[native-result native-error]`.

  This is deliberately separate from `invoke`: neither dispatcher has an
  operation-dependent result shape, so a caller cannot accidentally treat a
  captured pair as an ordinary scalar."
  [op & args]
  (apply (or (get captured-call op)
             (throw (ex-info (str "jolt.net: no captured binding for " op)
                             {:jolt.net/kind :invalid :jolt.net/op op})))
         args))

(defn handle-valid? [h] (t/handle-valid? descriptor h))
(defn const [k] (t/const descriptor k))

;; --- Winsock subsystem ------------------------------------------------------
;; WSAStartup is process-scoped and once-only. Individual sockets must NOT pair
;; it with WSACleanup: that would tear the subsystem down under other users.
;; The outcome is memoized either way, so a failure is not retried on every
;; socket -- jolt.mvn-http re-runs its init on each attempt, which is the bug
;; this avoids.
;;
;; A check-then-reset atom (read @subsystem, maybe CAS a result in) is NOT a
;; once-only protocol: two threads can both observe nil before either writes,
;; and both then call WSAStartup. `subsystem` instead holds one of three kinds
;; of value: nil (untried), a promise (one attempt in flight), or a resolved
;; outcome (:ok or {:error ex}, permanently memoized). Exactly one caller wins
;; the nil->promise CAS and performs the single WSAStartup call; every other
;; caller -- concurrent or arriving later -- blocks on or reads that same
;; promise/outcome instead of attempting its own.
(def ^:private subsystem (atom nil))

;; Diagnostic only -- not consulted by ensure-subsystem! itself. Lets a
;; concurrent first-use stress test prove exactly one WSAStartup call was
;; made, rather than trusting the memoized-value assertion alone.
(def ^:private startup-attempts (atom 0))

(defn winsock-startup-attempts
  "How many times this process has actually called WSAStartup. Diagnostic
  only, for the once-only stress test; always 0 on POSIX."
  []
  @startup-attempts)

(defn- attempt-wsa-startup!
  "The single WSAStartup call a winning caller performs. Native WSAStartup
  failure is returned as {:error ex}; an exceptional FFI/allocation failure is
  normalized by ensure-once! so it cannot strand the in-flight promise."
  []
  (let [buf (ffi/alloc 512)]                       ; WSADATA
    (try
      ;; WSAStartup RETURNS its error code; it does not set the last-error
      ;; slot, so WSAGetLastError must not be consulted here.
      (swap! startup-attempts inc)
      (let [rc (w-wsastartup 0x0202 buf)]           ; request 2.2
        (if (zero? rc)
          :ok
          {:error (ex-info "jolt.net: WSAStartup failed"
                           {:jolt.net/kind :unknown
                            :jolt.net/op :wsa-startup
                            :jolt.net/code rc
                            :jolt.net/platform :windows})}))
      (finally (ffi/free buf)))))

(defn- resolve-subsystem-outcome
  "true for :ok; throws the SAME memoized exception object for {:error ex}, so
  every caller -- including ones long after the original attempt -- sees an
  identical failure rather than a fresh, differently-timestamped one."
  [outcome]
  (if (= :ok outcome)
    true
    (throw (:error outcome))))

(defn ensure-once!
  "Internal, testable once-only state machine.

  `state` contains nil, an in-flight promise, or a terminal :ok/{:error ex}
  outcome. `attempt` is invoked exactly once by the nil->promise CAS winner.
  Both returned failures and thrown exceptions become the same terminal error
  outcome. Terminal state is published and the promise is delivered before the
  winning public call returns or throws, so a failed attempt cannot strand
  current or future waiters.

  This lives in the private support namespace rather than the public jolt.net
  API so deterministic controls can exercise success, returned-error, and
  thrown-exception paths without resetting process-global Winsock state."
  [state attempt]
  (loop []
    (let [s @state]
      (cond
        (= :ok s) (resolve-subsystem-outcome s)
        (map? s) (resolve-subsystem-outcome s)
        ;; a pending promise from the in-flight winner: wait for its result
        ;; rather than attempting a second initialization
        (some? s) (resolve-subsystem-outcome (deref s))
        :else
        (let [p (promise)]
          (if (compare-and-set! state nil p)
            (let [outcome (try
                            (attempt)
                            (catch :default e {:error e}))]
              ;; Publish terminal state before waking a waiter. Both transitions
              ;; precede the winner's own return/throw below.
              (reset! state outcome)
              (deliver p outcome)
              (resolve-subsystem-outcome outcome))
            (recur)))))))

(defn ensure-subsystem!
  "Initialize Winsock exactly once, even under concurrent first callers. A
  no-op on POSIX. Returns true when usable; throws the memoized failure on
  every call after a failed attempt."
  []
  (if-not windows?
    true
    (ensure-once! subsystem attempt-wsa-startup!)))
