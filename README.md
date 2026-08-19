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

The native Windows implementation is split into independently reviewable tasks
in `docs/WINDOWS-RUNTIME-SEQUENCE.md`, including the exact PowerShell/Chez
workflow and the evidence required before each capability is promoted.

Both Windows architectures — **x86-64 and aarch64** — now run the same four
native Winsock gates on real loopback sockets in hosted CI, with independently
probed ABI descriptors. That is **source-mode** evidence: no packaged `joltc`
and no AOT image has been built or tested on Windows ARM64, and the property
layer does not run there. `docs/PLATFORM-COVERAGE.md` states each of those
claims separately, and is the file to read rather than this paragraph.

All six CI lanes — Linux x86_64/aarch64, macOS arm64/x86_64, and Windows
x86-64/aarch64 — run on the same immutable, checksum-pinned Chez Scheme release
(`chez-ci-10.4.1.1`) installed through `casselc/jolt-toolchains/setup-chez`.
Nothing builds Chez from source, each target's archive is pinned to an explicit
SHA-256, and there is no source-build fallback: a digest mismatch fails the job.
Every target is requested at the `source-runtime` capability only, which is the
boundary of what this project claims — a runnable Chez and its boot files, not
the Chez kernel-development inputs. `docs/PLATFORM-COVERAGE.md` records the
digests and the cold/warm CI evidence.

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

This branch targets Jolt v0.7.16 plus three reviewed shared FFI capabilities. It
does not preserve compatibility with older Jolt releases:

- exact fail-closed socket ABI facts selected from upstream
  `jolt.host/machine-type`;
- upstream `System/nanoTime`, wrapped by `jolt.net.target/monotonic-nanos`, for
  deadlines and deterministic test seams;
- `:int16` / `:uint16` foreign types — `sockaddr` fields are 16-bit;
- `jolt.ffi/with-byte-array-pointer` — a scoped copy-in/copy-back native buffer
  over any validated array slice;
- the upstream `:varargs` signature marker — preserves the C variadic ABI
  boundary even with a fully typed Jolt signature; required for `fcntl` on
  Apple arm64.
- `{:capture-native-error true}` on `jolt.ffi/defcfn` — returns the native
  result and its matching `errno`/Windows last-error value as one pair before a
  return-boundary action, lazy resolution, cleanup, or another foreign call can
  clobber the error slot.

The driver no longer consumes ambient `jolt.ffi/errno`; every failure sentinel
whose error matters uses the atomically captured pair above.

Run everything through `bin/jnc`. Set `JOLT_BIN` to test an unreleased runtime;
otherwise it uses the installed `jolt` binary:

```sh
bin/jnc -A:test -m hegel.install   # one-time: fetch libhegel for property tests
bin/jnc -M:test                    # run the suite
```

For example, `JOLT_BIN=/path/to/jolt bin/jnc -M:test` uses that exact binary for
the parent suite and every subprocess witness.

## Design

The accepted design is `jolt-tcp/docs/JOLT-NET-DESIGN-SPIKE.md`. The load-bearing
decisions:

- **Expected states are values, not exceptions.** `::would-block`, `::eof`, and
  `::in-progress` are tagged returns; only genuine failures throw, and they throw
  `ExceptionInfo` carrying a small closed `:kind` set plus the native `:code` —
  which is preserved even when no kind maps to it.
- **A failure sentinel and its error are one result.** `errno`/Windows
  last-error is valid only until intervening runtime or native work. Every
  sentinel-returning call whose error is consumed uses an atomic
  `[result native-error]` foreign return; only error-independent `close` remains
  on the scalar dispatch surface.
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
