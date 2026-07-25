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
| Linux aarch64 | **probed** | **runtime** | **runtime** | The `ubuntu-24.04-arm` job diffs every freshly probed fact against the explicit x86-64 alias before running real sockets. Probe, blocking/non-blocking runtime, poller races, and required Hegel properties passed on revision `f0affc4` in CI run `30144054281`. |
| Windows x86-64 | **probed** | **runtime** | **runtime** (byte I/O, connect, `WSAPoll` readiness, **and the public poller**) | Hosted Windows CI ran all four native gates on revision `0e66bcf` in [CI run 30169225227](https://github.com/casselc/jolt-net/actions/runs/30169225227): W1 162/162, W2 56/56, W3 124/124, and the W4 public-poller gate 73/73, each with a required observed child exit code of 0, against a source-built official Chez 10.4.1 and the pinned Jolt fork `85f645aa`. The same four counts had passed locally on `38ff1db` beforehand. The `WSAPOLLFD` layout, flag values, and `WSAPoll` signature are probed from real Windows headers and gated byte-for-byte by the drift job; a local MinGW re-run of `tools/probe-constants.c` on this revision showed no drift from the committed table. Readiness covers read, write, error, hangup/EOF, zero and positive timeouts, captured failure, readiness-driven connect completion, forced short reads, and stale generation/revision/removal token rejection. Task W4 added an owner-independent wake transport — a connected IPv4 loopback datagram pair, chosen because both ends are real `SOCKET`s and `WSAPoll` accepts nothing else — so `jolt.net/open-poller` now works here, `close!` is a completion boundary rather than a refusal, and blocking `accept` is readiness-driven. The W3 wake-less adapter is retained, and its refusals remain evidence for a poller built *without* a waker rather than for this transport. The gates carry no jolt-hegel alias and download no artifact, so a dependency-resolution failure cannot quietly erase this runtime coverage. |
| Windows aarch64 | **preview artifact** | none | none | CI run `30144909720` built native `tarm64nt` Chez 10.4.1, executed the ABI probe with ARM64 MSVC, and passed direct source-mode target/fail-closed selection. The uploaded facts match Windows x86-64 after normalizing only CRLF and the architecture label, but no descriptor is committed yet. |
| macOS arm64 | **probed** | **runtime** | **runtime** | The complete native suite passes with source-built Chez 10.4.1: variadic-ABI-correct `fcntl`, `poll(2)`, non-blocking connect/`SO_ERROR`, sliced byte I/O, SIGPIPE, close races, and the owner-independent self-pipe protocol, with Darwin's distinct 32-bit `nfds_t` binding. |
| macOS x86-64 | **probed** | **runtime** | **runtime** | The live x86_64 probe is normalized only at the architecture label and diffed against the explicit shared-Darwin descriptor. Probe, full socket/poller runtime, source-built pinned libhegel, and required Hegel properties passed on revision `f0affc4` in CI run `30144054281`. |

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
- **Windows now has both readiness and a wake transport.** Task W3 added the
  `WSAPoll` backend under `jolt.net.readiness`; task W4 added the waker under
  `jolt.net.wake` and promoted Windows into the public poller. The waker is a
  connected IPv4 loopback datagram pair. That choice is not arbitrary and not
  merely convenient: `WSAPoll` accepts only `SOCKET`s, and one non-socket handle
  in its array can fail the **entire** call with `WSAENOTSOCK` rather than
  marking that one entry, so an anonymous pipe would not degrade gracefully — it
  would destroy the readiness of every other registered socket in the same wait.
  Both ends are switched to non-blocking mode before ownership is published, and
  every partially constructed handle is closed on rollback.

  The transports are **not** interchangeable at their terminal edge, and the
  close protocol does not pretend otherwise. Retiring a connected datagram
  *sender* is invisible to its peer: no hangup, no error, no readiness event. So
  a published terminal **byte** is the only Windows cancellation guarantee,
  where POSIX additionally gets `POLLHUP` from the retired pipe. `close!`
  therefore publishes that byte at step 3, while sends are still admitted, and
  only then retires admission at step 4; and `await-ready` re-reads the
  lifecycle after its last drain and before the native call, because that drain
  can legitimately consume the terminal byte. Any `WSAECONNRESET` a connected
  UDP receiver surfaces after its peer is retired is an ICMP port-unreachable
  artifact, not a protocol guarantee; `jolt.net.wake` classifies it as benign
  drain noise so nothing can come to depend on it. See
  `docs/proofs/socket-invariants.md` §8.

  The internal `jolt.net.poller/open-readiness-adapter` is retained. It runs the
  same registration and token state machine with **no** waker and refuses —
  rather than degrades — every wake-dependent operation, and the W3 gate still
  proves that. It is evidence about a poller built without a transport, and is
  explicitly not evidence for the W4 one.
- **Windows blocking accept is now readiness-driven, and W2's mixed-mode refusal
  is gone.** That refusal existed for exactly one reason: native blocking
  `accept` could not be interrupted, so mixing it with a listener `try-accept`
  had switched to non-blocking mode was unsound. Task W4 removed native blocking
  accept from Windows entirely — `accept` there now uses the same short
  non-blocking accept leases plus close-wakeable poller as POSIX — so mixing is
  sound and listener close is a bounded completion boundary rather than an
  uninterruptible park. The native W4 gate covers accept after a prior
  `try-accept`, accept and `try-accept` interleaved, and listener close racing a
  blocked accept, and asserts that the release is an ownership failure rather
  than a native one, so no syscall reached a closing descriptor. Readiness did
  not become a blocking-mode getter; `postcondition-kind` is still
  `:call-status` on Windows.
- **`WSAPoll` diverges from POSIX `poll` behaviorally, not just numerically.**
  Both differences are asserted by the W3 gate rather than smoothed over. A peer
  FIN with no pending data reports `POLLHUP` **alone** on Winsock, where Linux
  reports `POLLIN`; a Windows caller that acts only on `:read` will therefore
  never observe EOF, and must act on `:hangup`. Behind unread data the same FIN
  does report readable, and readable is never discarded because hangup is also
  set. Separately, a handle Winsock does not recognize fails the **entire**
  `WSAPoll` call with `WSAENOTSOCK` instead of marking that one entry
  `POLLNVAL`, which is why the poller holds a handle lease across the native
  wait. A refused connect is reported as `POLLWRNORM|POLLERR|POLLHUP`, and only
  after the refusal lands (about 2.5 s on loopback here) -- before that,
  `SO_ERROR` still reads 0, so `finish-connect!` is meaningful only AFTER
  readiness.
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

Task W5 closed the Windows x86-64 gap. CI source-builds official Chez 10.4.1
under MSYS2 MINGW64 and runs all four gates — W1, W2, W3, and the W4 public
poller — through direct PowerShell/Chez on Windows x86-64. Every step must
observe a real child process exit code: a step that cannot see one now fails
rather than reporting green, because in a fresh step process `$LASTEXITCODE` can
be unset and `exit $null` exits 0.

The remaining Windows gap is **ARM64**. A non-gating native preview produces its
ABI artifact and proves that unreviewed selection fails closed, but no
descriptor is committed, so ARM64 cannot load descriptor-backed namespaces and
none of the socket suites run there. It stays a preview artifact until that
probe is reviewed into a descriptor and these same gates genuinely execute on
it. Do not infer an ARM64 descriptor from the x86-64 one.

Note also that ABI probing and socket-runtime evidence are separate claims. The
probe job proves the numbers; it opens no socket. A table check, a cross-build,
a namespace load, or a source-mode selection check is never evidence that a
socket was opened.

CI (`.github/workflows/ci.yml`) re-probes Linux x86_64, Linux aarch64, macOS
arm64, macOS x86_64, and Windows x86_64 on each push. A separate preview
produces Windows aarch64 evidence without promoting it to the committed table.
CI fails if a committed descriptor disagrees with the platform's real headers;
the Linux arm64 and Intel macOS runtime jobs also require their complete probes
to match their explicit descriptor aliases.
