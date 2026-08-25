# jolt-net

A portable, dependency-free **socket substrate** for
**[jolt](https://github.com/jolt-lang/jolt)** (Clojure on Chez Scheme) — endpoints,
name resolution, owned sockets, and structured native errors.

## Why

Jolt currently has three partial socket stacks that duplicate the most dangerous
kind of code — raw FFI over `sockaddr` structs — and none of them is complete:

| | `jolt.mvn-http` | `jolt.nrepl` | `jolt-tcp` |
|---|---|---|---|
| DNS (`getaddrinfo`) | yes | no | no |
| IPv6 | partial | no | no |
| Bind a hostname / wildcard | no | no | no |
| Report the real local/peer address | no | no | no |
| Native error code on failure | rarely | never | `errno`, sometimes read too late |

`teensyp.server` literally returns `{:local-address nil :remote-address nil}`, which
forces `jolt-http` to report configured constants instead of the addresses it is
actually serving. A listener bound to port 0 cannot say which port the kernel gave it.

jolt-net factors that substrate out once, correctly, so the three stacks can delete
their copies. It is designed to be **upstreamed into the jolt stdlib** — every public
name is under `jolt.net`, and there are no runtime dependencies.

## Status

Early. The implemented slices are **endpoints, resolution, owned sockets, numeric
address inspection, the error contract, POSIX non-blocking byte I/O, and a POSIX
`poll(2)` readiness poller with a self-pipe waker, including non-blocking
connect initiation and `SO_ERROR` completion.** TLS and byte-stream protocols
remain out of scope. See
`docs/PLATFORM-COVERAGE.md` for exactly what is verified on which platform —
including what is *not*.

## Non-blocking connect

The substrate keeps connection policy above native socket ownership:

```clojure
(let [attempt (net/try-connect (net/endpoint "127.0.0.1" 7888))
      socket  (:jolt.net/socket attempt)]
  (if (= net/in-progress (:jolt.net/status attempt))
    (do
      (net/register! poller socket #{:write})
      ;; await against one caller-owned absolute monotonic deadline
      (net/await-ready poller remaining-timeout-ms)
      ;; writable/error/hangup readiness is not success; SO_ERROR decides
      (net/finish-connect! socket))
    net/connected))
```

`try-connect` also accepts a resolved address or an ordered sequence returned by
`resolve`. It skips synchronous native failures in resolver order and returns
the selected address plus `:jolt.net/remaining-addresses`, so a connector can
close a failed asynchronous attempt and advance without resolving again.
Ownership transfers with both `net/connected` and `net/in-progress`; neither
`finish-connect!` success nor failure closes the socket.

## Requirements

Jolt v0.7.27 supplies every runtime primitive this checkout uses. The former
proposal-fork dependencies have been removed: target selection is a narrow,
fail-closed helper over released System properties and `ffi/sizeof`, while
non-blocking byte I/O uses scoped native scratch plus `read-into!` and sliced
`write-array`.

Run the suite with the current `jolt` CLI:

```sh
jolt -A:test -m hegel.install   # one-time: fetch libhegel for property tests
jolt -M:test                    # run the suite
```

See [`docs/UPSTREAM-NOTES.md`](docs/UPSTREAM-NOTES.md) for the versioned ledger
and the remaining zero-copy pointer-loan opportunity.

## Design

The accepted design is `jolt-tcp/docs/JOLT-NET-DESIGN-SPIKE.md`. The load-bearing
decisions:

- **Expected states are values, not exceptions.** `::would-block`, `::eof`, and
  `::in-progress` are tagged returns; only genuine failures throw, and they throw
  `ExceptionInfo` carrying a small closed `:kind` set plus the native `:code` —
  which is preserved even when no kind maps to it.
- **Capture precedes cleanup.** `errno` is valid only until the next native call,
  and `close()` is a native call. Every failure path reads the error before rolling
  anything back. This is enforced structurally by a combinator, not by convention.
- **Handles are opaque and idempotently closed.** A raw descriptor is available for
  diagnostics but conveys no ownership. Short operation leases prevent native
  close while a syscall is using the descriptor, and close rejects new leases
  before deferring the one `close(2)` to the last releaser.
- **Readiness mutations are acknowledged and tokens are versioned.** Register,
  update, and remove return only after the mutation is applied. Every poll
  snapshot carries the socket ownership generation and registration revision, so
  events from descriptor reuse or an invalidated snapshot are dropped.
- **Connect readiness is not connect success.** Initiation returns an owned
  non-blocking socket plus an exact status. After write/error/hangup readiness,
  completion reads `SO_ERROR` under a short handle lease and preserves the real
  pending native code. Resolver ordering and deadline/cancellation policy remain
  in the connector layer.
- **Non-blocking is a verified postcondition.** `fcntl` declares its two fixed
  arguments before `...`, and every transition reads `F_GETFL` back before the
  handle is marked. An ABI mismatch fails closed instead of turning a short
  handle lease into an uninterruptible syscall.
- **Poller close is a completion boundary.** Wake writes and close share an
  owner-independent CAS admission gate. The winning close retires and drains
  writers, closes pipe-write before pipe-read, wakes/joins the single await, and
  returns only with both wake handles closed.
- **Numeric inspection never triggers reverse DNS.** Address text is formatted in
  pure Clojure, so that is a structural guarantee rather than flag discipline.
- **Platform tables fail closed.** An unrecognized target throws instead of
  inferring an ABI from a similar-looking one.

## License

EPL-2.0 OR GPL-2.0-or-later, matching jolt-tcp.
