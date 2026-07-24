(ns jolt.net.address-test
  "Deterministic address cases.

  These complement the generative properties rather than duplicating them. The
  properties prove ROUND-TRIP (bytes survive parse/render); they deliberately do
  not pin the exact TEXT, because many spellings denote one address. Canonical
  form is a separate contract -- RFC 5952 -- and it needs named examples, since
  a round-trip property passes happily while rendering 0:0 instead of ::."
  (:require [jolt.net.check :as c]
            [jolt.net.address :as addr]
            [jolt.net.target :as t]
            [jolt.ffi :as ffi]))

(defn- canon [s]
  (let [p (addr/parse-ipv6 s)]
    (when p (addr/render-ipv6 (:bytes p) (:scope-id p)))))

(defn run! []
  (c/section "address: RFC 5952 canonical rendering")
  (c/check "all zeros compresses to ::" "::" (canon "0:0:0:0:0:0:0:0"))
  (c/check "loopback is ::1" "::1" (canon "0:0:0:0:0:0:0:1"))
  (c/check "leading zeros are dropped" "2001:db8::1" (canon "2001:0db8:0000:0000:0000:0000:0000:0001"))
  (c/check "hex is lowercase" "2001:db8::abcd" (canon "2001:DB8::ABCD"))
  ;; RFC 5952 4.2.1: a run of ONE zero group must NOT be compressed
  (c/check "a single zero group is not compressed"
           "2001:db8:0:1:1:1:1:1" (canon "2001:db8:0:1:1:1:1:1"))
  ;; RFC 5952 4.2.3: on a tie, the LEFTMOST longest run wins
  (c/check "leftmost of two equal runs is compressed"
           "2001:db8::1:0:0:1" (canon "2001:db8:0:0:1:0:0:1"))
  (c/check "the longest run wins over an earlier shorter one"
           "2001:0:0:1::1" (canon "2001:0:0:1:0:0:0:1"))
  (c/check "v4-mapped renders in mixed notation"
           "::ffff:192.168.0.1" (canon "::ffff:c0a8:1"))
  (c/check "scope id is preserved" "fe80::1%2" (canon "fe80::1%2"))

  (c/section "address: rejections")
  (doseq [[label s] [["too few groups" "1:2:3:4:5:6:7"]
                     ["too many groups" "1:2:3:4:5:6:7:8:9"]
                     ["two :: runs" "1::2::3"]
                     ["triple colon" ":::1"]
                     ["bracketed" "[::1]"]
                     ["group too long" "12345::1"]
                     ["non-hex" "gggg::1"]
                     ["named scope" "fe80::1%eth0"]]]
    (c/check (str "rejects " label) nil (addr/parse-ipv6 s)))
  (doseq [[label s] [["three octets" "1.2.3"]
                     ["five octets" "1.2.3.4.5"]
                     ["octet > 255" "1.2.3.256"]
                     ["empty octet" "1..3.4"]
                     ["non-numeric" "a.b.c.d"]]]
    (c/check (str "rejects IPv4 " label) nil (addr/parse-ipv4 s)))

  (c/section "address: cross-target sockaddr encoding")
  ;; Encode the same endpoint under each target's layout and assert the exact
  ;; bytes. This is the only place the macOS sin_len convention is exercised.
  (let [darwin (t/descriptor {:os :darwin :arch :aarch64 :pointer-bits 64})
        linux (t/descriptor {:os :linux :arch :x86-64 :pointer-bits 64})
        ep {:jolt.net/host "127.0.0.1" :jolt.net/port 8080 :jolt.net/family :inet}]
    (let [p (ffi/alloc 64)]
      (try
        (addr/encode-sockaddr! darwin p ep)
        (c/check "darwin: byte 0 is the struct length (sin_len)" 16 (ffi/read p :uint8 0))
        (c/check "darwin: byte 1 is AF_INET (30-family space, AF_INET is 2)"
                 2 (ffi/read p :uint8 1))
        (c/check "darwin: port is big-endian 8080 -> 0x1F90" [31 144]
                 [(ffi/read p :uint8 2) (ffi/read p :uint8 3)])
        (c/check "darwin: address bytes are 127.0.0.1" [127 0 0 1]
                 (mapv #(ffi/read p :uint8 (+ 4 %)) (range 4)))
        (finally (ffi/free p))))
    (let [p (ffi/alloc 64)]
      (try
        (addr/encode-sockaddr! linux p ep)
        (c/check "linux: family occupies the first two bytes, no sin_len"
                 2 (ffi/read p :uint16 0))
        (c/check "linux: port is big-endian regardless of host endianness" [31 144]
                 [(ffi/read p :uint8 2) (ffi/read p :uint8 3)])
        (finally (ffi/free p))))))
