(ns jolt.net.error
  "Native error capture, classification, and structured exceptions.

  Two rules define this namespace.

  1. EXPECTED STATES ARE VALUES. would-block, EOF, and connect-in-progress are
     tagged returns, not exceptions. They are ordinary control flow on a socket
     and collapsing them into throws is what forces callers into try/catch in
     their hot path.

  2. CAPTURE PRECEDES CLEANUP. errno is valid only until the NEXT native call --
     and close(), free(), and closesocket() are all native calls. A rollback that
     runs before the read makes the caller report the cleanup's error instead of
     the real one. That is not hypothetical: teensyp.ffi-net's constructors call
     close before reading errno, so a failed bind can surface as whatever close
     set. `checked` below makes the ordering structural rather than a convention
     each call site has to remember."
  (:require [jolt.ffi :as ffi]
            [jolt.net.target :as t]
            [jolt.net.ffi :as nffi]))

(def ^:private d nffi/descriptor)
(def ^:private platform (:platform d))

;; --- tagged expected states -------------------------------------------------
;; Namespaced keywords so they can never be confused with a byte count or nil.
(def would-block ::would-block)
(def eof ::eof)
(def in-progress ::in-progress)
(def connected ::connected)

(defn would-block? [x] (= x ::would-block))
(defn eof? [x] (= x ::eof))
(defn in-progress? [x] (= x ::in-progress))
(defn connected? [x] (= x ::connected))

;; --- capture ----------------------------------------------------------------
(defn capture
  "This thread's last native error code, RIGHT NOW.

  Must be called immediately after the failing call, before anything else --
  jolt.ffi/errno itself makes exactly one native call and no other, but any
  cleanup the caller performs first will have overwritten the value."
  []
  (ffi/errno))

;; --- classification ---------------------------------------------------------
;; The kind set is deliberately small and closed (per the accepted design). It
;; exists so callers can branch on the handful of outcomes worth branching on;
;; anything else is :unknown WITH ITS NATIVE CODE PRESERVED, which is strictly
;; more useful than inventing a kind per errno.
(def ^:private kind-by-errno-key
  {:econnrefused :connection-refused
   :econnreset :connection-reset
   ;; a write to a peer that is gone is the same event as a reset, from the
   ;; caller's point of view
   :epipe :connection-reset
   :eaddrinuse :address-in-use
   :enetunreach :unreachable
   :ehostunreach :unreachable
   ;; "cannot assign requested address" -- binding an address this host does not
   ;; have, or connecting to one with no route
   :eaddrnotavail :unreachable
   :etimedout :timed-out
   :eintr :interrupted})

;; code -> kind, built once by inverting the target's errno table. Built from the
;; descriptor rather than hardcoded because the numbers differ per platform
;; (ECONNREFUSED is 111 on Linux, 61 on macOS, and 10061 on Winsock).
(def ^:private code->kind
  (reduce (fn [m [errno-key kind]]
            (if-let [code (t/errno-code d errno-key)]
              (assoc m code kind)
              m))
          {} kind-by-errno-key))

(defn kind-of
  "Classify a native error code. :unknown is a normal outcome, not a failure of
  this function -- the code is always retained by the caller regardless."
  [code]
  (get code->kind code :unknown))

(def ^:private messages
  {:connection-refused "Connection refused"
   :connection-reset "Connection reset by peer"
   :address-in-use "Address already in use"
   :unreachable "Network or host unreachable"
   :timed-out "Operation timed out"
   :interrupted "Interrupted"
   :cancelled "Cancelled"
   :name-resolution "Name resolution failed"
   :invalid "Invalid argument"
   :unsupported-target "Unsupported target"
   :unknown "Native error"})

;; strerror is deliberately not called: it is not thread-safe, and strerror_r has
;; incompatible GNU and XSI signatures that cannot both be bound from one
;; declaration. The native code is always preserved, so nothing is lost.
(defn- message-for [kind code]
  (str (get messages kind "Native error") " (code " code ")"))

;; --- structured exceptions --------------------------------------------------
(defn native-ex
  "An ExceptionInfo for a failed native call. `code` must already have been
  captured -- this function makes no native call, precisely so it cannot clobber
  the error it is reporting."
  ([op code] (native-ex op code nil))
  ([op code ctx]
   (let [kind (kind-of code)]
     (ex-info (str "jolt.net " (name op) ": " (message-for kind code))
              (merge {:jolt.net/op op
                      :jolt.net/kind kind
                      :jolt.net/code code
                      :jolt.net/platform platform
                      :jolt.net/message (message-for kind code)}
                     ctx)))))

(defn invalid-ex
  "A caller-input error, raised before any native call is attempted."
  [op msg ctx]
  (ex-info (str "jolt.net " (name op) ": " msg)
           (merge {:jolt.net/op op
                   :jolt.net/kind :invalid
                   :jolt.net/platform platform
                   :jolt.net/message msg}
                  ctx)))

;; --- the ordering combinator ------------------------------------------------
(defn checked
  "Run `thunk`; if `fail?` says its result is a failure, capture the native error
  IMMEDIATELY and throw.

  The capture is lexically the first thing after the result binding, with nothing
  between them. Callers put rollback in a catch/finally, never in the failure
  branch, so cleanup provably runs after the capture:

      (let [h (checked :socket invalid? #(...) ctx)]
        (try (checked :bind neg? #(...) ctx)
             (transfer-ownership h)
             (catch :default e (raw-close! h) (throw e))))

  `ctx` must be an already-evaluated value, never an expression containing a
  native call -- that would run between the failing call and the capture."
  ([op fail? thunk] (checked op fail? thunk nil))
  ([op fail? thunk ctx]
   (let [r (thunk)]
     (if (fail? r)
       (let [code (capture)]        ;; nothing may be interposed here
         (throw (native-ex op code ctx)))
       r))))

;; --- resolver errors --------------------------------------------------------
;; getaddrinfo returns its code DIRECTLY and does not set errno (except
;; EAI_SYSTEM), so it needs its own path. Note glibc's EAI_* are negative while
;; Winsock's are positive -- never compare a code across platforms.
(defn gai-message [rc]
  (if (= :windows platform)
    (str "Name resolution failed (code " rc ")")
    ;; Safe here: gai_strerror is called only after rc is already a Jolt value,
    ;; so it cannot clobber anything we still need.
    (let [p (nffi/c-gai-strerror rc)]
      (if (ffi/null? p)
        (str "Name resolution failed (code " rc ")")
        (ffi/ptr->string p)))))

(defn gai-ex
  "An ExceptionInfo for a getaddrinfo failure. `sys` is the errno captured at the
  point of failure when rc was EAI_SYSTEM, else nil."
  [rc sys ctx]
  (ex-info (str "jolt.net resolve: " (gai-message rc))
           (merge {:jolt.net/op :resolve
                   :jolt.net/kind :name-resolution
                   :jolt.net/code rc
                   :jolt.net/platform platform
                   :jolt.net/message (gai-message rc)}
                  (when sys {:jolt.net/system-code sys})
                  ctx)))
