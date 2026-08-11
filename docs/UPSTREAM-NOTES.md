# Upstreaming notes

jolt-net is written to move into the jolt stdlib as `jolt.net`. This file records
what that move depends on, and where jolt-net deliberately departs from the
accepted design spike.

## Jolt v0.7.1 capability boundary

Jolt v0.7.1 supplies exact machine tags, a real monotonic clock, and the current
variadic-signature marker. This branch adds only the shared FFI capabilities
that remain absent upstream:

| Primitive | Commit | Why jolt-net needs it |
|---|---|---|
| `jolt.host/machine-type` | upstream v0.7.1 | Select exact fail-closed ABI facts in jolt-net without requiring the fork-only `jolt.host/target` projection. |
| `System/nanoTime` over a real monotonic clock | upstream v0.7.1 | Deadlines need monotonic, sub-millisecond time. Upstream now supplies it directly, so jolt-net no longer needs the fork-only `jolt.host/monotonic-nanos` alias. |
| `:int16` / `:uint16` / `:short` / `:ushort` foreign types | `2436a7f7` | `sockaddr_in.sin_family` and the `sockaddr_in6` fields are 16-bit. Without them the only option was an endian-dependent short-packing workaround. |
| atomic native-error capture and `jolt.ffi/errno` | `423cd84d` | The error contract must pair the native result with the error slot before any later native work can overwrite it. |
| `jolt.ffi/with-byte-array-pointer` | `38e43b11` | Provides a validated synchronous native buffer for partial I/O, with exact signed-byte copy-in/copy-back and no retained unsafe pointer. |
| `:varargs` marker in a `jolt.ffi/defcfn` signature | upstream v0.7.1 | Lowers an explicit fixed/variadic boundary to Chez. Apple arm64 passes `fcntl`'s third argument according to the variadic ABI even though its Jolt type is known. |

The three fork additions remain in Jolt because they are shared FFI/host
capabilities, not networking policy.

## Hard runtime prerequisite: lazy `defcfn`

`defcfn` resolves its C symbol **lazily, on first call**
(`jolt-core/jolt/backend_scheme.clj`, commit `c71bc342`). jolt-net relies on this: it
declares *every* platform's bindings at top level under distinct names and selects an
active set from the target descriptor. That is what lets a Linux test assert on the
Windows signature table as a value without ever calling into it.

If jolt-net were ever built on a branch predating `c71bc342`, loading it on Linux
would fail while trying to resolve Winsock symbols. **No load-time assertion can
detect this** — that is precisely what laziness removes — so it is recorded here.

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
