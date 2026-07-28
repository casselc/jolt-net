# Upstreaming notes

jolt-net is written to move into the jolt stdlib as `jolt.net`. This file records
what that move depends on, and where jolt-net deliberately departs from the
accepted design spike.

## Fork prerequisites

jolt-net cannot load on upstream Jolt v0.5.7. It requires seven primitives
retained on fork branch `codex/upstream-rebase-v0.5.7`, currently pinned at
`46e1f74fc14f29283586900ef4b98c45375c0500`:

| Primitive | Commit | Why jolt-net needs it |
|---|---|---|
| `jolt.host/target` | `1af26e26` | Select exact fail-closed ABI facts instead of inferring them from the build host. |
| `jolt.host/monotonic-nanos`, `System/nanoTime` over a real monotonic clock | `6f01c236` | Deadlines. The previous `nanoTime` was `currentTimeMillis * 1e6` — wall-clock and millisecond-truncated, so it could step backwards and could not resolve a sub-millisecond interval at all. |
| `:int16` / `:uint16` / `:short` / `:ushort` foreign types | `437ed89d` | `sockaddr_in.sin_family` and the `sockaddr_in6` fields are 16-bit. Without them the only option was the endian-dependent short-packing hack in `teensyp.ffi-net`. |
| `jolt.ffi/errno` | `31fe706c` | The whole error contract rests on reading the native error before any other native call. |
| `jolt.ffi/with-byte-array-pointer` | `f8c91414` | Pins a validated interior array slice for one callback, eliminating partial-I/O allocation and copying without exposing an unsafe retained pointer. |
| `{:varargs-after n}` on `jolt.ffi/defcfn` | `8f16fec2` | Lowers an explicit fixed/variadic boundary to Chez. Apple arm64 passes `fcntl`'s third argument according to the variadic ABI even though its Jolt type is known. |
| `{:capture-native-error true}` on `jolt.ffi/defcfn` | `d0c58691`, corrected by `d973693d` | Returns `[result native-error]` from the foreign return boundary, before later runtime or native work can clobber POSIX `errno` or Windows last-error state. Every sentinel-returning operation whose error is consumed must use this pair rather than call `jolt.ffi/errno` afterward. |

The pinned tip also carries `9f5acb54` / `46e1f74f`, which make the transactional
Git dependency cache report the platform's own diagnostics instead of assuming a
POSIX `sh` error string. That fix is what lets a native Windows ARM64 runner
reach a readable failure rather than an opaque one, so it is part of this pin
rather than an unrelated carry.

These live in the proposed fork rather than inside jolt-net because each is a
shared FFI/host platform concern. Nothing in this branch has been pushed to the
core project's origin.

## Hard runtime prerequisite: lazy `defcfn`

`defcfn` resolves its C symbol **lazily, on first call**
(`jolt-core/jolt/backend_scheme.clj`, commit `c71bc342`). jolt-net relies on this: it
declares *every* platform's bindings at top level under distinct names and selects an
active set from the target descriptor. That is what lets a Linux test assert on the
Windows signature table as a value without ever calling into it.

If jolt-net were ever built on a branch predating `c71bc342`, loading it on Linux
would fail while trying to resolve Winsock symbols. **No load-time assertion can
detect this** — that is precisely what laziness removes — so it is recorded here.

## Runtime substrate

The fork pin above is one half of what CI reproduces; the Chez Scheme it runs on
is the other. Every lane installs the shared immutable release
`chez-ci-10.4.1.1` through `casselc/jolt-toolchains/setup-chez` (pinned at
`095108ae32659757808064d004855092567d3ad3`), with a per-target archive SHA-256
and the `source-runtime` capability. That recipe records the same Jolt commit
this file pins, `46e1f74fc14f29283586900ef4b98c45375c0500`, so the runtime and
the fork cannot drift apart silently.

This matters for upstreaming in one specific way: `source-runtime` is the only
capability jolt-net has ever needed. It does not link against the Chez kernel and
requests none of the GNU kernel-development inputs, so moving these files into the
stdlib does not carry a build-toolchain requirement with them.
`docs/PLATFORM-COVERAGE.md` holds the digests and the cold/warm CI evidence.

## Deliberate departures from the design spike

**Numeric address text is formatted in pure Clojure, not via `getnameinfo`.** The
spike recommends `getnameinfo` with numeric flags. Rejected because `NI_NUMERICHOST`
is `1` on Linux but `2` on macOS/Windows, `NI_NUMERICSERV` likewise differs, and it
drags in a second `EAI_*` error surface. Pure formatting makes "endpoint reporting
never triggers reverse DNS" a *structural* guarantee instead of one that depends on
passing the right flag on every call.

**`strerror` is not called.** `strerror` is not thread-safe, and `strerror_r` has
incompatible GNU and XSI signatures. Messages come from a static table; the native
code is always preserved regardless, so no diagnostic information is lost.

## When this moves upstream

1. `src/jolt/net*.clj` → `stdlib/jolt/`, unchanged.
2. `bin/jnc` and the fork-prerequisite checks in the test main become unnecessary.
3. The `tools/probe-constants.sh` harness should move with the tables it verifies —
   it is what keeps them honest.
