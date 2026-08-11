(ns jolt.net.nonblocking
  "The non-blocking transition shared by sockets and poller wake pipes.

  Two mechanisms, selected by the target rather than by the call site:

  POSIX uses fcntl(F_SETFL). F_SETFL reporting success is not enough there: a
  mismatched variadic FFI ABI can return normally without delivering its third
  argument. The flags are read back before any handle is marked non-blocking, so
  a substrate mismatch fails closed instead of parking a supposedly short
  operation lease in accept(2).

  Windows uses ioctlsocket(FIONBIO), which has no counterpart read-back: Winsock
  exposes no portable getter for a socket's blocking mode, and inventing one
  (for example inferring it from a speculative call) would be a worse oracle
  than the value it checks. Production therefore marks after a successful call,
  relying on the probed ABI facts and the documented Winsock FIONBIO contract.
  The native W2 gate then observes accept/recv returning would-block as
  cross-boundary CONFORMANCE evidence. That later observation does not gate each
  handle mark. `postcondition-kind` names the in-process boundary so this trusted
  edge remains visible instead of being implied by a platform branch."
  (:require [jolt.ffi :as ffi]
            [jolt.net.error :as err]
            [jolt.net.ffi :as nffi]
            [jolt.net.target :as t]))

(def ^:private d nffi/descriptor)
(def ^:private windows? (= :windows (:platform d)))

(defn enabled?
  "Whether flags observed through F_GETFL contain this target's O_NONBLOCK bit.
  POSIX only -- Windows has no readable equivalent."
  [flags]
  (not (zero? (bit-and flags (t/const d :o-nonblock)))))

(defn postcondition-kind
  "The in-process boundary used before a handle is marked non-blocking.

    :observed-flag -- POSIX re-reads O_NONBLOCK through F_GETFL in process.
    :call-status   -- Winsock has no portable getter, so a successful
                      ioctlsocket(FIONBIO) return under the probed ABI and OS
                      contract is the only in-process boundary. Native
                      would-block behavior is later conformance evidence, not
                      an admission guard."
  []
  (if windows? :call-status :observed-flag))

(defn- set-raw-posix!
  [raw ctx]
  (let [get-flags #(err/checked-captured
                      :fcntl-getfl neg?
                      (nffi/invoke-captured
                        :fcntl raw (t/const d :f-getfl) 0)
                      ctx)
        before (get-flags)
        desired (bit-or before (t/const d :o-nonblock))]
    (err/checked-captured
      :fcntl-setfl neg?
      (nffi/invoke-captured :fcntl raw (t/const d :f-setfl) desired)
      ctx)
    (let [observed (get-flags)]
      (when-not (enabled? observed)
        (throw
          (ex-info
            "jolt.net fcntl-setfl: O_NONBLOCK was not observable after F_SETFL"
            (merge {:jolt.net/op :fcntl-setfl
                    :jolt.net/kind :unsupported-target
                    :jolt.net/platform (:platform d)
                    :jolt.net/message
                    "O_NONBLOCK was not observable after F_SETFL"
                    :jolt.net/expected-flag (t/const d :o-nonblock)
                    :jolt.net/observed-flags observed}
                   ctx)))))
    raw))

(defn- ioctl-arg-type
  "The foreign type for a u_long argument cell of the probed width.

  Fails closed on any width this code cannot write exactly. A wider
  little-endian cell might expose the same low u_long bytes, but that is
  incidental layout rather than the declared ABI; the reverse mismatch can
  write past the cell. Exact sizing establishes that the whole u_long at argp is
  initialized and within bounds."
  [bytes]
  (case bytes
    4 :uint
    8 :size_t
    (throw (ex-info
             (str "jolt.net ioctlsocket: unsupported u_long width " bytes)
             {:jolt.net/op :ioctlsocket
              :jolt.net/kind :unsupported-target
              :jolt.net/platform (:platform d)
              :jolt.net/width bytes}))))

(defn- set-raw-windows!
  [raw ctx]
  (let [width (:ioctl-arg-bytes d)]
    (when-not (integer? width)
      (throw (ex-info
               "jolt.net ioctlsocket: this target has no probed u_long width"
               (merge {:jolt.net/op :ioctlsocket
                       :jolt.net/kind :unsupported-target
                       :jolt.net/platform (:platform d)}
                      ctx))))
    (let [arg-type (ioctl-arg-type width)
          p (ffi/alloc width)]
      (try
        ;; a NON-ZERO argp enables non-blocking mode
        (ffi/write p arg-type 0 1)
        ;; ioctlsocket returns 0 or SOCKET_ERROR (-1). The :int return makes
        ;; neg? a valid failure test here, unlike the SOCKET-returning calls.
        (err/checked-captured
          :ioctlsocket neg?
          (nffi/invoke-captured :ioctlsocket raw (t/const d :fionbio) p)
          ctx)
        (finally (ffi/free p)))))
  raw)

(defn set-raw!
  "Put raw into non-blocking mode and establish this target's postcondition.

  Returns raw for constructor threading, and throws otherwise -- so a caller
  that marks a handle after this returns can never mark one whose transition
  failed. ctx is merged into any structured native or fail-closed exception."
  ([raw] (set-raw! raw nil))
  ([raw ctx]
   (if windows?
     (set-raw-windows! raw ctx)
     (set-raw-posix! raw ctx))))
