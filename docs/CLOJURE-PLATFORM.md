# `clojure.platform`: a versioned portability boundary

Status: design plus one Jolt composition witness, 2026-07-24. This document does
not create namespaces, rename `jolt-net`, or claim a cross-runtime
implementation already exists.

## Decision

Make the reusable boundary explicit and narrow, without making one data
structure or distributed algorithm the center of the design:

```text
runtime adapters
  ffi/native calls  clock  buffers  files/durable storage
          \           |       |              /
           operation + completion capabilities
                         |
        +----------------+-------------------+
        |                |                   |
  codecs + framing   socket + clock      storage + clock
        |                |                   |
  memory structures   TCP byte streams   disk structures
        |                |                   |
        +--------- composable libraries -----+
                         |
      clients, servers, TLS/HTTP, storage engines,
       communication protocols, and applications
```

`clojure.platform.ffi` is the Jolt adapter's lowest native layer and the only
Jolt code allowed to know C calling conventions, pointer ownership,
errno/error-channel rules, library selection, or ABI layout. Other runtimes do
not have to pretend that a general C FFI is their best primitive: a JVM adapter
may use NIO, Node may use its evented I/O APIs, and CLR may use its native async
surface. They meet at the same operation, completion, ownership, clock, and
storage semantics.

`clojure.platform.tcp` derives its portable contract from Teensyp's socket work
but exposes byte streams, addresses, deadlines, cancellation, and normalized
failures—not file descriptors or `sockaddr` offsets.
`clojure.platform.http` derives its client/server contract from Capra but
consumes TCP/byte-stream effects rather than creating a second native transport
surface.

Everything above those capability seams should be ordinary portable Clojure:
in-memory queues and indexes, disk-backed logs and trees, codecs, framed
protocols, clients and servers, storage engines, replication, and consensus.
Raft and LSM are useful examples of that outcome, not privileged API layers and
not gates in the bootstrap sequence.

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
 :runtime {:kind :jolt             ; :jvm, :node, :clr, :fake, :jank, ...
           :implementation "..."}
 :bytes {:version 1
         :slice? true
         :borrowed-call-scope? true
         :retained-operation-scope? true}
 :ffi {:version 1
       :available? true
       :target {:os :linux :arch :x86-64 :abi :sysv :pointer-bits 64}
       :scoped-resource? true
       :layouts-verified? true}
 :io {:version 1
      :operation-ids? true
      :ordered-completions? true
      :cancellation? true}
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
 :storage {:version 1
           :available? true
           :durable? false
           :atomic-replace? true
           :sync? false}}
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
 :socket {:listen!        (fn [endpoint opts] ...)
          :open!          (fn [address opts] ...)
          :submit!        (fn [{:keys [op-id generation op target
                                       bytes off len]}] ...)
          :cancel!        (fn [op-id generation] ...)
          :completions!   (fn [absolute-deadline cancellation] ...)
          :shutdown!      (fn [connection direction] ...)
          :local-endpoint (fn [connection] ...)
          :peer-endpoint  (fn [connection] ...)
          :close!         (fn [owned] ...)}
 :storage {:open!         (fn [address opts] ...)
           :submit!       (fn [operation] ...)
           :cancel!       (fn [op-id generation] ...)
           :completions!  (fn [absolute-deadline cancellation] ...)
           :close!        (fn [owned] ...)}}
```

A host may wrap this in records or protocols for local ergonomics, but the map
is the bootstrap contract and the conformance suite consumes it directly. A new
Clojure-like therefore needs only to supply the small handler set; the state
machines, buffering, backpressure, codecs, protocol parsing, indexes, and
higher layers stay in pure Clojure. Handler arguments and results are portable
data plus opaque owned values. No public contract relies on a host class name.

The operation/completion shape above is illustrative, not a frozen function
list, but its semantics are load-bearing. Every submitted operation carries a
caller-chosen ID and target generation and produces exactly one terminal
completion. A buffer window has explicit ownership from accepted submission
through that completion; the caller may not mutate or reuse it early.
Cancellation and close define whether the terminal result is completed,
cancelled, or failed, and an old generation can never complete against a new
owner. Completions have an explicit ordering rule rather than inheriting
whatever order one host happens to produce.

Readiness is an adapter detail, not necessarily the portable SPI. Linux may
eventually submit through `io_uring`; Darwin may use
`libdispatch`/`dispatch_io` or kqueue; Windows naturally maps to IOCP and
overlapped Winsock. A `poll` or kqueue adapter can implement the same
operation/completion contract by attempting a non-blocking operation,
registering readiness on `would-block`, and retrying before publishing the
completion. A native completion backend submits directly. Both preserve the
same buffer ownership, operation IDs/generations, cancellation/close semantics,
and completion ordering.

Contiguous byte windows are the required baseline. Scatter/gather is an
optional negotiated extension, not a hidden requirement: the current Chez/Jolt
path has contiguous bytevector I/O but no built-in vectored syscall primitive.
A Jolt adapter can later bind POSIX `readv`/`writev`, while a Windows adapter can
use `WSARecv`/`WSASend`; adapters without either can coalesce or issue
contiguous operations. A vectored completion must report one total byte count
and an unambiguous segment/offset cursor for partial progress. Every submitted
segment remains owned by the operation until its terminal completion, including
on cancellation or failure. Readiness adapters may attempt the vectored syscall
after readiness, and native completion backends may submit it directly, without
changing those portable progress or lifetime rules. This stabilization slice
does not implement or advertise that extension.

The socket handlers are the narrow internal seam needed by the portable TCP
engine. On Jolt they are implemented with `clojure.platform.ffi`; on the JVM
they may use NIO, and on Node or CLR their native networking APIs. Applications
consume the TCP API, not this low-level map.

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

Raw pointers and C layouts may be used inside a runtime adapter, but they are
never values in portable transport, protocol, collection, storage, or
distributed-system contracts.

### `clojure.platform.tcp`

The contract is portable Teensyp-derived transport, not “BSD sockets with a
different spelling.” A connection is a byte source/sink with explicit close,
half-close where available, bounded reads/writes, deadlines, cancellation, and
normalized error categories. Listener and dial operations use structured
addresses. The adapter may add capability-specific extensions, but portable
callers must negotiate them.

No layer above TCP may infer EOF from a zero-length success, assume one write
drains a buffer, reuse a submitted byte window before its terminal completion,
or observe a native descriptor.

Peer half-close is asynchronous across the connection. `shutdown(:write)`
orders the sender's own writes before FIN, but it is not a barrier that makes
FIN synchronously observable by the receiver. A non-blocking receive with no
buffered payload may still report `would-block`; the adapter waits for
read/hangup readiness and retries under the operation's existing absolute
deadline. After buffered payload is exhausted and FIN is observable, the next
positive-length receive reports the distinct EOF value. A zero-length request
alone reports numeric zero and never stands in for EOF.

Most of this layer is one pure constructor over the socket and clock handlers:

```clojure
(tcp/runtime {:socket-spi (:socket platform-spi)
              :clock-spi  (:clock platform-spi)})
```

Operation state, partial-I/O continuation, portable stream lifecycle, and
backpressure live above that seam and are shared unchanged by every adapter.
Readiness bookkeeping, if the adapter uses it, stays below the seam.

The current Jolt adapter exposes the native seam needed by `:dial!`:

```clojure
(net/try-connect endpoint-or-resolved-addresses opts)
;; => {:jolt.net/socket owned-socket
;;     :jolt.net/status net/connected ; or net/in-progress
;;     :jolt.net/address selected-address
;;     :jolt.net/remaining-addresses [...]}

(net/finish-connect! owned-socket)
;; after write/error/hangup readiness => net/connected, net/in-progress, or throw
```

Initiation owns only one current socket and exposes every untried resolver
candidate. `finish-connect!` checks `SO_ERROR` under that socket's short
operation lease and never closes it. The pure TCP connector therefore owns the
absolute deadline, cancellation token, candidate advancement, and failed-socket
cleanup. This is intentionally below the eventual blocking/async `:dial!`
handler: putting relative timeout or Happy Eyeballs policy into the FFI adapter
would make that policy Jolt-specific and unportable.

`teensyp.client` in jolt-tcp is now the first concrete composition witness. It
uses only `jolt.net`—not raw FFI—to implement an opaque outbound connection,
one monotonic connect deadline across all resolver candidates, exact
failed-socket cleanup, FIFO read/write operation gates, partial-progress-safe
send and receive, per-operation deadlines, half-close, cached EOF, and
idempotent close. nREPL's client transport passes its framing suite on top of
that API without retaining a descriptor or native declaration.

That implementation is evidence for the split, not yet the portable
`clojure.platform.tcp` constructor sketched above: `teensyp.client` currently
calls Jolt's concrete `jolt.net` namespace rather than a versioned socket/clock
handler map. Extracting those calls behind the SPI and running the same TCP
state machine against Jolt, JVM, and fake adapters remains the portability
step.

### `clojure.platform.http`

The Capra-derived layer defines messages, headers, body streaming, cancellation,
deadlines, and normalized response/error semantics. It is free to use TCP,
another runtime-native HTTP implementation, or a fake transport, provided its
advertised capability version and streaming semantics match. HTTP must not
become a new route for FFI calls or platform-specific process/TLS behaviour.

### Bytes, storage, and portable libraries

Cross-runtime reuse is broader than networking. Byte slices, codecs, clocks,
atomic file replacement, append/read/sync operations, and explicit durability
levels form the small base needed by portable disk-backed code. The storage SPI
must distinguish “write accepted,” “visible to readers,” and “durably synced”;
collapsing them would make a fast fake adapter lie about recovery semantics.

Pure libraries can then be layered and tested without knowing the engine:

- in-memory queues, caches, tries, indexes, and immutable/mutable collection
  algorithms over ordinary Clojure values and negotiated byte operations;
- codecs and framed protocols over byte streams;
- reusable clients and servers over framed transports or HTTP;
- append logs, trees, LSM-like indexes, and other disk-backed structures over
  the storage and clock capabilities;
- storage engines, replication protocols, and consensus algorithms over those
  lower portable libraries.

An implementation should depend only on the smallest capability map it needs.
A codec does not receive a socket adapter; an in-memory index does not receive
FFI; a storage engine does not receive HTTP merely because one deployment uses
an HTTP control plane.

## Runtime gate

Every capability version is accepted only after the same behavioural suite runs
against these adapters:

| Adapter | Required evidence |
| --- | --- |
| Jolt | Real platform calls where claimed; ABI probes are recorded separately from runtime calls. |
| JVM | Standard implementation passes the portable behavioural suite; no Jolt-specific type leaks. |
| Fake | Deterministic virtual time, controllable partial I/O, EOF, cancellation, and failure injection. |
| jank | Independently reports capabilities and passes the same suite; it is not accepted by JVM similarity. |
| Node/CLR/other | Independently advertises only implemented semantics and passes the same capability-version suite. |

The minimum suite covers partial reads/writes, EOF and half-close, timeout versus
cancellation, terminal completion uniqueness and ordering, buffer ownership,
error normalization, ownership after failed operations, request/response
backpressure, storage visibility/durability, and deterministic replay of
representative higher-level state machines. Platform tests add OS-specific
facts; they never weaken the portable contract.

## Migration rules

1. Keep existing Jolt-specific implementations working while adapters are
   characterized; this document authorizes no replacement.
2. Extract one capability at a time behind versioned maps, beginning with bytes,
   ownership, monotonic clock, operation/completion, and storage semantics.
3. Port the Teensyp-derived TCP engine over that seam, then reusable framing and
   codecs, then the Capra-derived HTTP layer. Validate each against Jolt, JVM,
   and a deterministic fake before widening the advertised surface.
4. Prove generality with representative portable libraries: at least one
   in-memory structure, one disk-backed log or index, and one framed
   client/server. Raft and LSM can be later examples; neither defines the API or
   blocks earlier useful portability.
5. Add jank, Node, CLR, or another runtime only when it independently satisfies
   the negotiated capability maps; similarity to an existing host is not
   evidence.
6. Reject compatibility shims that erase a semantic difference. A smaller map
   or an explicit unsupported result is preferable to a false portable promise.
