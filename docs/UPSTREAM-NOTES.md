# Upstreaming notes

jolt-net is written to move into the jolt stdlib as `jolt.net`. This file records
what that move depends on, and where jolt-net deliberately departs from the
accepted design spike.

## Fork prerequisites

jolt-net cannot load on released `joltc` v0.4.15. It requires six primitives added
on the fork branch `codex/upstream-improvements-6-8`:

| Primitive | Commit | Why jolt-net needs it |
|---|---|---|
| `jolt.host/target` | `3105198a` | Select exact fail-closed ABI facts instead of inferring them from the build host. |
| `jolt.host/monotonic-nanos`, `System/nanoTime` over a real monotonic clock | `1670dfde` | Deadlines. The previous `nanoTime` was `currentTimeMillis * 1e6` — wall-clock and millisecond-truncated, so it could step backwards and could not resolve a sub-millisecond interval at all. |
| `:int16` / `:uint16` / `:short` / `:ushort` foreign types | `55160f2c` | `sockaddr_in.sin_family` and the `sockaddr_in6` fields are 16-bit. Without them the only option was the endian-dependent short-packing hack in `teensyp.ffi-net`. |
| `jolt.ffi/errno` | `5422ee9d` | The whole error contract rests on reading the native error before any other native call. |
| `jolt.ffi/with-byte-array-pointer` | `1c8fdb97` | Pins a validated interior array slice for one callback, eliminating partial-I/O allocation and copying without exposing an unsafe retained pointer. |
| `{:varargs-after n}` on `jolt.ffi/defcfn` | `ecf7728f` | Lowers an explicit fixed/variadic boundary to Chez. Apple arm64 passes `fcntl`'s third argument according to the variadic ABI even though its Jolt type is known. |
| `{:capture-native-error true}` on `jolt.ffi/defcfn` | `b8229737`, corrected by `01023d9f` | Returns `[result native-error]` from the foreign return boundary, before collect-safe runtime reactivation can clobber POSIX `errno` or Windows last-error state. Blocking socket operations must consume this paired return rather than call `jolt.ffi/errno` afterward. |

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
