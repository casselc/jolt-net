# `clojure.platform`: a versioned portability boundary

Status: design only, 2026-07-23. This document does not create namespaces,
rename `jolt-net`, or claim a common implementation already exists.

## Decision

Make the reusable boundary explicit and narrow:

```text
clojure.platform.ffi
        |
clojure.platform.tcp
        |
clojure.platform.http
        |
clojure.raft and clojure.lsm
```

`clojure.platform.ffi` is the only layer allowed to know a runtime's native
calling convention, pointer ownership, errno/error-channel rules, library
selection, or ABI layout. `clojure.platform.tcp` derives its portable contract
from Teensyp's socket work but must expose byte streams, addresses, deadlines,
cancellation, and normalized failures—not file descriptors or `sockaddr`
offsets. `clojure.platform.http` derives its client/server contract from Capra
but consumes TCP/byte-stream effects rather than creating a second native
transport surface.

`clojure.raft` and `clojure.lsm` live above that effect boundary. They receive
explicit clock, durable-store, byte, transport, and cancellation capabilities.
Their state machines, log layout, replication rules, and recovery tests cannot
depend on Jolt FFI, Java classes, native handle widths, or a particular HTTP
client.

## Descriptors negotiate; handler maps execute

An adapter exposes two values:

1. a data-only capability descriptor used for negotiation, diagnostics, and
   fail-fast construction; and
2. a versioned map of operation handlers implementing the advertised SPI.

Versioning is mandatory: absence means an effect is unavailable, while an
incompatible meaning requires a new major capability version rather than a
truthful-looking fallback.

```clojure
{:clojure.platform/capability-version 1
 :runtime {:kind :jolt             ; :jvm, :fake, or :jank
           :implementation "..."}
 :ffi {:version 1
       :available? true
       :target {:os :linux :arch :x86-64 :abi :sysv :pointer-bits 64}
       :scoped-resource? true
       :layouts-verified? true}
 :tcp {:version 1
       :available? true
       :ipv4? true :ipv6? true
       :deadline? true :cancellation? true
       :byte-stream-version 1}
 :http {:version 1
        :available? true
        :client? true :server? false
        :streaming-request? true :streaming-response? true}
 :clock {:version 1 :monotonic? true}
 :storage {:version 1 :durable? false}}
```

The map describes what is actually supported by this adapter, not what the
application hopes to use. For example, a Jolt TCP adapter without verified
Win64 runtime calls must not advertise Windows socket support merely because a
constant table exists. A fake adapter may advertise deterministic TCP and HTTP
semantics while setting `:ffi/available?` false. An unavailable capability must
fail at construction/negotiation with the missing key and required version,
before work begins.

The executable half is deliberately a plain map first:

```clojure
{:clojure.platform.spi/version 1
 :describe (fn [] capability-descriptor)
 :clock {:monotonic-nanos (fn [] ...)}
 :socket {:listen!          (fn [endpoint opts] ...)
          :dial!            (fn [endpoint opts] ...)
          :accept!          (fn [listener] ...)
          :read!            (fn [connection bytes off len] ...)
          :write!           (fn [connection bytes off len] ...)
          :await!           (fn [registrations deadline cancellation] ...)
          :shutdown!        (fn [connection direction] ...)
          :local-endpoint   (fn [connection] ...)
          :peer-endpoint    (fn [connection] ...)
          :close!           (fn [owned] ...)}}
```

A host may wrap this in records or protocols for local ergonomics, but the map
is the bootstrap contract and the conformance suite consumes it directly. A new
Clojure-like therefore needs only to supply the small handler set; the state
machines, buffering, backpressure, HTTP parsing, and higher layers stay in pure
Clojure. Handler arguments and results are portable data plus opaque owned
values. No public contract relies on a host class name.

The socket handler map is the narrow internal seam needed by the portable TCP
engine. On Jolt it is implemented with `clojure.platform.ffi`; on the JVM it may
use NIO, and on Node or CLR their native networking APIs. Those adapters satisfy
the same semantics without pretending that every runtime has a useful general C
FFI. Applications consume the TCP API, not this low-level map.

## Layer contracts

### `clojure.platform.ffi`

This layer owns only native effects and their proof obligations:

- target and library capability discovery, including OS, architecture, ABI,
  pointer width, endianness, and library/symbol version;
- declared struct/union layouts verified against the target headers;
- width-correct scalar types and by-value aggregate rules where supported;
- explicit ownership: caller, callee, borrowed, copied, and scoped cleanup;
- length-aware byte/text conversion and secure-buffer zeroing where required;
- native failure capture before another foreign call can overwrite it.

Raw pointers and C layouts may be used inside a runtime's socket adapter, but
they are never values in the portable socket, TCP, HTTP, Raft, or LSM contract.

### `clojure.platform.tcp`

The contract is portable Teensyp-derived transport, not “BSD sockets with a
different spelling.” A connection is a byte source/sink with explicit close,
half-close where available, bounded reads/writes, deadlines, cancellation, and
normalized error categories. Listener and dial operations use structured
addresses. The adapter may add capability-specific extensions, but portable
callers must negotiate them.

No layer above TCP may infer EOF from a zero-length success, assume one write
drains a buffer, retain a caller byte array after the operation returns, or
observe a native descriptor.

Most of this layer is one pure constructor over the socket and clock handlers:

```clojure
(tcp/runtime {:socket-spi (:socket platform-spi)
              :clock-spi  (:clock platform-spi)})
```

Reactor state, partial-I/O continuation, buffer ownership, lifecycle, and
backpressure live above that seam and are shared unchanged by every adapter.

### `clojure.platform.http`

The Capra-derived layer defines messages, headers, body streaming, cancellation,
deadlines, and normalized response/error semantics. It is free to use TCP,
another runtime-native HTTP implementation, or a fake transport, provided its
advertised capability version and streaming semantics match. HTTP must not
become a new route for FFI calls or platform-specific process/TLS behaviour.

## Runtime gate

Every capability version is accepted only after the same behavioural suite runs
against these adapters:

| Adapter | Required evidence |
| --- | --- |
| Jolt | Real platform calls where claimed; ABI probes are recorded separately from runtime calls. |
| JVM | Standard implementation passes the portable behavioural suite; no Jolt-specific type leaks. |
| Fake | Deterministic virtual time, controllable partial I/O, EOF, cancellation, and failure injection. |
| jank | Independently reports capabilities and passes the same suite; it is not accepted by JVM similarity. |

The minimum suite covers partial reads/writes, EOF and half-close, timeout versus
cancellation, error normalization, ownership after failed operations, request
and response backpressure, and deterministic Raft/LSM replay above the effect
boundary. Platform tests add OS-specific facts; they never weaken the portable
contract.

## Migration rules

1. Keep existing Jolt-specific implementations working while adapters are
   characterized; this document authorizes no replacement.
2. Extract one capability at a time behind versioned maps, beginning with clock
   and byte/ownership semantics, then TCP, then HTTP.
3. Move Raft and LSM only after they run unchanged against Jolt, JVM, and fake
   adapters. Add jank only when it independently satisfies the negotiated map.
4. Reject compatibility shims that erase a semantic difference. A smaller map
   or an explicit unsupported result is preferable to a false portable promise.
