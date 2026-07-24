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
`poll(2)` readiness poller with a self-pipe waker.** Non-blocking connect
completion, TLS, and byte-stream protocols remain out of scope. See
`docs/PLATFORM-COVERAGE.md` for exactly what is verified on which platform —
including what is *not*.

## Requirements

**jolt-net does not build on released `joltc` v0.4.15.** It depends on five
primitives added in the local fork:

- `(jolt.host/target)` — the target descriptor, for fail-closed platform tables;
- `jolt.host/monotonic-nanos` — a real monotonic source for deadlines;
- `jolt.ffi/errno` — immediate native error capture;
- `:int16` / `:uint16` foreign types — `sockaddr` fields are 16-bit;
- `jolt.ffi/with-byte-array-pointer` — a scoped, pinned pointer to any validated
  array slice, so partial reads and writes do not allocate or copy.

Run everything through `bin/jnc`, which pins the fork and fails with a readable
message rather than an unbound-var error:

```sh
bin/jnc -A:test -m hegel.install   # one-time: fetch libhegel for property tests
bin/jnc -M:test                    # run the suite
```

Point `JOLT_UPSTREAM` at your fork checkout if it is not the default.

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
