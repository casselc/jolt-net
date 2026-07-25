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
| **table** | Only the selection logic is exercised. The numbers themselves are unverified. |
| **none** | Not covered. |

## Current state

| Platform | Constants / layouts | Blocking socket base | Non-blocking I/O + poller/connect | Notes |
|---|---|---|---|---|
| Linux x86-64 | **probed** | **runtime** | **runtime** | The development and CI platform. Real `fcntl`, `poll`, pipe-wake, sliced byte I/O, EOF, non-blocking connect/`SO_ERROR`, mutation wake, and close races are exercised. |
| Linux aarch64 | table | none | none | Aliases the x86-64 socket facts: same kernel UAPI, same LP64. An explicit table entry with the checked facts listed — never a `:linux` fallback. |
| Windows x86-64 | **probed** | **candidate** | none | Probed on a real Windows CI runner (and independently via mingw + WSL interop, which agree). A real native Windows machine (Chez 10.4.1, `tools/test-windows-blocking.ps1`) has now made real Winsock calls on commit `4e7dc43`+this task's changes: `WSAStartup` ordering/once-only, real IPv4/IPv6 loopback listen/connect/accept, port-zero, duplicate-bind, and idempotent close all passed. Marked **candidate** rather than **runtime** because this ran locally, not yet on a hosted CI gate (task W5) — see `docs/WINDOWS-RUNTIME-SEQUENCE.md`. |
| macOS arm64 | **probed** | **runtime** | **runtime** | The complete native suite passes with source-built Chez 10.4.1: variadic-ABI-correct `fcntl`, `poll(2)`, non-blocking connect/`SO_ERROR`, sliced byte I/O, SIGPIPE, close races, and the owner-independent self-pipe protocol, with Darwin's distinct 32-bit `nfds_t` binding. |
| macOS x86-64 | **table** | none | none | Shares the arm64 descriptor: these are SDK facts rather than arch facts on macOS. Only arm64 is machine-checked. |

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
  32-future stress test proved exactly one `WSAStartup` attempt and consistent
  memoized success across all of them; see
  `docs/proofs/socket-invariants.md` §0 and
  `docs/proofs/models/winsock-init-once-*.smt2`.
- **Refused-connect native code (`WSAECONNREFUSED` / 10061) is NOT proven**,
  and this is a newly discovered runtime defect, not a jolt-net bug. A raw
  `socket()`/`connect()` probe below the `jolt.net` API showed `connect()`
  genuinely returning `-1`, but three immediate, consecutive
  `WSAGetLastError()` reads afterward all returning `0`. Non-`:blocking` calls
  on the same target (`bind`/`listen`, duplicate-bind's real `10048`) correctly
  preserve their codes. This isolates the clobber to the `:blocking`
  (collect-safe) Winsock FFI calling convention in this proposal-runtime
  revision (`9dc88108`) — outside jolt-net's own capture ordering and outside
  task W1's scope to patch, since that runtime is a separate, pinned, detached
  checkout. `test/jolt/net/blocking_test_main.clj` records this as an honest,
  diagnosed SKIP rather than a silently weakened assertion or a hidden defect.
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

The remaining gap is Windows *runtime* (hosted-CI) coverage, promoted from the
**candidate** evidence above only once `tools/test-windows-blocking.ps1` (or its
successor) runs green as a Windows x86-64 GitHub Actions gate on the exact
revision — task W5 in `docs/WINDOWS-RUNTIME-SEQUENCE.md`. That still needs a
Chez Scheme build reachable from a hosted Windows runner. Separately, Windows
non-blocking I/O, `WSAPoll`, and the wake transport (tasks W2-W4) remain
unimplemented, and the `:blocking`-call errno-clobber defect noted above needs
a fix in the proposal runtime before refused-connect's native code can be
proven on any platform's CI.

CI (`.github/workflows/ci.yml`) re-probes every platform on each push and fails
the build if a committed descriptor disagrees with that platform's real headers,
so these tables cannot silently drift.
