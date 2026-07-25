# Platform coverage

What is actually verified, and by what means. The distinction matters: a green table
test proves the *selection logic*, not that the constants in the table are right.

Do not summarize this file as "supports Linux, macOS and Windows."

## Levels of evidence

| Level | Meaning |
|---|---|
| **runtime** | Real sockets opened, real syscalls made, on this platform in CI. |
| **candidate** | Implemented from probed ABI facts and assigned to a native CI gate, but that gate has not yet validated this exact revision. |
| **probed** | `tools/probe-constants.c` compiled against that platform's real system headers and **executed**, so every constant, `sizeof`, and `offsetof` is read from the platform itself and diffed against the committed table. Proves the numbers; proves nothing about calls. |
| **configured** | A native evidence job exists on this revision, but no successful run/artifact has yet been observed. |
| **preview artifact** | A native non-gating job produces probe evidence, but the artifact has not yet been reviewed into a committed descriptor. |
| **table** | Only the selection logic is exercised. The numbers themselves are unverified. |
| **none** | Not covered. |

## Current state

| Platform | Constants / layouts | Blocking socket base | Non-blocking I/O + poller/connect | Notes |
|---|---|---|---|---|
| Linux x86-64 | **probed** | **runtime** | **runtime** | The development and CI platform. Real `fcntl`, `poll`, pipe-wake, sliced byte I/O, EOF, non-blocking connect/`SO_ERROR`, mutation wake, and close races are exercised. |
| Linux aarch64 | **candidate** | **candidate** | **candidate** | Has its own `ubuntu-24.04-arm` ABI-probe and full-runtime jobs. The descriptor explicitly aliases the x86-64 socket facts (same kernel UAPI and LP64), and the arm64 job diffs every freshly probed fact against that alias before running real sockets. Keep this row at candidate until that native job is observed green. |
| Windows x86-64 | **probed** | **candidate** | none | A portable hosted gate is configured to load native Chez/Jolt and exercise descriptor/address logic. Separately, `tools/test-windows-blocking.ps1` has made real local Winsock calls: initialization, IPv4/IPv6 loopback listen/connect/accept, port-zero, endpoint inspection, duplicate bind, and idempotent close. Keep this row at candidate until the socket suite is a hosted gate on the exact revision. |
| Windows aarch64 | **configured** | none | none | A non-gating `windows-11-vs2026-arm` job is configured to build native `tarm64nt` Chez 10.4.1, compile and execute the probe with ARM64 MSVC, assert the probe and source Jolt both report `:aarch64`, and upload `windows-aarch64.edn`. No descriptor is committed from assumed x64 similarity: until a successful artifact is reviewed, target selection is required to fail closed. |
| macOS arm64 | **probed** | **runtime** | **runtime** | The complete native suite passes with source-built Chez 10.4.1: variadic-ABI-correct `fcntl`, `poll(2)`, non-blocking connect/`SO_ERROR`, sliced byte I/O, SIGPIPE, close races, and the owner-independent self-pipe protocol, with Darwin's distinct 32-bit `nfds_t` binding. |
| macOS x86-64 | **candidate** | **candidate** | **candidate** | Has its own `macos-15-intel` probe and full-runtime jobs. The live x86_64 probe is uploaded and reported as a new artifact, then explicitly normalized and diffed against the shared Darwin descriptor. Keep this row at candidate until both jobs are observed green. |

## Specific residuals

- **Win64 handle width.** `SOCKET` is pointer-width unsigned and `INVALID_SOCKET` is
  all-bits-one, so `neg?` and an `:int` return type are both invalid tests. The
  predicate is exercised on Linux against synthetic high-bit values, and a real
  Windows run now also marshals actual `:uptr` socket handles end to end
  (listen/connect/accept), though no single observed handle in that run had its
  high bit set.
- **`WSAStartup` ordering and once-only initialization are now exercised on a
  real Windows machine** (task W1, `docs/WINDOWS-RUNTIME-SEQUENCE.md`). The
  original `10093` (`WSANOTINITIALISED`) witness — `getaddrinfo` resolving
  before `ensure-subsystem!` ran — is gone because `jolt.net.resolver/resolve`
  now calls `ensure-subsystem!` directly, not only through `socket-for`. A
  latch-gated 32-future stress test proved exactly one `WSAStartup` attempt and
  consistent memoized success across all of them. Fresh-state controls also
  prove that both a returned initialization error and a thrown allocation/FFI
  exception become one terminal memoized failure rather than an unresolved
  promise; see
  `docs/proofs/socket-invariants.md` §0 and
  `docs/proofs/models/winsock-init-once-*.smt2`.
- **Refused-connect and first-use duplicate-bind exposed the full atomic capture
  prerequisite.** A raw
  `socket()`/`connect()` probe below the `jolt.net` API showed `connect()`
  genuinely returning `-1`, but three immediate, consecutive
  `WSAGetLastError()` reads afterward all returning `0`. Pairing only blocking
  calls then exposed a second witness: the first duplicate `bind` reported code
  `0`, while a later duplicate bind happened to retain `10048`. The sound rule
  is therefore independent of `:blocking`: every sentinel-returning call whose
  error is consumed returns `[result native-error]` atomically. On revision
  `11142a3`, the native W1 suite observed exact `10061` and `10048` in the same
  process; scalar dispatch owns only error-independent `close`.
- **Windows ARM64 evidence boundary.** The hosted runner includes an x86_64
  MinGW gcc that can run under emulation. The preview therefore invokes
  `cl.exe` only after selecting the MSVC `x64_arm64` environment and rejects
  probe output without `:arch :aarch64`. The resulting artifact is evidence to
  review, not permission to infer a descriptor from Windows x86-64.
- **Windows readiness** still needs `ioctlsocket(FIONBIO)`, `WSAPoll`, and a
  tested owner-independent wake transport such as a loopback UDP pair. The
  non-blocking connect API fails closed before resolution or socket creation on
  Windows until that backend and real `getsockopt(SO_ERROR)` calls are verified.
  This is unchanged by task W1, which is scoped to the blocking socket base only.
- **macOS runtime evidence.** The earlier gate showed that a typed
  three-argument signature is not enough for variadic `fcntl` on Apple arm64:
  the third argument uses the variadic stack ABI. The core binding now declares
  `{:varargs-after 2}`, and jolt-net reads `F_GETFL` back before marking a
  handle. The complete poller, connect, close-race, SIGPIPE, and sliced-I/O
  suite passed on the macOS arm64 runner for commit `65a0f1e` in
  [CI run 30078697403](https://github.com/casselc/jolt-net/actions/runs/30078697403).
  There is no fallback to the old uninterruptible accept path.
- **Intel macOS evidence.** `macos-15-intel` now has independent ABI-probe and
  complete POSIX-runtime jobs. The probe remains a separately uploaded
  `darwin-x86-64.edn` artifact and the tables job reports it as new until the
  evidence is reviewed and committed; the runtime job additionally proves the
  current shared-Darwin descriptor assumption by diffing all live facts.
- **Readiness hot-path shape.** Listener, connected, and accepted descriptors
  enter nonblocking mode once. A scoped core FFI primitive pins and exposes the
  validated interior pointer for every byte-array slice, so partial recv/send
  calls allocate no temporary array and copy no payload bytes.
- **Interrupted poll.** POSIX `EINTR` is retried against the same absolute
  monotonic deadline; interruption does not restart or extend the caller's
  timeout. Wake-pipe reads and writes also retry `EINTR` while retaining the
  same short handle lease and native buffer.
- **Connect deadlines.** `try-connect` itself never accepts a relative timeout.
  It returns the owned socket, selected address, exact initiation status, and
  untried resolver candidates. A connector registers the socket for write
  readiness, recomputes each finite wait from one absolute monotonic deadline,
  and calls `finish-connect!`; only `SO_ERROR == 0` is success. The runtime suite
  exercises immediate/in-progress classification, real loopback completion and
  refusal, caller-retained ownership after completion failure, rollback leak
  detection, and this absolute-deadline composition.
- **Darwin `nfds_t`.** The probe records the width and the call table selects an
  exact binding: `:size_t` on supported LP64 Linux and `:uint` on Darwin.
- **macOS `sin_len`.** BSD puts the struct size in byte 0 and the family in byte 1.
  The Mac probe supplies both the 16-byte struct size and family offset 1; the
  pure encoder is then exercised from Linux against those probed facts.
- **Wall-clock adjustment.** The monotonic clock's independence from an NTP step is
  not directly tested — stepping the system clock needs root. The test substitutes
  the falsifiable half: proving a distinct, boot-relative source.
- **IPv6** is skipped, not failed, where the host has no `::1`. A SKIP is not a pass.

## What the probe caught

Three facts a hand-written table got wrong. The first two were caught locally;
the third was caught only once CI ran the probe on a real Mac, which is the
whole argument for doing it:

- **`addrinfo` field order differs.** Linux places `ai_addr` at 24 and `ai_canonname`
  at 32; Windows (and macOS) reverse them. Reading one platform's offsets on the
  other yields a pointer to the wrong field.
- **Windows `ai_addrlen` is `size_t` (8 bytes)**, not `socklen_t` (4).
  `jolt.mvn-http` reads it as `:int` and only survives because Win64 is
  little-endian and address lengths are small.
- **macOS has `MSG_NOSIGNAL`** (`0x80000`), and this table said it did not.
  Documentation commonly presents `SO_NOSIGPIPE` as the BSD mechanism, which is
  true but not exclusive. The wrong value was benign — the socket option path
  still suppresses SIGPIPE — but it was wrong, and only a real Mac could say so.

The runtime gate caught a different class of problem that a header probe cannot:
`fcntl`'s constants and return value were correct while its variadic calling
convention was not. This is why the suite now checks the kernel-visible
`O_NONBLOCK` postcondition rather than accepting `F_SETFL == 0` as sufficient.

The table test is non-vacuous: corrupting `AF_INET6` or swapping
`ai_addr`/`ai_canonname` makes it fail with the exact field named.

## Making this better

The remaining gap is hosted Windows *socket-runtime* coverage. CI source-builds Chez
and runs Jolt on Windows x86-64, and a non-gating native ARM64 preview produces
the missing ABI artifact while proving unreviewed selection fails closed. The
x86-64 hosted job intentionally stops at portable target/address checks while
W1 is a locally validated candidate; task W5 promotes the PowerShell
socket suite to hosted evidence after W2-W4 complete the portable readiness and
close lifecycle. ARM64 cannot load descriptor-backed namespaces until its probe
is reviewed and committed.

CI (`.github/workflows/ci.yml`) re-probes Linux x86_64, Linux aarch64, macOS
arm64, macOS x86_64, and Windows x86_64 on each push. A separate preview
produces Windows aarch64 evidence without promoting it to the committed table.
CI fails if a committed descriptor disagrees with the platform's real headers;
the Linux arm64 and Intel macOS runtime jobs also require their complete probes
to match their explicit descriptor aliases.
