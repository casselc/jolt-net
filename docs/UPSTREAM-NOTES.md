# Upstreaming notes

jolt-net is written to move into the jolt stdlib as `jolt.net`. This file records
what that move depends on, and where jolt-net deliberately departs from the
accepted design spike.

## Jolt v0.7.16 capability boundary

Jolt v0.7.16 supplies exact Chez machine tags, a real monotonic clock, the
current variadic-signature marker, and the public `jolt.ffi/errno` accessor.
Three shared FFI capabilities remain on the fork branch:

| Primitive | Commit | Why jolt-net needs it |
|---|---|---|
| exact scalar widths | `2436a7f7` | `sockaddr_in.sin_family` and `sockaddr_in6` fields are 16-bit. |
| atomic native-error capture | `423cd84d` | Returns `[result native-error]` before later runtime/native work can overwrite the error slot. |
| `jolt.ffi/with-byte-array-pointer` | `38e43b11` | Supplies a validated synchronous native buffer with exact signed-byte copy-back and no retained pointer. |

These stay in Jolt because they are shared FFI/host capabilities, not network
policy. jolt-net wraps upstream `System/nanoTime` only to retain its deterministic
deadline-test seam.

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
and the `source-runtime` capability. CI independently pins the proposal core
through one `JOLT_CORE_SHA`, so every lane combines the same core revision with
the same verified Chez recipe rather than inheriting either from the runner.

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
