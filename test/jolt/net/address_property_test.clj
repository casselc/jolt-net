(ns jolt.net.address-property-test
  "Generative properties for the address codec, via jolt-hegel.

  This is where generation earns its keep. The codec is byte and index
  arithmetic over three different struct layouts, which is exactly the shape of
  code where hand-picked examples pass and off-by-ones survive: `::1` and
  `127.0.0.1` work long after a zero-run compression bug or a wrong family offset
  has been introduced.

  The sockaddr properties run against ALL THREE target descriptors, including
  Windows and macOS, from Linux. That is honest coverage of the codec -- the
  encoder and decoder are pure functions of a descriptor -- and it is the only
  way the macOS sin_len path gets exercised at all. It proves the codec, not the
  platform; see docs/PLATFORM-COVERAGE.md."
  (:require [clojure.test :refer [deftest is]]
            [hegel.clojure-test :refer [with]]
            [hegel.generator :as g]
            [jolt.ffi :as ffi]
            [jolt.net.address :as addr]
            [jolt.net.target :as t]))

(def ^:private opts {:test-cases 200 :database "" :verbosity :quiet})

(def ^:private descriptors
  {:linux (t/descriptor {:os :linux :arch :x86-64 :pointer-bits 64})
   :windows (t/descriptor {:os :windows :arch :x86-64 :pointer-bits 64})
   :darwin (t/descriptor {:os :darwin :arch :aarch64 :pointer-bits 64})})

;; --- IPv4 -------------------------------------------------------------------
(deftest ipv4-text-round-trips
  (with (assoc opts :name "address/ipv4-round-trip")
        [s (g/ipv4)]
        (let [parsed (addr/parse-ipv4 s)]
          (is (some? parsed) (str "failed to parse generated IPv4: " s))
          (is (= 4 (count parsed)))
          (is (every? #(<= 0 % 255) parsed))
          ;; dotted-quad from the generator is already canonical
          (is (= s (addr/render-ipv4 parsed))))))

;; --- IPv6 -------------------------------------------------------------------
;; Text is NOT asserted to round-trip verbatim: many spellings denote the same
;; address, and render emits the RFC 5952 canonical form. The invariant that
;; must hold is that the BYTES are stable, and that re-rendering is idempotent.
(deftest ipv6-bytes-round-trip
  (with (assoc opts :name "address/ipv6-round-trip")
        [s (g/ipv6)]
        (let [p (addr/parse-ipv6 s)]
          (is (some? p) (str "failed to parse generated IPv6: " s))
          (is (= 16 (count (:bytes p))))
          (let [rendered (addr/render-ipv6 (:bytes p))
                reparsed (addr/parse-ipv6 rendered)]
            (is (some? reparsed) (str "canonical form did not re-parse: " rendered))
            (is (= (:bytes p) (:bytes reparsed))
                (str "bytes changed through render: " s " -> " rendered))
            ;; rendering is a fixed point: canonical text renders to itself
            (is (= rendered (addr/render-ipv6 (:bytes reparsed))))))))

;; --- sockaddr codec, every layout ------------------------------------------
(deftest sockaddr-in-round-trips-on-every-target
  (with (assoc opts :name "address/sockaddr-in")
        [host (g/ipv4)
         port (g/integer 0 65535)
         os (g/sampled-from [:linux :windows :darwin])]
        (let [d (get descriptors os)
              ep {:jolt.net/host host :jolt.net/port port :jolt.net/family :inet}
              p (ffi/alloc 128)]
          (try
            (let [n (addr/encode-sockaddr! d p ep)
                  back (addr/decode-sockaddr d p)]
              (is (= 16 n) (str "sockaddr_in should be 16 bytes on " os))
              (is (= host (:jolt.net/host back)) (str "host round-trip failed on " os))
              (is (= port (:jolt.net/port back)) (str "port round-trip failed on " os))
              (is (= :inet (:jolt.net/family back)))
              ;; sin_port is network byte order by definition, so the two port
              ;; bytes must be big-endian REGARDLESS of the target's endianness.
              (let [off (:port (t/layout d :sockaddr-in))]
                (is (= (bit-and (bit-shift-right port 8) 0xff) (ffi/read p :uint8 off))
                    "sin_port high byte must come first (network order)")
                (is (= (bit-and port 0xff) (ffi/read p :uint8 (inc off)))))
              ;; BSD carries the struct length in byte 0; everyone else does not
              (when (:sin-len? d)
                (is (= 16 (ffi/read p :uint8 0)) "BSD sin_len must be the struct size")))
            (finally (ffi/free p))))))

(deftest sockaddr-in6-round-trips-on-every-target
  (with (assoc opts :name "address/sockaddr-in6")
        [host (g/ipv6)
         port (g/integer 0 65535)
         scope (g/integer 0 1000)
         os (g/sampled-from [:linux :windows :darwin])]
        (let [d (get descriptors os)
              canonical (addr/render-ipv6 (:bytes (addr/parse-ipv6 host)))
              ep {:jolt.net/host host :jolt.net/port port :jolt.net/family :inet6
                  :jolt.net/scope-id scope}
              p (ffi/alloc 128)]
          (try
            (let [n (addr/encode-sockaddr! d p ep)
                  back (addr/decode-sockaddr d p)]
              (is (= 28 n) (str "sockaddr_in6 should be 28 bytes on " os))
              (is (= port (:jolt.net/port back)))
              (is (= :inet6 (:jolt.net/family back)))
              (is (= scope (:jolt.net/scope-id back)) "scope id is HOST byte order")
              ;; compare by bytes: decode renders canonically and may differ in text
              (is (= (:bytes (addr/parse-ipv6 canonical))
                     (:bytes (addr/parse-ipv6 (:jolt.net/host back))))
                  (str "address bytes changed through the codec on " os)))
            (finally (ffi/free p))))))

;; --- endpoint construction is total ----------------------------------------
;; For any ASCII string and any in-range port, `endpoint` either returns a
;; well-formed value or throws :invalid. It must never return something
;; malformed and never throw an unclassified error -- callers branch on :kind.
(deftest endpoint-construction-is-total
  (with (assoc opts :name "address/endpoint-total")
        [host (g/string {:codec :ascii :max-size 40})
         port (g/integer 0 65535)]
        (let [r (try {:ok (addr/endpoint host port)}
                     (catch :default e {:ex (ex-data e)}))]
          (if-let [d (:ex r)]
            (is (= :invalid (:jolt.net/kind d))
                (str "endpoint threw a non-:invalid kind for " (pr-str host)))
            (let [ep (:ok r)]
              (is (= port (:jolt.net/port ep)))
              (is (contains? #{:inet :inet6 :unspecified} (:jolt.net/family ep)))
              ;; a host that parsed as a literal must be classified as such
              (when (addr/parse-ipv4 host)
                (is (= :inet (:jolt.net/family ep))))
              (when (and (addr/parse-ipv6 host) (not (addr/parse-ipv4 host)))
                (is (= :inet6 (:jolt.net/family ep)))))))))

;; A port outside 0..65535 must ALWAYS be rejected -- an out-of-range port that
;; slipped through would be silently truncated into the 16-bit sin_port field
;; and connect to the wrong service.
(deftest out-of-range-ports-always-rejected
  (with (assoc opts :name "address/port-range")
        [port (g/integer 65536 1000000)]
        (is (= :invalid
               (try (addr/endpoint "127.0.0.1" port) :no-throw
                    (catch :default e (:jolt.net/kind (ex-data e))))))))
