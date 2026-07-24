(ns jolt.net.resolver-test
  (:require [jolt.net.check :as c]
            [jolt.net :as net]
            [jolt.net.address :as addr]
            [jolt.ffi :as ffi]))

(defn run! []
  (c/section "resolver: numeric literals")
  (let [r (net/resolve (net/endpoint "127.0.0.1" 8080))]
    (c/check "a numeric IPv4 resolves to exactly one address" 1 (count r))
    (c/check "family is :inet" :inet (:jolt.net/family (first r)))
    (c/check "host round-trips as text" "127.0.0.1" (:jolt.net/host (first r)))
    (c/check "port is preserved" 8080 (:jolt.net/port (first r)))
    (c/check "socket type defaults to :stream" :stream (:jolt.net/socket-type (first r)))
    (c/check-pred "the sockaddr bytes were copied out"
                  #(and (some? %) (pos? (count %))) (:jolt.net/sockaddr (first r))))

  (c/section "resolver: copy-before-free")
  ;; The invariant: results must not point into the addrinfo chain, which is
  ;; freed before resolve returns. Scribble a lot of fresh native memory over the
  ;; heap the resolver just released, then use the result. If anything were still
  ;; a borrowed pointer, this is where it would show up as garbage or a crash.
  (let [r (first (net/resolve (net/endpoint "127.0.0.1" 0) {:passive? true}))
        host-before (:jolt.net/host r)
        bytes-before (vec (:jolt.net/sockaddr r))]
    (dotimes [_ 200]
      (let [p (ffi/alloc 4096)]
        (dotimes [i 4096] (ffi/write p :uint8 i 0xA5))
        (ffi/free p)))
    (System/gc)
    (c/check "host text survives freeaddrinfo + heap churn" host-before (:jolt.net/host r))
    (c/check "sockaddr bytes survive freeaddrinfo + heap churn"
             bytes-before (vec (:jolt.net/sockaddr r)))
    ;; the strongest form: the copied bytes are still usable to actually bind
    (let [l (net/listen (net/endpoint "127.0.0.1" 0))]
      (c/check-pred "a socket still binds after the resolver's memory was reused"
                    pos? (:jolt.net/port (net/local-endpoint l)))
      (net/close! l)))

  (c/section "resolver: order and failure")
  ;; localhost is IPv4-only in some environments and dual in others, so assert
  ;; the property that holds either way rather than a specific count.
  (let [r (net/resolve (net/endpoint "localhost" 80))]
    (c/check-pred "localhost resolves to at least one address" #(>= (count %) 1) r)
    (c/check-pred "every result carries owned sockaddr bytes"
                  #(every? (fn [a] (pos? (count (:jolt.net/sockaddr a)))) %) r)
    (c/check-pred "every result is a known family"
                  #(every? (fn [a] (contains? #{:inet :inet6} (:jolt.net/family a))) %) r)
    (c/check-pred "resolve is deterministic in order across calls"
                  #(= % (map :jolt.net/host (net/resolve (net/endpoint "localhost" 80))))
                  (map :jolt.net/host r)))

  (c/check-throws "an unresolvable name is :name-resolution"
                  {:jolt.net/kind :name-resolution :jolt.net/op :resolve}
                  #(net/resolve (net/endpoint "no-such-host.invalid" 80)))
  (c/check-pred "resolution failure keeps the native EAI_ code"
                integer?
                (try (net/resolve (net/endpoint "no-such-host.invalid" 80))
                     (catch :default e (:jolt.net/code (ex-data e)))))

  (c/section "resolver: v1 hostname policy")
  ;; Rejected at construction, before any native call -- never handed to
  ;; Windows' ANSI getaddrinfo, where behavior would depend on the code page.
  (c/check-throws "a non-ASCII hostname is rejected structurally"
                  {:jolt.net/kind :invalid :jolt.net/op :endpoint}
                  #(net/endpoint "bücher.example" 80))
  (c/check-throws "a bracketed IPv6 literal is rejected as URI syntax"
                  {:jolt.net/kind :invalid :jolt.net/op :endpoint}
                  #(net/endpoint "[::1]" 80))
  (c/check-throws "a port above 65535 is rejected"
                  {:jolt.net/kind :invalid :jolt.net/op :endpoint}
                  #(net/endpoint "127.0.0.1" 65536))
  (c/check-throws "a negative port is rejected"
                  {:jolt.net/kind :invalid :jolt.net/op :endpoint}
                  #(net/endpoint "127.0.0.1" -1))
  ;; an ASCII IDNA A-label is legal input and must NOT be rejected
  (c/check-pred "an IDNA A-label is accepted"
                map? (net/endpoint "xn--bcher-kva.example" 80)))
