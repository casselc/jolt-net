(ns jolt.net.socket-test
  "Real sockets on real loopback. Everything here executes; nothing is simulated."
  (:require [jolt.net.check :as c]
            [jolt.net :as net]
            [jolt.net.handle :as h]
            [jolt.net.error :as err]))

(defn- ipv6-available?
  "Probe rather than assume: containers routinely lack ::1, and a hard failure
  there would be reporting an environment gap as a jolt-net defect."
  []
  (try
    (let [l (net/listen (net/endpoint "::1" 0))]
      (net/close! l) true)
    (catch :default _ false)))

(defn- round-trip
  "listen -> connect -> accept on `host`, asserting the two sides agree about
  who is who. This is the property that jolt-tcp could not express at all: it
  returned {:local-address nil :remote-address nil}."
  [label host]
  (let [l (net/listen (net/endpoint host 0) {:reuse-address? true})
        local (net/local-endpoint l)
        port (:jolt.net/port local)]
    (try
      (c/check-pred (str label ": port 0 bind reports a real kernel port")
                    #(and (pos? %) (<= % 65535)) port)
      (c/check (str label ": listener bound the requested host") host (:jolt.net/host local))
      (let [client-f (future (net/connect (net/endpoint host port)))
            server (net/accept l)
            client (deref client-f)]
        (try
          (let [s-peer (net/peer-endpoint server)
                s-local (net/local-endpoint server)
                c-local (net/local-endpoint client)
                c-peer (net/peer-endpoint client)]
            ;; the accepted socket's peer IS the client's local address
            (c/check (str label ": server's peer == client's local address")
                     [(:jolt.net/host c-local) (:jolt.net/port c-local)]
                     [(:jolt.net/host s-peer) (:jolt.net/port s-peer)])
            (c/check (str label ": client's peer == the listening port")
                     port (:jolt.net/port c-peer))
            (c/check (str label ": accepted socket is on the listening port")
                     port (:jolt.net/port s-local))
            (c/check (str label ": both sides agree on family")
                     (:jolt.net/family c-peer) (:jolt.net/family s-peer)))
          (finally (net/close! server) (net/close! client))))
      (finally (net/close! l)))))

(defn run! []
  (c/section "sockets: IPv4 loopback")
  (round-trip "ipv4" "127.0.0.1")

  (c/section "sockets: IPv6 loopback")
  (if (ipv6-available?)
    (round-trip "ipv6" "::1")
    (c/skip "ipv6 loopback round trip" "no ::1 on this host"))

  (c/section "sockets: wildcard bind")
  (let [l (net/listen (net/endpoint nil 0) {:reuse-address? true})]
    (try
      (let [e (net/local-endpoint l)]
        (c/check-pred "wildcard bind reports a real port" pos? (:jolt.net/port e))
        ;; connectable via loopback even though bound to the wildcard
        (let [cf (future (net/connect (net/endpoint "127.0.0.1" (:jolt.net/port e))))
              s (net/accept l)
              cl (deref cf)]
          (c/check-pred "wildcard listener accepts a loopback connection"
                        some? (net/peer-endpoint s))
          (net/close! s) (net/close! cl)))
      (finally (net/close! l))))

  (c/section "sockets: structured errors")
  ;; port 1 on loopback: nothing listens there and it is refused immediately
  (c/check-throws "connect to a closed port is :connection-refused"
                  {:jolt.net/kind :connection-refused :jolt.net/op :connect}
                  #(net/connect (net/endpoint "127.0.0.1" 1)))
  (c/check-pred "the refusal carries a real native code"
                #(and (integer? %) (pos? %))
                (try (net/connect (net/endpoint "127.0.0.1" 1))
                     (catch :default e (:jolt.net/code (ex-data e)))))

  ;; binding a port that is already bound, WITHOUT reuse
  (let [l (net/listen (net/endpoint "127.0.0.1" 0))
        port (:jolt.net/port (net/local-endpoint l))]
    (try
      (c/check-throws "double bind is :address-in-use"
                      {:jolt.net/kind :address-in-use :jolt.net/op :bind}
                      #(net/listen (net/endpoint "127.0.0.1" port)))
      ;; THE ordering property: the reported code must be bind's failure, not
      ;; whatever the rollback close() left behind.
      (let [data (try (net/listen (net/endpoint "127.0.0.1" port))
                      (catch :default e (ex-data e)))]
        (c/check "the error names bind, not the rollback close"
                 :bind (:jolt.net/op data))
        (c/check "the captured code is EADDRINUSE, not a cleanup error"
                 (:eaddrinuse (:errno (net/target-descriptor)))
                 (:jolt.net/code data)))
      (finally (net/close! l))))

  (c/section "sockets: ownership and lifecycle")
  (let [l (net/listen (net/endpoint "127.0.0.1" 0))]
    (c/check "close! reports that it performed the close" true (net/close! l))
    (c/check "a second close! is a no-op, not a double close(2)" false (net/close! l))
    (c/check "a third close! is still a no-op" false (net/close! l))
    (c/check "closed? reflects it" true (net/closed? l))
    (c/check-throws "using a closed socket is a clear error"
                    {:jolt.net/kind :invalid}
                    #(net/local-endpoint l)))

  ;; with-open must find and invoke the handle's :close
  (let [captured (atom nil)]
    (with-open [l (net/listen (net/endpoint "127.0.0.1" 0))]
      (reset! captured l))
    (c/check "with-open closes the handle via its :close fn" true (net/closed? @captured)))

  (c/section "sockets: rollback does not leak descriptors")
  ;; Every failed listen allocates a socket and must close it on the way out. If
  ;; it did not, descriptor numbers would climb by one per failure; comparing a
  ;; descriptor before and after 50 failures detects that directly.
  (let [l (net/listen (net/endpoint "127.0.0.1" 0))
        port (:jolt.net/port (net/local-endpoint l))
        before (let [p (net/listen (net/endpoint "127.0.0.1" 0))
                     n (net/native-handle p)]
                 (net/close! p) n)]
    (try
      (dotimes [_ 50]
        (try (net/listen (net/endpoint "127.0.0.1" port)) (catch :default _ nil)))
      (let [after (let [p (net/listen (net/endpoint "127.0.0.1" 0))
                        n (net/native-handle p)]
                    (net/close! p) n)]
        (c/check-pred (str "50 failed binds leak no descriptors (before " before
                           ", after " after ")")
                      #(< % 10) (abs (- after before))))
      (finally (net/close! l)))))
