(ns jolt.net.target-test
  "Two jobs.

  1. Prove the SELECTION logic: the right descriptor for a target, and a hard
     failure for anything unrecognized.
  2. Prove the CONTENTS are real, by diffing every probed descriptor against
     tools/probed/<os>-<arch>.edn -- the output of compiling
     tools/probe-constants.c against that platform's own headers.

  (2) is what keeps this from being a table of remembered numbers. A descriptor
  marked :probed whose values disagree with the probe is a test failure, not a
  curiosity. Descriptors marked :documented have no probe to check them against;
  that gap is asserted explicitly below so it cannot be forgotten."
  (:require [jolt.net.check :as c]
            [jolt.net.target :as t]
            [clojure.string :as str]))

(defn- project-dir [] (or (jolt.host/getenv "JOLT_PWD") "."))

(defn- read-probe [os arch]
  (let [p (str (project-dir) "/tools/probed/" os "-" arch ".edn")]
    (try (read-string (slurp p))
         (catch :default _ nil))))

;; a descriptor's declared handle width, for comparison with the probe
(defn- handle-bytes [d] (if (= :uptr (:handle-type d)) 8 4))
(defn- socklen-bytes [d]
  (case (:socklen-type d) (:int :uint) 4 :size_t 8 nil))
(defn- addrlen-bytes [d]
  (if (= :size_t (get-in d [:layout :addrinfo :addrlen-type])) 8 4))
(defn- nfds-bytes [d]
  (case (:nfds-type d) :size_t 8 :uint 4 nil))

(defn- diff-map
  "Keys whose values differ, as {k [expected actual]}. Only keys present in the
  probe are compared -- the descriptor may legitimately carry extra facts."
  [probed actual]
  (reduce (fn [acc [k v]]
            (if (= v (get actual k ::absent))
              acc
              (assoc acc k [v (get actual k ::absent)])))
          {} probed))

(defn- check-against-probe [label tuple]
  (let [[os arch _] tuple
        probe (read-probe (name os) (name arch))]
    (if-not probe
      (c/skip (str label " vs probed headers")
              (str "no tools/probed/" (name os) "-" (name arch)
                   ".edn -- run tools/probe-constants.sh"))
      (let [d (t/descriptor {:os os :arch arch :pointer-bits 64})]
        (c/check (str label ": constants match the platform headers")
                 {} (diff-map (:const probe) (:const d)))
        (c/check (str label ": sockaddr_in layout matches")
                 {} (diff-map (get-in probe [:layout :sockaddr-in])
                              (get-in d [:layout :sockaddr-in])))
        (c/check (str label ": sockaddr_in6 layout matches")
                 {} (diff-map (get-in probe [:layout :sockaddr-in6])
                              (get-in d [:layout :sockaddr-in6])))
        (when-let [pollfd (get-in probe [:layout :pollfd])]
          (c/check (str label ": pollfd layout matches")
                   {} (diff-map pollfd (get-in d [:layout :pollfd]))))
        ;; addrinfo carries the field ORDER difference that bites in practice:
        ;; Linux puts ai_addr before ai_canonname, Windows and macOS the reverse.
        (c/check (str label ": addrinfo layout matches")
                 {} (diff-map (dissoc (get-in probe [:layout :addrinfo]) :addrlen-bytes)
                              (dissoc (get-in d [:layout :addrinfo]) :addrlen-type)))
        (c/check (str label ": ai_addrlen width matches")
                 (get-in probe [:layout :addrinfo :addrlen-bytes]) (addrlen-bytes d))
        (c/check (str label ": sin_len convention matches")
                 (get-in probe [:layout :sin-len?]) (:sin-len? d))
        (c/check (str label ": socket handle width matches")
                 (:socket-handle-bytes probe) (handle-bytes d))
        (c/check (str label ": socket address-length width matches")
                 (:socklen-bytes probe) (socklen-bytes d))
        (c/check (str label ": nfds_t width matches")
                 (:nfds-bytes probe) (nfds-bytes d))
        ;; Winsock-only, and deliberately compared on every platform: the
        ;; interesting assertion on POSIX is that these stay absent, and on
        ;; Win64 that neither is pointer-width despite every other handle-ish
        ;; type there being 8 bytes.
        (c/check (str label ": ioctlsocket command width matches")
                 (:ioctl-cmd-bytes probe) (:ioctl-cmd-bytes d))
        (c/check (str label ": ioctlsocket argument width matches")
                 (:ioctl-arg-bytes probe) (:ioctl-arg-bytes d))
        (c/check (str label ": errno codes match") {} (diff-map (:errno probe) (:errno d)))
        (c/check (str label ": EAI_* codes match") {} (diff-map (:gai probe) (:gai d)))))))

(defn run! []
  (c/section "target: selection and fail-closed behavior")

  (c/check-pred "this host is a supported target" true? (t/supported-target? (jolt.host/target)))
  (c/check-pred "descriptor resolves for this host" map? (t/descriptor))

  ;; Fail closed. Each of these would be a wrong struct offset if guessed.
  (c/check-throws "an unknown os throws rather than guessing"
                  {:jolt.net/kind :unsupported-target}
                  #(t/descriptor {:os :unknown :arch :unknown :pointer-bits 64}))
  (c/check-throws "a 32-bit target is not silently given 64-bit layouts"
                  {:jolt.net/kind :unsupported-target}
                  #(t/descriptor {:os :linux :arch :x86-64 :pointer-bits 32}))
  (c/check-throws "an unlisted arch on a known os still fails"
                  {:jolt.net/kind :unsupported-target}
                  #(t/descriptor {:os :linux :arch :riscv64 :pointer-bits 64}))
  (c/check-throws "an unknown constant names the fact instead of returning nil"
                  {:jolt.net/kind :unsupported-target}
                  #(t/const (t/descriptor) :no-such-constant))
  (c/check-throws "an unknown struct offset names the field"
                  {:jolt.net/kind :unsupported-target}
                  #(t/offset (t/descriptor) :sockaddr-in :no-such-field))

  ;; Win64 handle validity, exercised from Linux. This proves the PREDICATE; it
  ;; does not prove the FFI marshals a :uptr result >= 2^63 (see PLATFORM-COVERAGE).
  (c/section "target: Win64 handle validity (predicate only, not native)")
  (let [w (t/descriptor {:os :windows :arch :x86-64 :pointer-bits 64})]
    (c/check "INVALID_SOCKET (all bits one) is rejected"
             false (t/handle-valid? w 18446744073709551615))
    (c/check "a high-bit handle is ACCEPTED (neg? would wrongly reject it)"
             true (t/handle-valid? w 9223372036854775808))
    (c/check "an ordinary small handle is accepted" true (t/handle-valid? w 4))
    (c/check "handle 0 is a valid Windows socket" true (t/handle-valid? w 0))
    (c/check "Windows handles are pointer-width" :uptr (:handle-type w)))
  (let [l (t/descriptor {:os :linux :arch :x86-64 :pointer-bits 64})]
    (c/check "POSIX rejects -1" false (t/handle-valid? l -1))
    (c/check "POSIX accepts fd 0" true (t/handle-valid? l 0)))

  (c/section "target: descriptors vs probed platform headers")
  (check-against-probe "linux/x86-64" [:linux :x86-64 64])
  (check-against-probe "linux/aarch64" [:linux :aarch64 64])
  (check-against-probe "windows/x86-64" [:windows :x86-64 64])
  (check-against-probe "darwin/aarch64" [:darwin :aarch64 64])
  (check-against-probe "darwin/x86-64" [:darwin :x86-64 64])

  (c/section "target: coverage honesty")
  (c/check "macOS is now machine-probed"
           :probed (:evidence (t/descriptor {:os :darwin :arch :aarch64 :pointer-bits 64})))
  (c/check "macOS x86-64 has independent native probe evidence"
           :probed
           (:evidence (t/descriptor {:os :darwin :arch :x86-64 :pointer-bits 64})))
  (c/check "Linux aarch64 has independent native probe evidence"
           :probed
           (:evidence (t/descriptor {:os :linux :arch :aarch64 :pointer-bits 64}))))
