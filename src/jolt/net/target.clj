(ns jolt.net.target
  "Per-target socket ABI facts: constants, struct layouts, handle width, and the
  error-code tables.

  Pure data and pure functions -- no FFI, no side effects, nothing loaded. That is
  deliberate: it means a Linux test can assert on the Windows or macOS descriptor
  as an ordinary value, without pretending that doing so is native validation.

  FAILS CLOSED. An unrecognized target throws rather than falling back to a
  similar-looking one. Guessing here does not produce a wrong answer, it produces
  a wrong struct offset, and a wrong struct offset is memory corruption. Targets
  are keyed on the [os arch pointer-bits] TUPLE for the same reason -- :os alone
  does not determine a layout.

  Most values here were extracted from the platform's own headers by
  tools/probe-constants.sh; each descriptor records how it was verified under
  :evidence. CI diffs committed probes directly and separately gates explicit
  same-ABI aliases against native Linux/aarch64 and Darwin/x86-64 probes. See
  docs/PLATFORM-COVERAGE.md."
  (:require [clojure.string :as str]))

;; --- Linux ------------------------------------------------------------------
;; :evidence :probed -- tools/probed/linux-x86-64.edn, native cc.
(def ^:private linux-x86-64
  {:platform :posix
   :evidence :probed
   :handle-type :int                 ; POSIX fd
   :socklen-type :uint
   :nfds-type :size_t                ; glibc nfds_t is unsigned long
   ;; POSIX reaches non-blocking mode through fcntl, not ioctlsocket, so these
   ;; Winsock-only widths are absent rather than zero.
   :ioctl-cmd-bytes nil
   :ioctl-arg-bytes nil
   :invalid-handle -1
   :sin-len? false

   :const {:af-unspec 0 :af-inet 2 :af-inet6 10
           :sock-stream 1 :sock-dgram 2
           :ipproto-tcp 6 :ipproto-ipv6 41
           :sol-socket 1 :so-reuseaddr 2 :so-error 4 :so-rcvbuf 8 :so-sndbuf 7
           :tcp-nodelay 1 :ipv6-v6only 26
           :ai-passive 1 :ai-numerichost 4 :ai-numericserv 1024
           :shut-rd 0 :shut-wr 1 :shut-rdwr 2
           ;; Linux suppresses SIGPIPE per send() call; there is no SO_NOSIGPIPE.
           :msg-nosignal 16384 :so-nosigpipe nil
           :o-nonblock 2048 :f-getfl 3 :f-setfl 4 :fionbio nil
           :pollin 1 :pollout 4 :pollerr 8 :pollhup 16 :pollnval 32}

   :layout {:sockaddr-in {:size 16 :family 0 :port 2 :addr 4}
            :sockaddr-in6 {:size 28 :family 0 :port 2 :flowinfo 4 :addr 8 :scope-id 24}
            :sockaddr-storage {:size 128}
            :pollfd {:size 8 :fd 0 :events 4 :revents 6}
            ;; NOTE: Linux orders ai_addr BEFORE ai_canonname; Windows and macOS
            ;; do the reverse. Hardcoding either set is a silent cross-platform bug.
            :addrinfo {:size 48 :flags 0 :family 4 :socktype 8 :protocol 12
                       :addrlen 16 :addr 24 :canonname 32 :next 40
                       :addrlen-type :uint}}

   :errno {:eagain 11 :ewouldblock 11 :einprogress 115
           :econnrefused 111 :econnreset 104 :eaddrinuse 98 :eaddrnotavail 99
           :enetunreach 101 :ehostunreach 113 :etimedout 110
           :eintr 4 :eacces 13 :epipe 32}

   ;; glibc's EAI_* are NEGATIVE; Winsock's are positive. Never compare across.
   :gai {:noname -2 :again -3 :fail -4 :family -6 :service -8 :memory -10
         :system -11 :addrfamily nil}})

;; --- Windows ----------------------------------------------------------------
;; :evidence :probed -- tools/probed/windows-x86-64.edn, checked against a probe
;; compiled and executed on a native Windows runner. The W1/W2 native gates now
;; exercise real Winsock calls against these facts. See docs/PLATFORM-COVERAGE.md.
(def ^:private windows-x86-64
  {:platform :windows
   :evidence :probed
   ;; SOCKET is a pointer-width UNSIGNED handle (probe: 8 bytes), not an int.
   ;; A valid handle may have its high bit set, so `neg?` and an :int return
   ;; type are both invalid tests -- only equality with INVALID_SOCKET works.
   :handle-type :uptr
   :socklen-type :int
   :nfds-type nil
   ;; ioctlsocket(SOCKET, long cmd, u_long *argp). Probed, because NEITHER type
   ;; is pointer-width here: both are 32 bits even on Win64. A wider little-endian
   ;; cell containing 1 might happen to expose the same first four bytes, but it
   ;; would not establish the declared ABI. Exact sizing makes the initialized
   ;; u_long value and its bounds explicit instead of relying on that accident.
   :ioctl-cmd-bytes 4
   :ioctl-arg-bytes 4
   :invalid-handle 18446744073709551615  ; (2^64)-1, all bits one
   :sin-len? false

   :const {:af-unspec 0 :af-inet 2 :af-inet6 23
           :sock-stream 1 :sock-dgram 2
           :ipproto-tcp 6 :ipproto-ipv6 41
           :sol-socket 65535 :so-reuseaddr 4 :so-error 4103
           :so-rcvbuf 4098 :so-sndbuf 4097
           :tcp-nodelay 1 :ipv6-v6only 27
           :ai-passive 1 :ai-numerichost 4 :ai-numericserv 8
           :shut-rd 0 :shut-wr 1 :shut-rdwr 2
           ;; Windows has no SIGPIPE, so neither suppression mechanism exists.
           :msg-nosignal nil :so-nosigpipe nil
           ;; non-blocking mode is ioctlsocket(FIONBIO), not fcntl. FIONBIO is
           ;; _IOW('f', 126, u_long) == 0x8004667E, which does not fit a signed
           ;; 32-bit int; the header casts it to long, and this is that signed
           ;; long value -- the exact bits the `long cmd` parameter must carry.
           :o-nonblock nil :f-getfl nil :f-setfl nil
           :fionbio -2147195266
           ;; WSAPoll readiness flags. Same key names as POSIX because the
           ;; poller reads them through this table -- and COMPLETELY different
           ;; values, which is exactly why they are probed per target rather
           ;; than shared. Carrying the POSIX numbers here would request
           ;; POLLERR|POLLHUP (1|2) as interests and silently never ask for
           ;; readable at all.
           :pollin 768 :pollout 16 :pollerr 1 :pollhup 2 :pollnval 4
           ;; The components POLLIN and POLLOUT are built from. Winsock defines
           ;; POLLIN as POLLRDNORM|POLLRDBAND and POLLOUT as POLLWRNORM alone,
           ;; so the composition is recorded as evidence instead of assumed.
           ;; POLLPRI is accepted in `events` but WSAPoll never reports it.
           :pollrdnorm 256 :pollrdband 512 :pollpri 1024
           :pollwrnorm 16 :pollwrband 32}

   ;; int WSAAPI WSAPoll(LPWSAPOLLFD fdArray, ULONG fds, INT timeout).
   ;; `fds` is a 32-bit ULONG -- NOT the pointer-width nfds_t POSIX poll takes --
   ;; and the timeout is a signed INT of milliseconds. Probed by initializing a
   ;; typed function pointer from WSAPoll, which does not compile if the real
   ;; declaration differs. :nfds-type stays nil above because that key means
   ;; POSIX nfds_t, which does not exist here.
   :wsapoll {:fds-bytes 4 :timeout-bytes 4 :result-bytes 4}

   :layout {:sockaddr-in {:size 16 :family 0 :port 2 :addr 4}
            :sockaddr-in6 {:size 28 :family 0 :port 2 :flowinfo 4 :addr 8 :scope-id 24}
            :sockaddr-storage {:size 128}
            ;; WSAPOLLFD is NOT struct pollfd, and is deliberately keyed apart
            ;; from it so no call site can reach a POSIX offset on this target.
            ;; Its first member is a pointer-width SOCKET rather than an int, so
            ;; events/revents land at 8/10 in a 16-byte struct (4 bytes of tail
            ;; padding) instead of 4/6 in an 8-byte one. Writing a POSIX pollfd
            ;; into this array would put the handle and both event words in the
            ;; wrong places -- memory corruption, not a wrong answer.
            :wsapollfd {:size 16 :fd 0 :events 8 :revents 10}
            ;; ai_canonname precedes ai_addr here (the reverse of Linux), and
            ;; ai_addrlen is size_t -- 8 bytes, not socklen_t's 4. jolt.mvn-http
            ;; reads it as :int and only gets away with it because Win64 is
            ;; little-endian and address lengths are small.
            :addrinfo {:size 48 :flags 0 :family 4 :socktype 8 :protocol 12
                       :addrlen 16 :canonname 24 :addr 32 :next 40
                       :addrlen-type :size_t}}

   ;; Winsock does not use errno for sockets; these are WSAE* values.
   :errno {:eagain 10035 :ewouldblock 10035
           ;; a non-blocking connect in flight reports WSAEWOULDBLOCK, not a
           ;; distinct WSAEINPROGRESS (which on Winsock means something else)
           :einprogress 10035
           :econnrefused 10061 :econnreset 10054 :eaddrinuse 10048
           :eaddrnotavail 10049 :enetunreach 10051 :ehostunreach 10065
           :etimedout 10060 :eintr 10004 :eacces 10013 :epipe nil}

   :gai {:noname 11001 :again 11002 :fail 11003 :family 10047 :service 10109
         :memory 8 :system nil :addrfamily nil}})

;; --- Windows aarch64 ----------------------------------------------------------
;; :evidence :probed -- tools/probed/windows-aarch64.edn, produced by compiling
;; tools/probe-constants.c with the NATIVE ARM64 MSVC toolchain and EXECUTING the
;; resulting ARM64 binary on a windows-11-vs2026-arm runner. The same runner then
;; runs the W1/W2/W3/W4 Winsock suites against real loopback sockets.
;;
;; Every fact below is byte-for-byte equal to the Windows x86-64 column, and the
;; table is shared to say so. That equality is a RECORDED OBSERVATION, not the
;; reason this entry exists and not a licence to have copied it: Win64 ARM64 and
;; Win64 x64 are both LLP64 with the same Winsock SDK, so equality is expected --
;; but "expected" is what the probe is for. The entry was added only after a
;; native ARM64 probe produced these numbers, and CI keeps that honest two ways:
;; the tables job byte-compares a freshly regenerated windows-aarch64.edn against
;; the committed one, and the ARM64 runtime job additionally diffs it against
;; windows-x86-64.edn after normalizing only the :arch label, so the equality
;; claim itself is gated rather than asserted.
;;
;; :evidence therefore stays :probed. It is deliberately NOT
;; :inferred-from-windows-x86-64 -- nothing here was inferred, and labelling
;; independently probed facts as inferred would understate the evidence exactly
;; as badly as the reverse would overstate it.
(def ^:private windows-aarch64 windows-x86-64)

;; --- macOS ------------------------------------------------------------------
;; :evidence :probed -- tools/probed/darwin-aarch64.edn, produced by compiling
;; and running tools/probe-constants.c on a macOS arm64 CI runner.
;;
;; The two entries share one table because these constants and layouts are SDK
;; facts rather than architecture facts on macOS. CI still probes both native
;; architectures independently: the x86-64 job uploads its distinct evidence
;; and gates this alias by diffing every fact after normalizing only :arch.
(def ^:private darwin
  {:platform :posix
   :evidence :probed
   :handle-type :int
   :socklen-type :uint
   :nfds-type :uint                  ; Darwin nfds_t is unsigned int
   ;; fcntl target: the Winsock ioctlsocket widths do not apply.
   :ioctl-cmd-bytes nil
   :ioctl-arg-bytes nil
   :invalid-handle -1
   ;; BSD-derived: sockaddr byte 0 is the struct length and byte 1 the family,
   ;; so sin_family sits at offset 1, not 0.
   :sin-len? true

   :const {:af-unspec 0 :af-inet 2 :af-inet6 30
           :sock-stream 1 :sock-dgram 2
           :ipproto-tcp 6 :ipproto-ipv6 41
           :sol-socket 65535 :so-reuseaddr 4 :so-error 4103
           :so-rcvbuf 4098 :so-sndbuf 4097
           :tcp-nodelay 1 :ipv6-v6only 27
           :ai-passive 1 :ai-numerichost 4 :ai-numericserv 4096
           :shut-rd 0 :shut-wr 1 :shut-rdwr 2
           ;; macOS offers BOTH: MSG_NOSIGNAL per send (0x80000, unlike Linux's
           ;; 0x4000) and SO_NOSIGPIPE per socket. This table originally said
           ;; MSG_NOSIGNAL was absent here -- CI probing a real Mac corrected it.
           :msg-nosignal 524288 :so-nosigpipe 4130
           :o-nonblock 4 :f-getfl 3 :f-setfl 4 :fionbio nil
           :pollin 1 :pollout 4 :pollerr 8 :pollhup 16 :pollnval 32}

   :layout {:sockaddr-in {:size 16 :family 1 :port 2 :addr 4}
            :sockaddr-in6 {:size 28 :family 1 :port 2 :flowinfo 4 :addr 8 :scope-id 24}
            :sockaddr-storage {:size 128}
            :pollfd {:size 8 :fd 0 :events 4 :revents 6}
            :addrinfo {:size 48 :flags 0 :family 4 :socktype 8 :protocol 12
                       :addrlen 16 :canonname 24 :addr 32 :next 40
                       :addrlen-type :uint}}

   :errno {:eagain 35 :ewouldblock 35 :einprogress 36
           :econnrefused 61 :econnreset 54 :eaddrinuse 48 :eaddrnotavail 49
           :enetunreach 51 :ehostunreach 65 :etimedout 60
           :eintr 4 :eacces 13 :epipe 32}

   :gai {:noname 8 :again 2 :fail 4 :family 5 :service 9 :memory 6
         :system 11 :addrfamily 1}})

;; --- selection --------------------------------------------------------------
;; Keyed on the full tuple. Note there is no [:linux :x86 32] or big-endian entry:
;; those fail closed rather than reusing 64-bit layouts.
(def ^:private descriptors
  {[:linux :x86-64 64] linux-x86-64
   ;; Linux/aarch64 shares these facts with x86-64: same kernel UAPI (the
   ;; constants come from asm-generic) and the same LP64 layout. Listed
   ;; explicitly, with that reasoning, rather than reached by an :os fallback --
   ;; the point of failing closed is that no target is matched by accident.
   [:linux :aarch64 64] (assoc linux-x86-64 :evidence :inferred-from-linux-x86-64)
   [:windows :x86-64 64] windows-x86-64
   ;; Independently probed on a native ARM64 Windows runner and found equal to
   ;; the x86-64 column; see the windows-aarch64 comment above for why that is
   ;; recorded as :probed rather than inferred.
   [:windows :aarch64 64] windows-aarch64
   [:darwin :aarch64 64] darwin
   ;; Keep the public evidence label honest until the new native Intel jobs have
   ;; run green and their probe artifact has been reviewed into the baseline.
   [:darwin :x86-64 64] (assoc darwin :evidence :inferred-from-darwin-aarch64)})

;; Upstream Jolt v0.7.1 exposes Chez's exact machine tag. Classify complete
;; tags here beside the ABI tables; fuzzy suffix matching could silently select
;; a false layout for a future target. Threaded and non-threaded tags share the
;; same socket ABI.
(def ^:private machine-targets
  {"a6le"      {:os :linux :arch :x86-64 :pointer-bits 64}
   "ta6le"     {:os :linux :arch :x86-64 :pointer-bits 64}
   "arm64le"   {:os :linux :arch :aarch64 :pointer-bits 64}
   "tarm64le"  {:os :linux :arch :aarch64 :pointer-bits 64}
   "a6nt"      {:os :windows :arch :x86-64 :pointer-bits 64}
   "ta6nt"     {:os :windows :arch :x86-64 :pointer-bits 64}
   "arm64nt"   {:os :windows :arch :aarch64 :pointer-bits 64}
   "tarm64nt"  {:os :windows :arch :aarch64 :pointer-bits 64}
   "a6osx"     {:os :darwin :arch :x86-64 :pointer-bits 64}
   "ta6osx"    {:os :darwin :arch :x86-64 :pointer-bits 64}
   "arm64osx"  {:os :darwin :arch :aarch64 :pointer-bits 64}
   "tarm64osx" {:os :darwin :arch :aarch64 :pointer-bits 64}})

(defn target-for-machine-type
  "Exact jolt-net target facts for one Chez machine tag."
  [machine]
  (or (get machine-targets machine)
      (throw (ex-info (str "jolt.net: unsupported Chez machine type " machine)
                      {:jolt.net/kind :unsupported-target
                       :jolt.net/machine-type machine
                       :jolt.net/supported-machine-types
                       (vec (sort (keys machine-targets)))}))))

(defn current-target
  "The current runtime's exact socket ABI target coordinate."
  []
  (let [machine (jolt.host/machine-type)]
    (assoc (target-for-machine-type machine) :machine-type machine)))

(defn monotonic-nanos
  "The upstream v0.7.1 monotonic clock behind jolt-net deadlines."
  []
  (System/nanoTime))

(defn supported-target?
  "Is `t` an [os arch pointer-bits] map jolt-net has facts for?"
  [t]
  (contains? descriptors [(:os t) (:arch t) (:pointer-bits t)]))

(defn supported-targets
  "The [os arch pointer-bits] tuples jolt-net knows, for diagnostics and tests."
  []
  (vec (sort (keys descriptors))))

(defn descriptor
  "Socket ABI facts for `t`, or for this host when called with no argument.

  Throws :unsupported-target rather than guessing. The message names the observed
  target and lists what is supported, because the actionable fix is either to add
  a probed descriptor or to run on a supported host."
  ([] (descriptor (current-target)))
  ([t]
   (or (get descriptors [(:os t) (:arch t) (:pointer-bits t)])
       (throw (ex-info (str "jolt.net: unsupported target "
                            (:os t) "/" (:arch t) "/" (:pointer-bits t) "-bit")
                       {:jolt.net/kind :unsupported-target
                        :jolt.net/target t
                        :jolt.net/supported (supported-targets)})))))

;; --- accessors --------------------------------------------------------------
;; Call sites read facts through these rather than reaching into the map, so a
;; missing key is an error naming the fact instead of a nil that silently becomes
;; a zero offset or a zero flag.

(defn const [d k]
  (let [v (get-in d [:const k] ::missing)]
    (when (= v ::missing)
      (throw (ex-info (str "jolt.net: no constant " k " for this target")
                      {:jolt.net/kind :unsupported-target :jolt.net/constant k})))
    v))

(defn layout [d struct]
  (or (get-in d [:layout struct])
      (throw (ex-info (str "jolt.net: no layout for " struct " on this target")
                      {:jolt.net/kind :unsupported-target :jolt.net/struct struct}))))

(defn offset [d struct field]
  (let [v (get (layout d struct) field ::missing)]
    (when (= v ::missing)
      (throw (ex-info (str "jolt.net: no offset for " struct "/" field)
                      {:jolt.net/kind :unsupported-target
                       :jolt.net/struct struct :jolt.net/field field})))
    v))

(defn errno-code [d k] (get-in d [:errno k]))
(defn gai-code [d k] (get-in d [:gai k]))

(defn handle-valid?
  "Is `h` a real socket handle on this target?

  On Win64 a SOCKET is pointer-width unsigned and INVALID_SOCKET is all-bits-one,
  so a legitimate handle may have its high bit set. Testing `neg?` there would
  reject valid sockets and accept the invalid one."
  [d h]
  (if (= :windows (:platform d))
    (not= h (:invalid-handle d))
    (not (neg? h))))
