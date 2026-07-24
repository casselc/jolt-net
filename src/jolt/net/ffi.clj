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
(ffi/defcfn p-accept      "accept"      [:int :pointer :pointer] :int :blocking)
(ffi/defcfn p-try-accept  "accept"      [:int :pointer :pointer] :int)
(ffi/defcfn p-connect     "connect"     [:int :pointer :uint] :int :blocking)
(ffi/defcfn p-try-connect "connect"     [:int :pointer :uint] :int)
(ffi/defcfn p-close       "close"       [:int] :int)
(ffi/defcfn p-shutdown    "shutdown"    [:int :int] :int)
(ffi/defcfn p-getsockname "getsockname" [:int :pointer :pointer] :int)
(ffi/defcfn p-getpeername "getpeername" [:int :pointer :pointer] :int)
(ffi/defcfn p-setsockopt  "setsockopt"  [:int :int :int :pointer :uint] :int)
(ffi/defcfn p-getsockopt  "getsockopt"  [:int :int :int :pointer :pointer] :int)
(ffi/defcfn p-recv        "recv"        [:int :pointer :size_t :int] :ssize_t :blocking)
(ffi/defcfn p-send        "send"        [:int :pointer :size_t :int] :ssize_t :blocking)
(ffi/defcfn p-try-recv    "recv"        [:int :pointer :size_t :int] :ssize_t)
(ffi/defcfn p-try-send    "send"        [:int :pointer :size_t :int] :ssize_t)
;; POSIX readiness support. fcntl is variadic in C. Its third argument remains
;; typed :int for this bounded F_GETFL/F_SETFL surface, but the ABI must still
;; name the two-fixed-argument boundary: Apple arm64 places `...` arguments on
;; the stack even when a fixed third argument would have occupied a register.
(ffi/defcfn p-fcntl       "fcntl"       [:int :int :int] :int
  {:varargs-after 2})
(ffi/defcfn p-pipe        "pipe"        [:pointer] :int)
(ffi/defcfn p-poll-size   "poll"        [:pointer :size_t :int] :int :blocking)
(ffi/defcfn p-poll-uint   "poll"        [:pointer :uint :int] :int :blocking)
(ffi/defcfn p-read        "read"        [:int :pointer :size_t] :ssize_t)
(ffi/defcfn p-write       "write"       [:int :pointer :size_t] :ssize_t)

;; --- Winsock ----------------------------------------------------------------
;; SOCKET is :uptr, and socklen is int rather than socklen_t.
(ffi/defcfn w-socket      "socket"      [:int :int :int] :uptr)
(ffi/defcfn w-bind        "bind"        [:uptr :pointer :int] :int)
(ffi/defcfn w-listen      "listen"      [:uptr :int] :int)
(ffi/defcfn w-accept      "accept"      [:uptr :pointer :pointer] :uptr :blocking)
(ffi/defcfn w-connect     "connect"     [:uptr :pointer :int] :int :blocking)
(ffi/defcfn w-close       "closesocket" [:uptr] :int)
(ffi/defcfn w-shutdown    "shutdown"    [:uptr :int] :int)
(ffi/defcfn w-getsockname "getsockname" [:uptr :pointer :pointer] :int)
(ffi/defcfn w-getpeername "getpeername" [:uptr :pointer :pointer] :int)
(ffi/defcfn w-setsockopt  "setsockopt"  [:uptr :int :int :pointer :int] :int)
(ffi/defcfn w-recv        "recv"        [:uptr :pointer :int :int] :int :blocking)
(ffi/defcfn w-send        "send"        [:uptr :pointer :int :int] :int :blocking)
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
(ffi/defcfn c-getaddrinfo  "getaddrinfo"  [:pointer :pointer :pointer :pointer] :int :blocking)
(ffi/defcfn c-freeaddrinfo "freeaddrinfo" [:pointer] :void)
;; POSIX only -- Winsock's gai_strerror is a non-thread-safe macro, so on Windows
;; messages come from the static table in jolt.net.error instead.
(ffi/defcfn c-gai-strerror "gai_strerror" [:int] :pointer)

;; --- active call table ------------------------------------------------------
;; Built once from the target descriptor. Resolving the descriptor here means an
;; unsupported target fails at namespace load, before any syscall can be reached.
(def descriptor (t/descriptor))

(def ^:private windows? (= :windows (:platform descriptor)))
(def ^:private p-poll
  (case (:nfds-type descriptor)
    :size_t p-poll-size
    :uint p-poll-uint
    nil))

(def call
  (if windows?
    {:socket w-socket :bind w-bind :listen w-listen :accept w-accept
     :connect w-connect :close w-close :shutdown w-shutdown
     :getsockname w-getsockname :getpeername w-getpeername
     :setsockopt w-setsockopt :recv w-recv :send w-send}
    {:socket p-socket :bind p-bind :listen p-listen :accept p-accept
     :try-accept p-try-accept
     :connect p-connect :try-connect p-try-connect
     :close p-close :shutdown p-shutdown
     :getsockname p-getsockname :getpeername p-getpeername
     :setsockopt p-setsockopt :getsockopt p-getsockopt
     :recv p-recv :send p-send
     :try-recv p-try-recv :try-send p-try-send
     :fcntl p-fcntl :pipe p-pipe :poll p-poll :read p-read :write p-write}))

(defn invoke
  "Call the platform's implementation of `op`. Named ops rather than direct vars
  so no call site has to know which platform it is on."
  [op & args]
  (apply (or (get call op)
             (throw (ex-info (str "jolt.net: no binding for " op)
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
(def ^:private subsystem (atom nil))

(defn ensure-subsystem!
  "Initialize Winsock once. A no-op on POSIX. Returns true when usable."
  []
  (if-not windows?
    true
    (let [s @subsystem]
      (if (some? s)
        s
        (let [buf (ffi/alloc 512)]                 ; WSADATA
          (try
            ;; WSAStartup RETURNS its error code; it does not set the last-error
            ;; slot, so WSAGetLastError must not be consulted here.
            (let [rc (w-wsastartup 0x0202 buf)     ; request 2.2
                  ok (zero? rc)]
              (reset! subsystem ok)
              (when-not ok
                (throw (ex-info "jolt.net: WSAStartup failed"
                                {:jolt.net/kind :unknown
                                 :jolt.net/op :wsa-startup
                                 :jolt.net/code rc
                                 :jolt.net/platform :windows})))
              ok)
            (finally (ffi/free buf))))))))
