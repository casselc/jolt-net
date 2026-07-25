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
| Linux aarch64 | **probed** | **runtime** | **runtime** | The `ubuntu-24.04-arm` job commits and diffs its independent native probe before running real sockets. Probe, blocking/non-blocking runtime, poller races, and required Hegel properties passed on revision `5554d04` in CI run `30146461748`. |
| Windows x86-64 | **probed** | **runtime** | **candidate** (byte I/O + connect only; **no poller**) | The repaired direct PowerShell gates passed W1 155/155 and W2 56/56 on revision `5554d04` in CI run `30146461748`; the runner now requires an observed process exit code. `WSAPoll` and the wake/close lifecycle remain W3/W4, so `open-poller` and everything built on it still fail closed. |
| Windows aarch64 | **preview artifact** | none | none | CI run `30144909720` built native `tarm64nt` Chez 10.4.1, executed the ABI probe with ARM64 MSVC, and passed direct source-mode target/fail-closed selection. The uploaded facts match Windows x86-64 after normalizing only CRLF and the architecture label, but no descriptor is committed yet. |
| macOS arm64 | **probed** | **runtime** | **runtime** | The complete native suite passes with source-built Chez 10.4.1: variadic-ABI-correct `fcntl`, `poll(2)`, non-blocking connect/`SO_ERROR`, sliced byte I/O, SIGPIPE, close races, and the owner-independent self-pipe protocol, with Darwin's distinct 32-bit `nfds_t` binding. |
| macOS x86-64 | **probed** | **runtime** | **runtime** | Its independent native probe is committed, while CI also normalizes only the architecture label and confirms it matches the shared-Darwin descriptor. Probe, full socket/poller runtime, source-built pinned libhegel, and required Hegel properties passed on revision `f0affc4` in CI run `30144054281`; run `30146461748` caught and supplied the newly added absent-ioctlsocket fields before runtime execution. |

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
- **Windows non-blocking transitions and byte I/O are now exercised on a real
  Windows machine** (task W2, `docs/WINDOWS-RUNTIME-SEQUENCE.md`). `FIONBIO`
  and the widths of `ioctlsocket`'s `long` command and `u_long` argument are
  read from the platform headers, not assumed: both are **32 bits even on
  Win64**, unlike the pointer-width socket handle. Treating the argument cell as
  pointer-width would misdescribe the ABI and rely on incidental low-byte
  layout, so unsupported widths fail closed. `FIONBIO` is
  `_IOW('f', 126, u_long)` = `0x8004667E`, which does not fit a signed 32-bit
  int; the committed table carries the signed `long` the ABI actually passes.
- **Windows has no getter for a socket's blocking mode**, so the `F_GETFL`
  read-back that guards the POSIX transition has no counterpart. jolt-net does
  not invent one. Production marks the handle only after `ioctlsocket` returns
  successfully under the probed ABI and documented Winsock FIONBIO contract.
  The native gate supplies cross-boundary conformance evidence:
  `try-accept` with no pending client and a read with no pending data must return
  `::would-block` as a value, which a blocking descriptor could not do without
  parking the thread and tripping the suite watchdog. That observation happens
  after marking; it is not an in-process guard or proof for every future handle.
  `jolt.net.nonblocking/postcondition-kind` names the in-process boundary. See
  `docs/proofs/socket-invariants.md` and the separate
  `docs/proofs/models/{posix-nonblocking-transition,windows-nonblocking-contract}-*.smt2`
  model families.
- **Windows readiness is still absent.** `WSAPoll` (task W3) and an
  owner-independent wake and close lifecycle (task W4) are not implemented, so
  no poller exists on Windows and `accept` there remains the native blocking
  call. A Windows caller can initiate `try-connect` and complete it with
  `finish-connect!`, but must supply its own readiness wait in between; W3 owns
  the readiness-driven native integration and its evidence. A listener switched
  to non-blocking mode by `try-accept` cannot use Windows's native blocking
  accept afterward; `accept` now fails explicitly with
  `:jolt.net/requires :windows-readiness` instead of violating its blocking
  contract. This is a sequential guard: concurrent `accept` and `try-accept` on
  one Windows listener remain unsupported until mode/admission is atomic. W3
  owns the readiness-driven replacement.
- **macOS runtime evidence.** The earlier gate showed that a typed
  three-argument signature is not enough for variadic `fcntl` on Apple arm64:
  the third argument uses the variadic stack ABI. The core binding now declares
  `{:varargs-after 2}`, and jolt-net reads `F_GETFL` back before marking a
  handle. The complete poller, connect, close-race, SIGPIPE, and sliced-I/O
  suite passed on the macOS arm64 runner for commit `65a0f1e` in
  [CI run 30078697403](https://github.com/casselc/jolt-net/actions/runs/30078697403).
  There is no fallback to the old uninterruptible accept path.
- **Intel macOS evidence.** `macos-15-intel` has independent ABI-probe and
  complete POSIX-runtime jobs. Both passed in run `30144054281`; the runtime job
  proves the explicit shared-Darwin descriptor by diffing all live facts and
  builds the pinned libhegel source because no matching release asset exists.
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

The remaining gaps are a green hosted result for the combined Windows
*socket-runtime* gates and the Windows *readiness* backend. CI source-builds Chez
and runs both W1 and W2 through direct PowerShell/Chez on Windows x86-64, with
the child process exit code now mandatory. A non-gating native ARM64 preview
produces the missing ABI artifact while proving unreviewed selection fails
closed. Task W5 promotes the completed W1-W4 path after readiness and close
lifecycle work; ARM64 cannot load descriptor-backed namespaces until its probe
is reviewed and committed.

CI (`.github/workflows/ci.yml`) re-probes Linux x86_64, Linux aarch64, macOS
arm64, macOS x86_64, and Windows x86_64 on each push. A separate preview
produces Windows aarch64 evidence without promoting it to the committed table.
CI fails if a committed descriptor disagrees with the platform's real headers;
the Linux arm64 and Intel macOS runtime jobs also require their complete probes
to match their explicit descriptor aliases.
