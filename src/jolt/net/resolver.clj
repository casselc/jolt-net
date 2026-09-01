(ns jolt.net.resolver
  "getaddrinfo, with every result copied into owned Jolt data before the native
  list is freed.

  The invariant that matters: NO NATIVE POINTER ESCAPES THIS NAMESPACE. Callers
  receive plain values whose sockaddr bytes are a Jolt byte-array copy, so a
  resolved address stays valid indefinitely and cannot become a use-after-free.
  jolt.mvn-http reads fields straight off the addrinfo chain while iterating and
  frees it in a finally, which works only because it never returns them.

  Blocking and NOT cancellable. There is no portable way to interrupt an
  in-flight getaddrinfo; a deadline can be applied before and after the call but
  not to it. A cancellable resolver needs either a platform async API or a
  bounded worker pool, neither of which belongs in the socket substrate."
  (:require [jolt.ffi :as ffi]
            [jolt.net.target :as t]
            [jolt.net.ffi :as nffi]
            [jolt.net.error :as err]
            [jolt.net.address :as addr]))

(def ^:private d nffi/descriptor)

(defn- family->const [family]
  (case family
    :inet (t/const d :af-inet)
    :inet6 (t/const d :af-inet6)
    (t/const d :af-unspec)))

(defn- const->family [c]
  (condp = c
    (t/const d :af-inet) :inet
    (t/const d :af-inet6) :inet6
    :unknown))

;; The hints struct is over-allocated and fully zeroed. A too-small hints buffer
;; is a memory smash rather than a catchable error, and the cost of the slack is
;; nothing.
(def ^:private hints-size 128)

(defn resolve
  "Resolve an endpoint to a vector of resolved-address values, in resolver order.

  Options:
    :socket-type  :stream (default) or :dgram
    :passive?     true for a listener -- a nil host then means wildcard

  Resolver order is preserved exactly as returned; Happy Eyeballs and other
  connection policies belong in a connector layer, not here."
  ([ep] (resolve ep {}))
  ([ep opts]
   (let [host (:jolt.net/host ep)
         port (:jolt.net/port ep)
         passive? (boolean (:passive? opts))
         socktype (if (= :dgram (:socket-type opts))
                    (t/const d :sock-dgram)
                    (t/const d :sock-stream))
         node (if (nil? host) ffi/null (ffi/string->ptr host))
         service (ffi/string->ptr (str port))
         hints (ffi/alloc hints-size)
         respp (ffi/alloc (ffi/sizeof :pointer))
         lay (t/layout d :addrinfo)
         ctx {:jolt.net/endpoint ep}]
     (try
       (dotimes [i hints-size] (nffi/write-at! hints :uint8 i 0))
       (nffi/write-at! hints :int (:family lay)
                       (family->const (:jolt.net/family ep)))
       ;; Without ai_socktype the resolver also returns UDP entries, and connect()
       ;; on a datagram socket spuriously "succeeds" -- a real bug jolt.mvn-http
       ;; documents at its own call site.
       (nffi/write-at! hints :int (:socktype lay) socktype)
       (nffi/write-at! hints :int (:flags lay)
                       (bit-or (if passive? (t/const d :ai-passive) 0)
                               ;; skip DNS entirely for a literal
                               (if (addr/numeric-host? host)
                                 (t/const d :ai-numerichost) 0)))
       (nffi/write-at! respp :pointer 0 ffi/null)

       (let [rc (nffi/c-getaddrinfo node service hints respp)]
         (when-not (zero? rc)
           ;; EAI_SYSTEM defers to errno, so capture it immediately -- before the
           ;; finally below runs any ffi/free, which would overwrite it.
           (let [sys (when (= rc (t/gai-code d :system)) (err/capture))]
             (throw (err/gai-ex rc sys ctx))))

         (let [head (ffi/read respp :pointer 0)]
           (try
             (loop [ai head acc []]
               (if (ffi/null? ai)
                 acc
                 (let [fam (ffi/read ai :int (:family lay))
                       stype (ffi/read ai :int (:socktype lay))
                       proto (ffi/read ai :int (:protocol lay))
                       alen (ffi/read ai (:addrlen-type lay) (:addrlen lay))
                       sa (ffi/read ai :pointer (:addr lay))
                       ;; decode + copy the raw bytes BEFORE moving on; after
                       ;; freeaddrinfo none of these pointers is readable
                       decoded (addr/decode-sockaddr d sa)
                       raw (ffi/read-array sa alen)]
                   (recur (ffi/read ai :pointer (:next lay))
                          (conj acc (merge decoded
                                           {:jolt.net/socket-type (if (= stype (t/const d :sock-dgram))
                                                                    :dgram :stream)
                                            :jolt.net/protocol proto
                                            :jolt.net/family (const->family fam)
                                            :jolt.net/sockaddr raw
                                            :jolt.net/sockaddr-len alen}))))))
             (finally (nffi/c-freeaddrinfo head)))))
       (finally
         (when-not (ffi/null? node) (ffi/free node))
         (ffi/free service)
         (ffi/free hints)
         (ffi/free respp))))))
