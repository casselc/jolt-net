# Upstreaming notes

jolt-net is written to move into the jolt stdlib as `jolt.net`. This file records
what that move depends on, and where jolt-net deliberately departs from the
accepted design spike.

## Current upstream boundary (revalidated 2026-08-25)

Jolt v0.7.27 releases four of the original six prerequisites. The other two are
no longer runtime requirements: jolt-net derives only the three target facts it
consumes from released APIs, and uses scoped scratch buffers for native I/O.
The checkout now runs on stock Jolt.

| Primitive | Official status | Why jolt-net needs it |
|---|---|---|
| `jolt.host/target` | Not released; dependency removed | `jolt.net.target/current-target` normalizes `os.name`, `os.arch`, and `ffi/sizeof :pointer`, then the existing tuple table still fails closed. |
| `System/nanoTime` over a real monotonic clock | Released in 0.5.16 | Deadlines must not follow a stepping wall clock. |
| `:int16` / `:uint16` / `:short` / `:ushort` | Released in 0.7.21 | Describe socket structures without endian-dependent half-word packing. |
| `{:capture-native-error true}` on `jolt.ffi/defcfn`/`foreign-fn` | Released in 0.7.28 | Capture errno/GetLastError atomically with the result. A follow-up read is unsound for `:blocking` calls because collect-safe thread reactivation runs before Jolt code. |
| `jolt.ffi/with-byte-array-pointer` | Not released; dependency removed | Reads use scoped scratch plus `read-into!`; writes use scoped scratch plus sliced `write-array`. The gated fork branch can remove these copies later without changing the socket contract. |
| `:varargs` in the `defcfn` argument vector | Released in 0.6.8 | Mark the fixed/variadic ABI boundary; this replaces the proposal's older `{:varargs-after n}` spelling. |

The historical proposal commits remain useful design evidence, but no proposal
fork is selected by the build or test workflow.

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
2. The local target helper can move with the tables unless Jolt gains an
   equivalent stable target API.
3. The `tools/probe-constants.sh` harness should move with the tables it verifies —
   it is what keeps them honest.
