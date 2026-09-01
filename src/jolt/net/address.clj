(ns jolt.net.address
  "Endpoint values, numeric IP text, and the sockaddr codec.

  All pure except for the encode/decode pair, which read and write caller-owned
  memory and allocate nothing.

  Address text is parsed and rendered here in Clojure rather than through
  inet_ntop/getnameinfo. That is a deliberate departure from the design spike
  (recorded in docs/UPSTREAM-NOTES.md): NI_NUMERICHOST is 1 on Linux but 2 on
  macOS and Windows, so flag-based numeric formatting is a per-target fact that
  must be right at every call site. Formatting here instead makes \"endpoint
  reporting never triggers reverse DNS\" a structural property of the code."
  (:require [clojure.string :as str]
            [jolt.ffi :as ffi]
            [jolt.net.target :as t]
            [jolt.net.error :as err]))

;; --- small string helpers (no regex: keep this portable and obvious) --------
(defn- split-on
  "Split `s` on the single character `ch`, keeping empty segments."
  [s ch]
  (loop [i 0 start 0 acc []]
    (cond
      (>= i (count s)) (conj acc (subs s start i))
      (= ch (nth s i)) (recur (inc i) (inc i) (conj acc (subs s start i)))
      :else (recur (inc i) start acc))))

(defn- ascii? [s]
  (every? #(< (int %) 128) s))

(defn- digit? [c] (and (>= (int c) 48) (<= (int c) 57)))

(defn- parse-uint
  "Parse a non-negative decimal integer, or nil. Rejects empty and non-digits so
  a malformed octet cannot silently become 0."
  [s]
  (when (and (seq s) (every? digit? s))
    (reduce (fn [acc c] (+ (* 10 acc) (- (int c) 48))) 0 s)))

(defn- hex-val [c]
  (let [i (int c)]
    (cond (and (>= i 48) (<= i 57)) (- i 48)
          (and (>= i 97) (<= i 102)) (+ 10 (- i 97))
          (and (>= i 65) (<= i 70)) (+ 10 (- i 65))
          :else nil)))

(defn- parse-hex16
  "Parse 1-4 hex digits into a 16-bit value, or nil."
  [s]
  (when (and (seq s) (<= (count s) 4))
    (reduce (fn [acc c] (if-let [v (hex-val c)] (+ (* 16 acc) v) (reduced nil))) 0 s)))

(defn- hex-str [n]
  (let [digits "0123456789abcdef"]
    (if (zero? n)
      "0"
      (loop [n n acc ""]
        (if (zero? n) acc
            (recur (quot n 16) (str (nth digits (rem n 16)) acc)))))))

;; --- IPv4 -------------------------------------------------------------------
(defn parse-ipv4
  "Dotted-quad -> vector of 4 bytes, or nil if not a valid IPv4 literal."
  [s]
  (let [parts (split-on s \.)]
    (when (= 4 (count parts))
      (let [octets (map parse-uint parts)]
        (when (and (every? some? octets) (every? #(<= 0 % 255) octets))
          (vec octets))))))

(defn render-ipv4 [bytes4]
  (str/join "." (map str bytes4)))

;; --- IPv6 -------------------------------------------------------------------
(defn- groups->bytes [groups]
  (vec (mapcat (fn [g] [(bit-and (bit-shift-right g 8) 0xff) (bit-and g 0xff)]) groups)))

(defn- parse-ipv6-side
  "Parse one side of a `::` split into 16-bit groups, allowing a trailing
  dotted-quad (as in ::ffff:127.0.0.1). Returns [groups] or nil."
  [s]
  (if (= "" s)
    []
    (let [parts (split-on s \:)]
      (when-not (some #(= "" %) parts)          ; no empty group outside `::`
        (let [last-part (last parts)]
          (if (and (> (count parts) 0) (some #(= \. %) last-part))
            ;; trailing IPv4 occupies the final two 16-bit groups
            (when-let [v4 (parse-ipv4 last-part)]
              (let [head (map parse-hex16 (butlast parts))]
                (when (every? some? head)
                  (concat head [(+ (* 256 (nth v4 0)) (nth v4 1))
                                (+ (* 256 (nth v4 2)) (nth v4 3))]))))
            (let [gs (map parse-hex16 parts)]
              (when (every? some? gs) gs))))))))

(defn parse-ipv6
  "IPv6 literal -> {:bytes <16 bytes> :scope-id <int>} or nil.

  Accepts an optional %scope suffix. A numeric scope is kept; a named interface
  (%eth0) cannot be resolved without if_nametoindex and is rejected rather than
  silently dropped, since dropping it would change which interface is used."
  [s]
  (when (and (seq s) (ascii? s))
    (let [pcts (split-on s \%)
          addr (first pcts)
          scope-str (when (= 2 (count pcts)) (second pcts))
          scope (if scope-str (parse-uint scope-str) 0)]
      (when (and (<= (count pcts) 2)
                 (or (nil? scope-str) (some? scope)))
        (let [dbl (str/index-of addr "::")]
          (when-let [groups
                     (if dbl
                       ;; exactly one `::` allowed
                       (when-not (str/index-of addr "::" (inc dbl))
                         (let [l (parse-ipv6-side (subs addr 0 dbl))
                               r (parse-ipv6-side (subs addr (+ dbl 2)))]
                           (when (and l r)
                             (let [fill (- 8 (count l) (count r))]
                               (when (>= fill 1)     ; `::` must cover >=1 group
                                 (concat l (repeat fill 0) r))))))
                       (let [g (parse-ipv6-side addr)]
                         (when (and g (= 8 (count g))) g)))]
            {:bytes (groups->bytes groups) :scope-id scope}))))))

(defn render-ipv6
  "16 bytes -> canonical text: lowercase, no leading zeros, longest zero run
  compressed to `::`, and v4-mapped addresses rendered as ::ffff:a.b.c.d."
  ([bytes16] (render-ipv6 bytes16 0))
  ([bytes16 scope-id]
   (let [groups (vec (map (fn [i] (+ (* 256 (nth bytes16 (* 2 i))) (nth bytes16 (inc (* 2 i)))))
                          (range 8)))
         v4-mapped? (and (every? zero? (subvec groups 0 5)) (= 0xffff (nth groups 5)))
         base
         (if v4-mapped?
           (str "::ffff:" (render-ipv4 (subvec (vec bytes16) 12 16)))
           ;; longest run of >=2 zero groups wins; ties go to the leftmost, which
           ;; is what RFC 5952 requires
           (let [runs (loop [i 0 best [-1 0] cur-start -1 cur-len 0]
                        (if (>= i 8)
                          (if (> cur-len (second best)) [cur-start cur-len] best)
                          (if (zero? (nth groups i))
                            (recur (inc i) best (if (neg? cur-start) i cur-start) (inc cur-len))
                            (recur (inc i)
                                   (if (> cur-len (second best)) [cur-start cur-len] best)
                                   -1 0))))
                 [rs rl] runs]
             (if (>= rl 2)
               (str (str/join ":" (map hex-str (subvec groups 0 rs)))
                    "::"
                    (str/join ":" (map hex-str (subvec groups (+ rs rl) 8))))
               (str/join ":" (map hex-str groups)))))]
     (if (pos? scope-id) (str base "%" scope-id) base))))

;; --- endpoints --------------------------------------------------------------
(defn endpoint
  "An endpoint value. `host` nil means wildcard (AI_PASSIVE for a listener).

  Validation happens HERE, before any native call, so a bad host never reaches
  the resolver. v1 accepts ASCII DNS names (including caller-supplied IDNA
  A-labels) and numeric IPv4/IPv6. Other Unicode is rejected structurally rather
  than being handed to Windows' ANSI getaddrinfo, which would make behavior
  depend on the platform's active code page."
  ([host port] (endpoint host port {}))
  ([host port opts]
   (when-not (and (integer? port) (<= 0 port 65535))
     (throw (err/invalid-ex :endpoint "port must be an integer in 0..65535"
                            {:jolt.net/port port})))
   (when (and (some? host) (not (string? host)))
     (throw (err/invalid-ex :endpoint "host must be a string or nil"
                            {:jolt.net/host host})))
   (when (and (some? host) (not (ascii? host)))
     (throw (err/invalid-ex :endpoint
                            "host must be ASCII; supply an IDNA A-label for an international name"
                            {:jolt.net/host host})))
   ;; A bracketed literal is a URI artifact, not address data. Rejected loudly so
   ;; the caller strips it at the URI layer instead of it reaching the resolver.
   (when (and (some? host) (str/starts-with? host "["))
     (throw (err/invalid-ex :endpoint
                            "host must be unbracketed address data (\"::1\", not \"[::1]\")"
                            {:jolt.net/host host})))
   (let [family (cond
                  (nil? host) (or (:family opts) :unspecified)
                  (parse-ipv4 host) :inet
                  (parse-ipv6 host) :inet6
                  :else (or (:family opts) :unspecified))]
     (when (and (:family opts) (not= (:family opts) family)
                (some? host) (or (parse-ipv4 host) (parse-ipv6 host)))
       (throw (err/invalid-ex :endpoint
                              "requested :family contradicts the numeric literal"
                              {:jolt.net/host host :jolt.net/family (:family opts)})))
     {:jolt.net/host host
      :jolt.net/port port
      :jolt.net/family family})))

(defn numeric-host?
  "Is this host an IP literal (so resolution can use AI_NUMERICHOST)?"
  [host]
  (boolean (and host (or (parse-ipv4 host) (parse-ipv6 host)))))

;; --- sockaddr codec ---------------------------------------------------------
;; sin_port and sin6_port are NETWORK byte order by definition, so they are
;; written byte-wise: that is endian-independent and needs no target fact.
;; sin_family is HOST order, and sin6_scope_id is HOST order too (unlike
;; sin6_flowinfo, which is network order) -- the easiest field here to get wrong.

(defn- put-be16! [p off v]
  (ffi/write p :uint8 (bit-and (bit-shift-right v 8) 0xff) off)
  (ffi/write p :uint8 (bit-and v 0xff) (inc off)))

(defn- get-be16 [p off]
  (+ (* 256 (ffi/read p :uint8 off)) (ffi/read p :uint8 (inc off))))

(defn- put-family! [d p base family-const]
  (let [lay-in (t/layout d :sockaddr-in)]
    (if (:sin-len? d)
      ;; BSD: byte 0 is the struct length, byte 1 the family. Both fit in a byte.
      (do (ffi/write p :uint8 (:size lay-in) base)
          (ffi/write p :uint8 family-const (inc base)))
      ;; 16-bit host-order family at offset 0
      (ffi/write p :uint16 family-const base))))

(defn encode-sockaddr!
  "Write `resolved` (or an endpoint with a numeric host) into caller-owned memory
  at `p`. Returns the number of bytes written. Zeroes the struct first so no
  padding byte is left holding stale data."
  [d p resolved]
  (let [family (:jolt.net/family resolved)
        port (:jolt.net/port resolved)
        host (:jolt.net/host resolved)]
    (case family
      :inet
      (let [lay (t/layout d :sockaddr-in)
            v4 (parse-ipv4 (or host "0.0.0.0"))]
        (dotimes [i (:size lay)] (ffi/write p :uint8 0 i))
        (put-family! d p 0 (t/const d :af-inet))
        (put-be16! p (:port lay) port)
        (dotimes [i 4] (ffi/write p :uint8 (nth v4 i) (+ (:addr lay) i)))
        (:size lay))

      :inet6
      (let [lay (t/layout d :sockaddr-in6)
            parsed (parse-ipv6 (or host "::"))
            b (:bytes parsed)]
        (dotimes [i (:size lay)] (ffi/write p :uint8 0 i))
        (put-family! d p 0 (t/const d :af-inet6))
        (put-be16! p (:port lay) port)
        (dotimes [i 16] (ffi/write p :uint8 (nth b i) (+ (:addr lay) i)))
        ;; host byte order
        (ffi/write p :uint
                   (or (:jolt.net/scope-id resolved) (:scope-id parsed) 0)
                   (:scope-id lay))
        (:size lay))

      (throw (err/invalid-ex :encode-sockaddr
                             (str "cannot encode family " family)
                             {:jolt.net/family family})))))

(defn decode-sockaddr
  "Read a sockaddr at `p` into a resolved-address value. Copies every field it
  needs, so the result stays valid after `p` is freed."
  [d p]
  (let [fam-off (if (:sin-len? d) 1 0)
        family-const (if (:sin-len? d)
                       (ffi/read p :uint8 1)
                       (ffi/read p :uint16 0))]
    (cond
      (= family-const (t/const d :af-inet))
      (let [lay (t/layout d :sockaddr-in)]
        {:jolt.net/family :inet
         :jolt.net/host (render-ipv4 (map #(ffi/read p :uint8 (+ (:addr lay) %)) (range 4)))
         :jolt.net/port (get-be16 p (:port lay))})

      (= family-const (t/const d :af-inet6))
      (let [lay (t/layout d :sockaddr-in6)
            b (vec (map #(ffi/read p :uint8 (+ (:addr lay) %)) (range 16)))
            scope (ffi/read p :uint (:scope-id lay))]
        {:jolt.net/family :inet6
         :jolt.net/host (render-ipv6 b scope)
         :jolt.net/port (get-be16 p (:port lay))
         :jolt.net/scope-id scope
         :jolt.net/flow-info (ffi/read p :uint (:flowinfo lay))})

      :else
      (throw (err/invalid-ex :decode-sockaddr
                             (str "unrecognized address family " family-const)
                             {:jolt.net/family-const family-const})))))

(defn max-sockaddr-size [d] (:size (t/layout d :sockaddr-storage)))
