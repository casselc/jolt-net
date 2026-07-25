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
(ffi/defcfn p-socket      "socket"      [:int :int :int] :int)
(ffi/defcfn p-bind        "bind"        [:int :pointer :uint] :int)
(ffi/defcfn p-listen      "listen"      [:int :int] :int)
(ffi/defcfn p-accept-with-error "accept" [:int :pointer :pointer] :int
  {:blocking true :capture-native-error true})
(ffi/defcfn p-try-accept  "accept"      [:int :pointer :pointer] :int)
(ffi/defcfn p-connect-with-error "connect" [:int :pointer :uint] :int
  {:blocking true :capture-native-error true})
(ffi/defcfn p-try-connect "connect"     [:int :pointer :uint] :int)
(ffi/defcfn p-close       "close"       [:int] :int)
(ffi/defcfn p-shutdown    "shutdown"    [:int :int] :int)
(ffi/defcfn p-getsockname "getsockname" [:int :pointer :pointer] :int)
(ffi/defcfn p-getpeername "getpeername" [:int :pointer :pointer] :int)
(ffi/defcfn p-setsockopt  "setsockopt"  [:int :int :int :pointer :uint] :int)
(ffi/defcfn p-getsockopt  "getsockopt"  [:int :int :int :pointer :pointer] :int)
(ffi/defcfn p-recv-with-error "recv" [:int :pointer :size_t :int] :ssize_t
  {:blocking true :capture-native-error true})
(ffi/defcfn p-send-with-error "send" [:int :pointer :size_t :int] :ssize_t
  {:blocking true :capture-native-error true})
(ffi/defcfn p-try-recv    "recv"        [:int :pointer :size_t :int] :ssize_t)
(ffi/defcfn p-try-send    "send"        [:int :pointer :size_t :int] :ssize_t)
;; POSIX readiness support. fcntl is variadic in C. Its third argument remains
;; typed :int for this bounded F_GETFL/F_SETFL surface, but the ABI must still
;; name the two-fixed-argument boundary: Apple arm64 places `...` arguments on
;; the stack even when a fixed third argument would have occupied a register.
(ffi/defcfn p-fcntl       "fcntl"       [:int :int :int] :int
  {:varargs-after 2})
(ffi/defcfn p-pipe        "pipe"        [:pointer] :int)
(ffi/defcfn p-poll-size-with-error "poll" [:pointer :size_t :int] :int
  {:blocking true :capture-native-error true})
(ffi/defcfn p-poll-uint-with-error "poll" [:pointer :uint :int] :int
  {:blocking true :capture-native-error true})
(ffi/defcfn p-read        "read"        [:int :pointer :size_t] :ssize_t)
(ffi/defcfn p-write       "write"       [:int :pointer :size_t] :ssize_t)

;; --- Winsock ----------------------------------------------------------------
;; SOCKET is :uptr, and socklen is int rather than socklen_t.
(ffi/defcfn w-socket      "socket"      [:int :int :int] :uptr)
(ffi/defcfn w-bind        "bind"        [:uptr :pointer :int] :int)
(ffi/defcfn w-listen      "listen"      [:uptr :int] :int)
(ffi/defcfn w-accept-with-error "accept" [:uptr :pointer :pointer] :uptr
  {:blocking true :capture-native-error true})
(ffi/defcfn w-connect-with-error "connect" [:uptr :pointer :int] :int
  {:blocking true :capture-native-error true})
(ffi/defcfn w-close       "closesocket" [:uptr] :int)
(ffi/defcfn w-shutdown    "shutdown"    [:uptr :int] :int)
(ffi/defcfn w-getsockname "getsockname" [:uptr :pointer :pointer] :int)
(ffi/defcfn w-getpeername "getpeername" [:uptr :pointer :pointer] :int)
(ffi/defcfn w-setsockopt  "setsockopt"  [:uptr :int :int :pointer :int] :int)
(ffi/defcfn w-recv-with-error "recv" [:uptr :pointer :int :int] :int
  {:blocking true :capture-native-error true})
(ffi/defcfn w-send-with-error "send" [:uptr :pointer :int :int] :int
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
    {:socket w-socket :bind w-bind :listen w-listen
     :close w-close :shutdown w-shutdown
     :getsockname w-getsockname :getpeername w-getpeername
     :setsockopt w-setsockopt}
    {:socket p-socket :bind p-bind :listen p-listen
     :try-accept p-try-accept
     :try-connect p-try-connect
     :close p-close :shutdown p-shutdown
     :getsockname p-getsockname :getpeername p-getpeername
     :setsockopt p-setsockopt :getsockopt p-getsockopt
     :try-recv p-try-recv :try-send p-try-send
     :fcntl p-fcntl :pipe p-pipe :read p-read :write p-write}))

(def captured-call
  (if windows?
    {:accept w-accept-with-error
     :connect w-connect-with-error
     :recv w-recv-with-error
     :send w-send-with-error
     :getaddrinfo c-getaddrinfo-with-error}
    {:accept p-accept-with-error
     :connect p-connect-with-error
     :recv p-recv-with-error
     :send p-send-with-error
     :poll p-poll-with-error
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
  "Call a failure-sensitive binding that atomically returns
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
