(ns jolt.net.readiness
  "The native readiness wait, factored out of the poller as a per-target backend.

  This namespace owns exactly one thing: turning a list of (raw handle,
  interests) entries into per-entry event bits by way of one native call. It
  owns no registration, no token, no generation, no revision, no deadline, and
  no lifecycle. Those stay in jolt.net.poller, which is the single shared state
  machine both targets run; a second one would be a second set of bugs.

  Two backends:

    :posix-poll     poll(2) over struct pollfd
    :windows-wsapoll  WSAPoll over WSAPOLLFD

  The two structs are NOT interchangeable and are keyed apart in the descriptor
  for that reason. WSAPOLLFD leads with a pointer-width SOCKET, so its event
  words sit at offsets 8 and 10 of a 16-byte struct where POSIX puts them at 4
  and 6 of an 8-byte one. The readiness flag VALUES are unrelated too -- Winsock
  POLLIN is 768 where POSIX POLLIN is 1 -- so every constant is read from the
  probed per-target table rather than shared. Writing one platform's layout into
  the other's array is memory corruption, not a wrong answer.

  Event normalization is uniform because the flag values come from the table:
  read/write/error/hangup mean the same thing to a caller on both targets. The
  places where Winsock genuinely differs from POSIX in BEHAVIOR rather than in
  numbering are recorded in docs/PLATFORM-COVERAGE.md and exercised by the W3
  gate; see `wait!` for the ones this code has to defend against directly."
  (:require [jolt.ffi :as ffi]
            [jolt.net.error :as err]
            [jolt.net.ffi :as nffi]
            [jolt.net.target :as t]
            [jolt.net.wake :as wake]))

(def ^:private d nffi/descriptor)
(def ^:private windows? (= :windows (:platform d)))

(def max-native-wait-ms
  "Ceiling on any single native wait. A missed platform wake therefore costs at
  most this much latency instead of making close unbounded."
  1000)

(def backend
  "This target's readiness backend. Selected once, at load, from the probed
  descriptor -- so an unsupported target fails here rather than at the first
  native wait.

    :op          the captured-call key this backend invokes
    :layout      that platform's poll-struct layout, under its own key
    :fd-type     the foreign type of the struct's handle member
    :event-type  the foreign type of its events/revents members
    :wake-transport
                 the wake mechanism available BENEATH this backend, named by
                 jolt.net.wake rather than re-derived here. POSIX has the
                 self-pipe; Windows has the connected loopback datagram pair
                 added in task W4. The two are NOT interchangeable in their
                 terminal semantics -- see jolt.net.wake/terminal-wake -- so the
                 kind is carried, never flattened to a boolean."
  (if windows?
    {:kind :windows-wsapoll
     :op :wsapoll
     :struct :wsapollfd
     :layout (t/layout d :wsapollfd)
     ;; SOCKET, not int. A valid handle may have its high bit set.
     :fd-type :uptr
     :event-type :int16
     :wake-transport wake/kind}
    {:kind :posix-poll
     :op :poll
     :struct :pollfd
     :layout (t/layout d :pollfd)
     :fd-type :int
     :event-type :int16
     :wake-transport wake/kind}))

(def entry-size (:size (:layout backend)))

(defn wake-transport
  "The wake mechanism available beneath this backend, or nil when the target
  has none. Callers must treat nil as a hard capability boundary.

  This reports the TARGET's capability. A particular poller value may still have
  been constructed without one -- that is what the internal wake-less Windows
  readiness adapter is -- so `jolt.net.poller/wake-transport?` is the per-poller
  question and this is the per-target one."
  []
  (:wake-transport backend))

;; --- event vocabulary --------------------------------------------------------
;; Both directions read their flag values from the probed table. The key names
;; are shared; the numbers deliberately are not.

(defn interest-mask
  "The `events` word requesting these interests on this target.

  Only readable/writable are ever REQUESTED. Error, hangup, and invalid-handle
  are reported by both platforms whether or not they were asked for, and Winsock
  additionally rejects them as inputs -- so putting them in `events` would be
  both redundant and, on Windows, wrong."
  [interests]
  (bit-or (if (contains? interests :read) (t/const d :pollin) 0)
          (if (contains? interests :write) (t/const d :pollout) 0)))

(defn event-set
  "Normalize a `revents` word into #{:read :write :error :hangup}.

  POLLNVAL folds into :error on both targets: a handle the kernel considers
  invalid is a failure to report, not a distinct state a caller can act on.
  Readable data is never dropped merely because hangup is also set -- a peer
  that sent bytes and then closed must still deliver those bytes."
  [bits]
  (cond-> #{}
    (not (zero? (bit-and bits (t/const d :pollin)))) (conj :read)
    (not (zero? (bit-and bits (t/const d :pollout)))) (conj :write)
    (not (zero? (bit-and bits (t/const d :pollerr)))) (conj :error)
    (not (zero? (bit-and bits (t/const d :pollhup)))) (conj :hangup)
    (not (zero? (bit-and bits (t/const d :pollnval)))) (conj :error)))

;; --- array encoding ----------------------------------------------------------

(defn alloc-entries
  "Native memory for `n` poll structs. The caller frees it."
  [n]
  (ffi/alloc (* entry-size n)))

(defn encode!
  "Write one entry at index `idx`: the handle, the requested events, and a
  zeroed revents.

  revents is cleared explicitly rather than trusted to be zero. Neither platform
  promises to write it on every path -- poll(2) may leave it undefined when the
  call fails -- so a stale word from a previous iteration could otherwise be
  read back as readiness that never happened."
  [buf idx raw interests]
  (let [layout (:layout backend)
        p (+ buf (* entry-size idx))]
    (ffi/write p (:fd-type backend) (:fd layout) raw)
    (ffi/write p (:event-type backend) (:events layout) (interest-mask interests))
    (ffi/write p (:event-type backend) (:revents layout) 0)
    nil))

(defn revents
  "The raw `revents` word of entry `idx`."
  [buf idx]
  (ffi/read (+ buf (* entry-size idx))
            (:event-type backend)
            (:revents (:layout backend))))

(defn events-of
  "The raw `events` word of entry `idx`. Diagnostic: lets a test prove what was
  actually requested of the kernel rather than what the caller meant to request."
  [buf idx]
  (ffi/read (+ buf (* entry-size idx))
            (:event-type backend)
            (:events (:layout backend))))

;; --- the native wait ---------------------------------------------------------

(defn wait!
  "Perform one native readiness wait over `n` encoded entries.

  Returns {:result count} on success or {:result -1 :code native-error} on
  failure, with the error captured atomically by the binding rather than read
  back afterwards.

  `n` must be positive. POSIX poll(2) accepts zero and degenerates into a sleep,
  but WSAPoll rejects an empty array with WSAEINVAL, so an empty wait is
  rejected here on BOTH targets. Letting it through would make the Windows
  backend fail natively where the POSIX one silently slept -- the divergence
  belongs at this seam, named, not at a native call."
  [buf n wait-ms]
  (when-not (and (integer? n) (pos? n))
    (throw (err/invalid-ex
            (:op backend)
            "a readiness wait needs at least one entry"
            {:jolt.net/count n
             :jolt.net/backend (:kind backend)})))
  (let [[result code] (nffi/invoke-captured (:op backend) buf n wait-ms)]
    (if (neg? result)
      {:result result :code code}
      {:result result})))
