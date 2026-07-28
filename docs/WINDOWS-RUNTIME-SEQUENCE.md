# Windows socket-runtime implementation sequence

This is the restart-safe task queue for bringing the native Windows socket
runtime up behind the existing `jolt.net` API. Each task is intended to be
assigned, reviewed, and integrated separately. Do not combine adjacent tasks
just because the later code looks nearby: the acceptance boundary of each task
is part of the design.

The primary path is a small Winsock implementation matching the proven POSIX
contracts. IOCP is a worthwhile alternate backend after this sequence has a
correct oracle; it is not a prerequisite for the first portable implementation.

## Live starting point

As of 2026-07-24:

- The authoritative native Windows library checkout is
  `D:\src\jolt-org\jolt-net`, clean at `4e7dc43`.
- `D:\src\jolt-proposal-net-runtime` began as a detached worktree of the
  proposal fork at `9dc88108`, with its submodules initialized. W1 must now be
  validated against `casselc/jolt` branch
  `codex/upstream-improvements-6-8` at `85f645aa` or later. That revision includes
  atomic native-error capture to the target, monotonic-clock, byte-slice, and
  FFI primitives jolt-net uses, plus the reviewed Windows build/provider fixes.
- Native Chez 10.4.1 is
  `D:\chez-10.4.1\bin\scheme.exe`.
- Native Windows Git is
  `C:\Program Files\Git\cmd\git.exe`.
- Git-for-Windows sh is
  `C:\Program Files\Git\bin\sh.exe`, but socket-runtime tests should invoke
  Chez directly from PowerShell and should not need a shell.
- The separate WSL checkout at `/home/chuck/ai-src/jolt-net` has pending
  cross-platform CI and ABI-evidence work. Do not overwrite, copy over, or
  reformat that checkout. Work on a dedicated branch in the clean native
  Windows checkout and return local commit IDs for integration.

The direct native launcher has been exercised successfully:

```powershell
$env:JOLT_PWD = "D:\src\jolt-org\jolt-net"
$env:JOLT_AOT_CACHE = "0"
$env:JOLT_VERSION = "dev"
$env:JOLT_SH = "C:\Program Files\Git\bin\sh.exe"
Set-Location "D:\src\jolt-proposal-net-runtime"
& "D:\chez-10.4.1\bin\scheme.exe" --script "host\chez\cli.ss" repl
```

It reports a native Windows x86-64 target, and jolt-net loads with
`jolt.ffi/errno-source` equal to `:wsa-get-last-error`. The first real loopback
attempt currently fails at a precise boundary:

```text
jolt.net resolve: Name resolution failed (code 10093)
```

`10093` is `WSANOTINITIALISED`. `listen` resolves its endpoint before
`socket-for` calls `ensure-subsystem!`, so `getaddrinfo` is the first Winsock
operation and runs before `WSAStartup`. This is the first implementation task,
not an environment or toolchain problem.

The ordinary `-M:test` alias also resolves the jolt-hegel Git dependency before
running any test and is exposed to the separate Windows Git-command work in the
core fork. Do not debug that problem in a jolt-net task. Task W1 adds a
dependency-free blocking-runtime alias so socket work can proceed
independently.

## Operating rules for every task

1. Use Windows Git as the authority for a checkout on `D:`. WSL Git can report
   a false dirty tree because of line-ending conversion.

   ```sh
   powershell.exe -NoProfile -ExecutionPolicy Bypass -Command \
     '$g="C:\Program Files\Git\cmd\git.exe"; & $g -C "D:\src\jolt-org\jolt-net" status --short --branch'
   ```

2. Run nontrivial Windows commands from a checked-in `.ps1` file. Invoke the
   script from WSL like this:

   ```sh
   powershell.exe -NoProfile -ExecutionPolicy Bypass \
     -File 'D:\src\jolt-org\jolt-net\tools\test-windows-blocking.ps1'
   ```

   The script must `Set-Location` to the native `D:` checkout before launching
   native tools. Do not launch them from a WSL UNC current directory, and do not
   use PowerShell merely to invoke bash and a chain of shell wrappers.

3. Set `JOLT_PWD` to a native Windows path, disable the AOT cache while editing,
   and invoke `scheme.exe --script host\chez\cli.ss` directly from the proposal
   fork worktree.

4. Preserve the public `jolt.net` API and the ownership, capture-before-cleanup,
   generation, registration-revision, and close-completion invariants. Resolve
   friction at the lowest appropriate layer; do not hide an absent primitive
   with an unsafe higher-level workaround.

   Every sentinel-returning foreign call whose error slot is consumed must
   declare `{:capture-native-error true}` and consume an explicit
   `[result native-error]` pair, whether or not it is `:blocking`. Keep scalar
   and captured call tables/APIs separate: one dispatch function must never
   return a scalar for some operations and a pair for others. `WSAStartup` is
   different because its return value is the error; error-independent
   `closesocket` may remain scalar. A later error accessor is not an atomic
   capture.

5. Do not weaken or skip POSIX tests. Run the Linux suite after the native
   Windows slice passes.

6. Work on one named branch per task, make bounded conventional commits, and
   report the branch, commits, exact commands, outputs, and final status. Do not
   push or open a pull request unless explicitly authorized.

7. Update `docs/PLATFORM-COVERAGE.md` only to the evidence actually obtained.
   `:candidate` means the code and native gate exist; `:runtime` means the gate
   ran green on that exact revision.

8. Update `docs/proofs/socket-invariants.md` and the bounded models whenever a
   task changes a concurrency or lifecycle invariant. Every model needs a
   corrected query, a deliberately buggy control that produces a witness, a
   non-vacuity control, source anchors, and a reproduction command.

If WSL reports `UtilBindVsockAnyPort socket failed 1` when starting a Windows
process, that is an execution/sandbox boundary, not a Jolt failure. Retry with
the required host-execution permission instead of changing the implementation.

## Task W1: initialize Winsock and prove the blocking socket base

Branch: `codex/windows-runtime-w1`

Status: accepted locally at revision `11142a3`. The native Windows x86-64 gate
passed 149/149 with no skips; Linux passed the dependency-free gate 146/146
with its one not-applicable Winsock skip and the full Hegel-required gate
222/222 with no skips. W2 may branch from this revision.

Scope:

- Make Winsock initialization precede every Winsock operation, including
  `getaddrinfo`.
- Make initialization linearizable. Concurrent first callers must perform
  exactly one successful `WSAStartup`; waiters must observe the same success or
  the same memoized failure. A check-then-reset atom is not a once-only
  protocol.
- Keep `WSAStartup`'s returned error code distinct from
  `WSAGetLastError`.
- Consume atomic `[result native-error]` returns for every sentinel-returning
  call whose error is classified, including socket creation, bind/listen,
  endpoint inspection, options, blocking and nonblocking calls, wake calls,
  `poll`, and `getaddrinfo`'s `EAI_SYSTEM` path. Preserve a disjoint scalar-only
  call API for error-independent operations.
- Keep subsystem ownership process-scoped. Individual sockets must not call
  `WSACleanup`.
- Exercise real native IPv4 listen, connect, accept, local/peer endpoint
  inspection, connection-refused and address-in-use classification, and
  idempotent `closesocket` ownership.
- Exercise IPv6 loopback when available, with an explicit skip when the host
  lacks `::1`.
- Add a dependency-free `:blocking-test` alias and a
  `tools/test-windows-blocking.ps1` runner. The test main should include target,
  address, resolver, and blocking socket coverage, but no Hegel, POSIX
  nonblocking calls, poller, or SIGPIPE child.
- Add the Winsock-initialization proof/model and controls described in the
  operating rules.

Likely files:

- `src/jolt/net/ffi.clj`
- `src/jolt/net/resolver.clj`
- `src/jolt/net/error.clj`
- `test/jolt/net/socket_test.clj`
- a small blocking-only test main under `test/jolt/net/`
- `deps.edn`
- `tools/test-windows-blocking.ps1`
- `docs/PLATFORM-COVERAGE.md`
- `docs/proofs/socket-invariants.md`
- `docs/proofs/models/`

Out of scope:

- `ioctlsocket`, nonblocking byte I/O, nonblocking connect, `WSAPoll`, poller
  registration, wakeup, blocked-accept cancellation, IOCP, jolt-tcp, and
  jolt-http.
- The core fork's Git dependency/process work.
- A claim that closing a listener safely cancels a currently blocked native
  Windows `accept`; that requires the later readiness lifecycle.

Acceptance:

- The recorded `10093` witness is gone because initialization occurs before
  resolution, not because the test bypasses resolution.
- A concurrent first-use stress test proves one initialization attempt.
- Real loopback endpoints agree on both sides and a port-zero listener reports
  the kernel-selected port.
- A refused connect preserves `10061`; a duplicate bind preserves `10048`; both
  are captured before rollback cleanup.
- The refused-connect assertion is a required pass, not a Windows skip.
- Repeated close performs exactly one native close and use-after-close fails
  clearly.
- The dependency-free blocking suite passes through the PowerShell runner.
- The complete Linux suite remains green.
- The branch is locally committed and clean.

Stop after W1 and return the evidence. Do not begin W2.

## Task W2: Windows nonblocking transitions and byte I/O

Branch `claude/windows-nonblocking-io` from reviewed W1
`f0affc4fa80ac00313860789cc8b894564ecc3b1`.

Status: accepted locally at revision `2cbd988`, branched from `8a5b3dd` (the
docs-only descendant of the reviewed W1 commit that clarified this task's
`ioctlsocket` acceptance bullet). Native Windows x86-64 passed the W2 gate
55/55 and the W1 blocking gate 155/155, both with no skips. Linux passed the
full Hegel-required gate 228/228 with no skips, the dependency-free W2 gate
53/53 with no skips, and the dependency-free W1 gate 152/152 with its one
not-applicable Winsock skip. All 31 bounded models were run through a standalone
z3 5.0.0 and matched their declared verdicts, but independent source/model
review found the generic Windows transition model stronger than production.
The combined W1/W2 branch now splits POSIX read-back from the conditional
Windows contract, bringing the directory to 33 models; Chiasmus rechecked all
six affected files with the expected verdicts. W3 must branch from the reviewed
combined tip so it inherits that correction and the later W1/macOS CI repairs,
not directly from `2cbd988`.

Implement and probe `ioctlsocket(FIONBIO)`, Windows nonblocking accept/connect,
`recv`, `send`, and `getsockopt(SO_ERROR)`. Preserve the existing value contract
for would-block, EOF, in-progress, and connected states. Add real partial-slice,
EOF, half-close, refused-connect, ownership-after-failed-completion, and
capture-before-cleanup tests. Every failure-sensitive native call must consume
an explicit captured result; a separate post-call last-error read is not an
acceptable Windows implementation. Record the honest cross-platform boundary:
POSIX has a per-handle read-back proof; Windows trusts probed ABI facts plus the
documented FIONBIO contract and uses later would-block behavior as conformance
evidence. Do not add a poller in this task.

Add a dependency-free Windows W2 test main and direct PowerShell runner rather
than loading the POSIX poller suite or invoking a bash wrapper. From WSL, invoke
native Windows only through:

```bash
/mnt/c/Windows/System32/WindowsPowerShell/v1.0/powershell.exe \
  -NoProfile -ExecutionPolicy Bypass \
  -File 'D:\src\jolt-org\jolt-net\tools\test-windows-nonblocking.ps1' \
  -JoltNetPath 'D:\src\jolt-org\jolt-net' \
  -RuntimePath 'D:\src\jolt-proposal-net-runtime' \
  -ChezExe 'D:\chez-10.4.1\bin\scheme.exe' \
  -TimeoutSeconds 90
```

The runner must set its native working directory and invoke
`scheme.exe --script host\chez\cli.ss` directly. Sync the `D:` checkout with
native Git for Windows, not PowerShell-to-bash indirection.

Acceptance:

- `ioctlsocket(FIONBIO)` and the width of its `u_long` argument are probed from
  Windows headers; production marks the handle only after a successful return,
  and native behavior tests establish cross-boundary conformance by observing
  accept/read report would-block. Those observations occur after marking and
  are not represented as an in-process guard.
  Do not invent a nonexistent portable getter for Windows nonblocking mode;
- accept/read before readiness return `would-block`, zero-length read alone
  returns zero, and peer half-close returns EOF;
- sliced send/receive preserve both array offsets and byte counts;
- immediate and in-progress connect remain distinct, and `SO_ERROR` decides
  completion rather than readiness;
- refused completion preserves exact `10061` and leaves the returned socket
  caller-owned until one explicit close;
- all sentinel/error consumers use captured pairs, including the first use of
  each binding;
- the native dependency-free W2 gate and the complete Hegel-required Linux
  suite pass under watchdogs; and
- the separate POSIX and Windows proof/model trios, source anchors, platform
  evidence, branch commits, and clean status are recorded.

### Evidence boundary handed to W3

- There is **no readiness backend on Windows**. `try-connect` initiates and
  `finish-connect!` completes, but nothing in production waits between them.
  The W2 gate closes that gap only with test-local coordination: a server's
  blocking `accept` as a real synchronization point where one exists, and
  otherwise a bounded wait for a terminal VALUE whose exhaustion fails the
  assertion. W3 owns the readiness-driven native integration and its evidence.
- A listener switched to non-blocking mode by `try-accept` cannot safely re-enter
  the Windows native blocking-accept path. W2 now rejects that mixed state with
  `:jolt.net/requires :windows-readiness` instead of returning would-block from
  an API that promises to block. This is a sequential check, not an atomic
  mode/admission gate: concurrent `accept` and `try-accept` on one Windows
  listener remain unsupported. W3 owns the readiness-driven replacement.
- Successful `ioctlsocket(FIONBIO)` under the probed ABI and documented Winsock
  contract is the in-process mark boundary. The later would-block gate is
  conformance evidence. `WSAPoll` does not become a blocking-mode getter, so W3
  must not use readiness to resurrect the rejected generic proof.
- The two Windows runners were exiting 0 unconditionally before this task, so
  any earlier "green" from them was the suite's printed text rather than a
  process exit code. Both are fixed here; treat pre-W2 runner exit codes as
  uninformative.

Stop after W2 and return the evidence.

## Task W3: `WSAPoll` readiness backend

Branch `claude/windows-wsapoll` from the reviewed, published combined W1/W2 tip.

Status: implemented locally on `claude/windows-wsapoll`, branched from the
reviewed combined tip `5554d04`. Native Windows x86-64 passed the new W3 WSAPoll
gate 116/116, the W2 gate 56/56, and the W1 gate 162/162, all with no skips and
an observed exit code of 0, against native Chez 10.4.1 and the pinned Jolt fork
`85f645aa`. Linux passed the full Hegel-required suite 235/235 with no skips,
the dependency-free W2 gate 53/53, and the dependency-free W1 gate 159/159 with
its one not-applicable Winsock skip; the W3 alias correctly SKIPS on a non-Windows
target rather than proving POSIX behavior under a Windows name. All 35 bounded
models were run through a standalone z3 5.0.0 and matched their declared
verdicts (13 unsat, 22 sat).

Post-W3 review found that the wake-less close refusal read `:awaiting?` before
its lifecycle CAS loop. An await could enter in between, after which close would
transition to `:closing` and wait with no wake mechanism. The review branch
`codex/windows-wsapoll-review` moves the refusal onto the exact lifecycle value
used by the CAS, adds a deterministic admitted-await gate, and records
buggy/corrected/non-vacuity models. Chiasmus reports `sat`/`unsat`/`sat` with the
corrected four-label core recorded in the model index. On reviewed revision
`2bd40ff`, native Windows W1, W2, and the expanded W3 gate passed 162/162,
56/56, and 124/124 respectively with observed exit code 0. The Linux full suite
also passed 235/235 with Hegel required.

The public Windows poller is deliberately still fail-closed. Without an
owner-independent wake transport, explicit `wake!`, blocked-await cancellation,
and terminal close against a blocked await cannot be implemented honestly, so
they refuse rather than degrade, and W3's evidence comes from the internal
`jolt.net.poller/open-readiness-adapter`. That adapter is not a second state
machine: it is the same registration, token, snapshot, and stale-event code
constructed with a nil wake transport. W4 supplies the transport and should
promote the public poller.

Two WSAPoll behaviors found by execution are worth carrying into W4. A peer FIN
with no pending data reports `POLLHUP` alone rather than `POLLIN`, so a caller
acting only on `:read` never sees EOF. An unrecognized handle fails the ENTIRE
`WSAPoll` call with `WSAENOTSOCK` rather than marking one entry `POLLNVAL`,
which makes the handle lease held across the native wait load-bearing on Windows
in a way it is not on POSIX.

Probe and commit `WSAPOLLFD` size, field widths/offsets, readiness flag values,
`WSAPoll` signature, and timeout/error behavior from native Windows headers and
execution. Add a Windows readiness adapter underneath the existing poller API;
do not fork a second registration/token state machine. The shared layer must
continue to own generation-bearing and revision-bearing tokens, complete-token
validation after the native wait, stale-event rejection, event normalization,
one caller-owned absolute monotonic deadline, and captured native errors.

W3 is the readiness slice, not the wake/close slice. Factor the backend seam and
exercise real `WSAPoll` without weakening existing mutation acknowledgement,
explicit `wake!`, or close-completion contracts. If those public lifecycle
operations cannot be implemented honestly without a Windows wake transport,
keep the public Windows poller fail-closed for them and test the internal
readiness adapter directly; W4 will wire the owner-independent wake transport.
Do not emulate a wake with sleeps, invent a blocking-mode getter, or claim that
finite polling is cancellation completion.

Acceptance:

- native `WSAPOLLFD` probe output is committed and the descriptor drift gate
  checks it byte-for-byte after line-ending normalization;
- real Windows sockets cover read, write, error, hangup/EOF, zero timeout,
  positive timeout, and captured failure behavior, with exact event
  normalization documented where Winsock differs from POSIX;
- a registration update/removal that invalidates a captured snapshot cannot
  dispatch its stale generation/revision token; current-token readiness remains
  non-vacuously deliverable;
- a real in-progress connect is awaited for write/error/hangup readiness and
  only `finish-connect!`/`SO_ERROR` decides success or the exact refused code;
- the interim W2 failure for blocking `accept` on an already non-blocking
  Windows listener is either replaced by a readiness-driven implementation with
  an honest lifecycle, or retained explicitly—never silently converted into
  would-block; concurrent blocking/nonblocking accept admission remains
  explicitly unsupported unless W3 makes it atomic;
- at least one real short read is forced by sending fewer bytes than requested
  after readiness; offset/count preservation and partial-progress composition
  remain green;
- Windows W1, W2, and W3 dependency-free gates, Linux full Hegel-required tests,
  and all affected proof/model controls pass under watchdogs;
- proofs/docs distinguish the WSAPoll adapter's proven token/event invariants
  from the W4 wake, blocked-await cancellation, and close-completion obligations;
  and
- the branch is committed and clean, with exact commands/results reported and
  no push or PR unless explicitly authorized.

Stop after W3 and return the evidence.

## Task W4: owner-independent Windows wake and close lifecycle

Branch from the clean reviewed W3 tip, not Claude's original W3 tip:
`claude/windows-poller-wake`.

Status: implemented locally on `claude/windows-poller-wake`, branched from the
reviewed W3 tip `08b24ea`. Every gate below was run on revision `38ff1db`; the
only commit after it is this prose record of the results, which touches no
source, test, tool, or model file. Native Windows x86-64 passed the new W4 public-poller
gate 73/73, the W3 gate 124/124, the W2 gate 56/56, and the W1 gate 162/162, all
with no skips and an observed exit code of 0, against native Chez 10.4.1 and the
pinned Jolt fork `85f645aa` (worktree `D:\src\jolt-proposal-net-runtime-w3`). A
local MinGW re-run of `tools/probe-constants.c` showed no drift from the
committed `tools/probed/windows-x86-64.edn`. Linux passed the full
Hegel-required suite 235/235 with no skips, plus the dependency-free W1, W2, W3,
and W4 aliases; the W3 and W4 aliases correctly SKIP on a non-Windows target
rather than proving POSIX behavior under a Windows name. All 48 bounded models
were run through a standalone z3 5.0.0 and matched their declared verdicts (17
unsat, 31 sat), and the three new corrected models were independently re-checked
through Chiasmus with identical verdicts and identical unsat cores.

The transport is a connected IPv4 loopback datagram pair. The hypothesis was
validated rather than assumed: `WSAPoll` accepts only `SOCKET`s, and a
non-socket handle in its array can fail the whole call with `WSAENOTSOCK`
instead of marking one entry, so both ends being real sockets is the property
that matters. Two things had to be true that a POSIX-shaped design would have
got wrong, and the native gate is what established them:

- **Retiring a datagram sender is invisible to its peer.** There is no hangup.
  Cancellation therefore rests entirely on a terminal BYTE that close publishes
  while sends are still admitted, never on peer close.
  `jolt.net.wake/terminal-wake` names this difference (`:byte-only` versus
  POSIX's `:byte-or-hangup`) so it stays a transport fact rather than an
  assumption buried in close.
- **Publishing the byte is not sufficient on its own.** An await's pre-snapshot
  drain can legitimately consume it and then park with nothing left, and the
  epoch-restore cannot help once admission is retired. `await-ready` therefore
  re-reads the lifecycle after its last drain and before the native call. On
  POSIX this defect is invisible, because the retired pipe still supplies
  `POLLHUP` — which is exactly why it had to be found here.

Two W2/W3 assertions described boundaries this task moved, and both were updated
in place rather than deleted, so W2 stays at 56 and W3 at 124: the Windows
mixed-mode blocking-accept refusal now asserts that accept SUCCEEDS, and W3's
"the public Windows poller is still fail-closed" now asserts that it carries a
wake transport. The wake-less readiness adapter and all of its refusals are
retained untouched.

### Evidence boundary handed to W5

`PLATFORM-COVERAGE.md` stays at **candidate** for Windows x86-64. Every gate
above ran locally; hosted Windows CI has not run this revision, and turning the
PowerShell runners into a hosted gate is W5's work, not a claim W4 may make.

Add an owner-independent Windows wake transport and promote the shared poller
through `jolt.net/open-poller`. A connected IPv4 loopback datagram pair is the
initial design candidate because both ends are real `SOCKET`s that `WSAPoll`
understands; probe its behavior under the real gate rather than treating that
choice as settled. Do not substitute finite polling, sleeps, a thread-owned
lock, or a second registration/token state machine.

Keep transport policy out of the shared state machine. In particular, the
POSIX wake path currently names `read`/`write`, whereas a Windows socket wake
must use the exact captured `recv`/`send` surface and Windows length/result
widths. Give the poller a small internal wake-transport value or equivalent
seam owning:

- the read and write handles;
- non-blocking signal and drain operations with captured native errors;
- construction rollback and terminal retirement; and
- any target-specific behavior needed to encode the wake handle in the existing
  readiness array.

Preserve one shared lifecycle, mutation queue, wake epoch, registration token,
and readiness decoder. Both datagram sockets must be non-blocking before they
are published. A one-byte wake send must be admitted under the same
owner-independent CAS state as close; release the handle lease before
decrementing the admitted-writer count. Close must publish a terminal wake
before retiring later sends, wait for admitted sends to drain, retain the
receiver through the active `WSAPoll` lease, wait for that await to exit, and
only then retire the receiver and return `:closed`. Datagram peer close is not a
hangup oracle, so the protocol must rely on the admitted terminal byte rather
than pretending sender close wakes the receiver.

Promoting Windows into the public poller set also promotes the readiness-driven
blocking `accept` path. Remove the W2 mixed-mode refusal only when the native
tests prove the same close callback, terminal wake, short-lease, and
descriptor-reuse contract already required on POSIX. Do not leave Windows
listeners on native blocking `accept` while claiming poller-backed close.

Acceptance:

- a dependency-free native W4 PowerShell gate exercises the public
  `jolt.net/open-poller`, including an empty poller, explicit wake, blocked
  update/removal, registered-socket close, and terminal poller close;
- forced interleavings cover close against an admitted wake send, a wake in the
  drain/reset window, await admission racing close, and receiver-handle
  retirement; no test uses elapsed sleep as its success oracle;
- native Windows blocking `accept` is readiness-driven, remains correct after a
  prior `try-accept`, and listener close is a bounded completion boundary;
- repeated public-poller and accept open/close stress leaves no owned socket or
  wake-handle leak and never passes by an unobserved child-process exit code;
- W1 162, W2 56, reviewed W3 124, the new W4 gate, Linux full
  Hegel-required tests, and all dependency-free Linux gates pass at the exact
  source revision;
- the existing wake-pair and wake-epoch models are generalized only where the
  implementation premise is genuinely transport-independent. Pipe/SIGPIPE and
  datagram-terminal-wake claims stay distinct. Every corrected model retains a
  satisfiable buggy control, a satisfiable useful/non-vacuity control, source
  anchors, bounded limits, and a decisive named core;
- platform coverage remains `candidate` until hosted Windows CI runs this exact
  implementation; and
- the branch is committed and clean, with exact native commands, counts,
  revision pins, and proof verdicts reported. Do not push or open a PR.

Stop after W4 and return the evidence.

## Task W5: native Windows CI promotion

Branch from reviewed W4: `claude/windows-runtime-ci`

Turn the local PowerShell runner into a Windows x86-64 GitHub Actions runtime
gate using native Chez/Jolt. Keep ABI probing distinct from socket-runtime
claims. Add Windows ARM64 runtime only after its native probe artifact has been
reviewed into a descriptor and the same tests actually run there. Retain
dependency-free socket gates so a property-test artifact download cannot erase
runtime coverage.

Promote `PLATFORM-COVERAGE.md` cells from candidate to runtime only after the
corresponding hosted job is green on the exact revision.

Stop after W5 and return the evidence.

### W5 results

The `windows-portable` job was updated in place rather than replaced, keeping
its working toolchain: MSYS2 MINGW64 source build of official Chez 10.4.1, the
exact proposal Jolt pin `85f645aa`, direct native PowerShell/Chez runners,
dependency-free aliases, no AOT cache, and no jolt-hegel or artifact download
anywhere in the socket path. It is now named for what it proves, and the stale
comments claiming Windows lacked a wake transport and that the public poller was
fail-closed are gone. All four gates run, W4 at its documented 300 s outer
timeout.

Hosted result on revision `0e66bcf`, [CI run
30169225227](https://github.com/casselc/jolt-net/actions/runs/30169225227), all
jobs green:

| Gate | Count | Timeout | Exit |
|---|---|---|---|
| W1 blocking sockets | 162/162 | 90 s | 0 |
| W2 non-blocking I/O | 56/56 | 90 s | 0 |
| W3 `WSAPoll` readiness | 124/124 | 240 s | 0 |
| W4 public poller and wake | 73/73 | 300 s | 0 |

Exit codes are load-bearing rather than incidental. `if ($LASTEXITCODE -ne 0)`
alone was unsound: in a fresh step process the variable can be unset, and
`exit $null` exits 0, so a step that never launched its suite would have
reported green. Each step now refuses to report success when it cannot see a
child exit code.

Hosted CI immediately caught two defects that had been latent since W2/W3 and
were invisible because those commits had never run through it:

- The Windows ABI probe failed to link with `undefined reference to WSAPoll`.
  W3 made the probe take that function's address to pin its declared signature,
  which requires the symbol to resolve even though no socket is opened. The
  ARM64 preview already linked the MSVC equivalent; the x86-64 step had no
  matching flag. Fixed with a per-target matrix key.
- The committed Darwin descriptor was missing `:ioctl-cmd-bytes`,
  `:ioctl-arg-bytes`, and `:fionbio`. `28bde52` added those probe fields for W2
  and regenerated the Linux and Windows evidence but not Darwin's, so every
  fresh Darwin probe reported three fields the committed file lacked. The W1/W2
  line fixed this in `7de096d`; this branch forked one commit earlier at
  `5554d04` and never picked it up. Refreshed from the artifact a real macOS
  arm64 runner produced at this revision.

Windows ARM64 was deliberately **not** promoted. Its probe artifact is still
unreviewed, no descriptor is committed, and none of these suites run there.
(Task W7 below did that promotion later, on those terms.)

Not taken from `7de096d`: its `src/jolt/net/target.clj` change relabelling the
Linux aarch64 and Darwin x86-64 descriptors from `:inferred-from-*` to
`:probed`, with the matching `target_test.clj` assertions. That is a production
evidence-semantics change and is out of scope for a CI promotion.

## Task W6: downstream integration

Only after W1-W5 are reviewed:

1. Rebase `jolt-tcp` onto the reviewed jolt-net revision and delete duplicated
   socket/FFI logic in bounded slices.
2. Rebase `jolt-http` onto that jolt-tcp revision; use jolt-net or raw FFI only
   for a narrowly justified missing transport primitive.
3. Keep `jolt-hegel` separate and use the reviewed core FFI primitives directly.
4. Re-run nREPL, Maven/dependency resolution, Teensyp/Capra compatibility, and
   ecosystem validation as consumers rather than as jolt-net implementation
   shortcuts.

## Task W7: native Windows ARM64 runtime promotion

Branch from reviewed W6 tip: `claude/windows-arm64-runtime`

W5 left one Windows lane unpromoted, with the conditions for promoting it
written down: review the native probe artifact into a committed descriptor, and
make the same suites actually run there. W7 does exactly that and nothing more.

### Did W7 change semantics?

No. This was the first question asked, because the answer determines whether new
proof models are owed.

Windows ARM64 is another member of the **existing Windows contract family**, not
a new contract. The evidence:

- The native ARM64 probe is byte-identical to the Windows x86-64 probe apart
  from the `:arch` label. Every fact the Winsock code branches on — `SOCKET`
  width, `long`/`u_long` widths, the `FIONBIO` signed representation, the
  address-length width, `WSAPOLLFD` size and offsets, the `WSAPoll` count and
  timeout types, every `POLL*` value, every `WSAE*` code, and both `sockaddr`
  layouts — is the same number.
- No production source file changed. `src/jolt/net/target.clj` gained a
  descriptor-table entry; no readiness, wake, lifecycle, or error code was
  touched.
- The four suites ran unmodified. The only script change is a `-ShellExe`
  parameter, which moves a hardcoded `JOLT_SH` path into a parameter and alters
  no assertion.

So the correct action was **not** to invent a new SMT model. Inventing one would
have implied a distinct ARM64 behavior that was not observed, which is its own
kind of dishonesty. Instead the complete existing suite was rerun, and the
Windows models' applicability to ARM64 is argued below from what they quantify
over.

### Why the Windows W1–W4 models apply to ARM64

Each Windows-relevant model family is stated over an abstraction that names no
architecture. Concretely:

| Family | What it quantifies over | Architecture-dependent? |
|---|---|---|
| `winsock-init-once-*` | one CAS gate, attempt count, terminal memoized outcome | no |
| `windows-nonblocking-contract-*` | binding declaration, command representation, nonzero `u_long`, mark ordering | only through the *values* of the FIONBIO command and `u_long` width, which the ARM64 probe supplies identically |
| `readiness-*` | registration presence and complete-token (generation, revision) comparison | no |
| `windows-wakeless-close-race-*` | lifecycle CAS shared by guard and transition, in the wake-less adapter | no |
| `windows-terminal-wake-*` | publish-before-retire ordering, level-triggered terminal byte, drain/park interleaving | no |
| `close-completion-ordering-*`, `wake-pair-*`, `wake-epoch-*`, `wake-receiver-lease-*`, `wake-cursor-ordering-*` | admission gating, lease lifetime, epoch arithmetic, cursor ordering | no |
| `accept-terminal-close-*`, `connect-ownership-completion-*`, `idempotent-close-*`, `errno-capture-ordering-*`, `short-lease-post-close-*` | ownership, close, and error-capture ordering | no |

Only `windows-nonblocking-contract-*` has any architecture-sensitive premise at
all, and it is sensitive to *numbers* rather than to an instruction set: the
model assumes `ioctlsocket`'s command is representable in the declared `long`
and that the argument cell is a nonzero `u_long` of the probed width. The ARM64
probe reports `:ioctl-cmd-bytes 4`, `:ioctl-arg-bytes 4`, and
`:fionbio -2147195266` — the same values the model was discharged against on
x86-64. The premise therefore holds here for a checked reason, not by analogy.

### Assumptions the native gate discharges

The models are bounded counterexample queries over an abstraction; they assume
things about the machine that only a machine can settle. Running W1–W4 natively
on ARM64 discharges these, and no others:

1. **The probed facts are the facts this machine's Winsock uses.** The
   descriptor is byte-compared against a freshly executed ARM64 probe twice per
   run — once in the drift gate, once on the runtime runner itself.
2. **The ABI marshalling those facts describe actually works here.** A
   pointer-width `:uptr` `SOCKET`, 16-bit `WSAPOLLFD` event words at offsets
   8/10, and a signed `long` FIONBIO command are all exercised through real
   calls, not merely compared as integers.
3. **The wake transport really delivers on this platform.** The connected IPv4
   loopback datagram pair, and the terminal-byte close protocol the
   `windows-terminal-wake-*` family models, are driven by W4.
4. **The `WSAPoll` behavioral divergences hold here too** — FIN-with-no-data
   reporting `POLLHUP` alone, and an unrecognized handle failing the whole call
   rather than marking one entry. W3 asserts both rather than assuming them.
5. **No handle or wake-handle leak accumulates** across repeated open/close
   stress. The initial W7 oracle still sampled numeric handle allocation and did
   not fully discharge this premise; W7.1 replaces it with the process resource
   count and live calibration described below.

What the gate does **not** discharge: anything about packaged `joltc`, AOT
images, or the property layer, none of which run on Windows ARM64.

### Architecture is not taken on trust

The runner image ships an x86_64 MinGW gcc, and Windows-on-ARM executes x64
binaries under emulation, so a lane could produce entirely x86-64 evidence while
sitting on an ARM machine. Six witnesses must agree before a socket is opened;
they are tabulated in `docs/PLATFORM-COVERAGE.md`. Two are worth repeating here
because they are the only ones that do not consist of a process describing
itself: the compiled probe's and `scheme.exe`'s COFF machine fields, read with
`dumpbin` and matched as `machine (ARM64)` *with* the closing parenthesis, so
ARM64EC — an x64-compatible ABI — is rejected rather than accepted as close
enough.

Note honestly that witnesses 5 and 6, Chez's `(machine-type)` and
`jolt.host/target`, are **not independent**: the core derives `:arch` from
`(machine-type)` through an exact allowlist. They are listed as two checks
because they fail at two different layers, not because they are two facts.

### One observed ARM64/x86-64 difference

The core's machine-name allowlist maps `tarm64nt` to `:abi :unknown`, where
`ta6nt` maps to `:abi :win64`. jolt-net reads only `:os`, `:arch`, and
`:pointer-bits`, and `:abi` is consumed nowhere outside the core's own AOT cache
key, so this is informational rather than load-bearing. It is asserted on the
gate anyway, so that if `:abi` ever starts selecting a calling convention, ARM64
fails loudly instead of silently selecting `:unknown`.

### W7 results

Hosted result on final W7 revision `5da1855`, [CI run
30313054115](https://github.com/casselc/jolt-net/actions/runs/30313054115), all
13 jobs green — including the pre-existing x86-64, Linux, and macOS gates, none
of which were weakened to get ARM64 there.

Native `tarm64nt` Chez 10.4.1 built from source on `windows-11-vs2026-arm`,
proposal core `46e1f74f`, source mode, `JOLT_AOT_CACHE=0`:

| Gate | Count | Timeout | Exit |
|---|---|---|---|
| W1 blocking sockets | 184/184 | 90 s | 0 |
| W2 non-blocking I/O | 56/56 | 90 s | 0 |
| W3 `WSAPoll` readiness | 124/124 | 240 s | 0 |
| W4 public poller and wake | 73/73 | 300 s | 0 |

Zero failures and **zero skips** in all four, and the counts are identical to
what the x86-64 lane produced in the same run — which is the expected result
when the same suites run against the same ABI facts, and would have been
suspicious had it come out any other way.

The six architecture witnesses as actually observed:

```text
runner.arch      = ARM64
PROCESSOR_ARCH   = ARM64
msvc-arm64-env: VSCMD_ARG_TGT_ARCH=arm64 VSCMD_ARG_HOST_ARCH=arm64
            AA64 machine (ARM64)      <- probe-windows-aarch64.exe
            AA64 machine (ARM64)      <- tarm64nt\bin\tarm64nt\scheme.exe
chez version: 10.4.1
chez machine-type: tarm64nt
jolt.host/target: {:os :windows, :arch :aarch64, :abi :unknown, :libc :unknown,
                   :endian :little, :pointer-bits 64, ...}
OK:  windows-aarch64.edn matches this machine's real headers byte-for-byte
```

Note the MSVC line: the toolchain selected was **host arm64, target arm64** —
fully native, no emulation. The committed descriptor was originally produced by
the `x64_arm64` cross toolchain in the earlier preview, and the native toolchain
reproduced it byte-for-byte, which is an unplanned but useful corroboration.

The initial W7 report misidentified the following output as process-handle
counts. Source review of exact revision `5da1855` found that
`handle-probe!` opened a listener and returned `net/native-handle`: these are
**socket handle allocator positions**, not counts. They showed no catastrophic
one-way movement during the loops, but they did not prove that every resource
was returned, and their static “noise separation” check was not a non-vacuity
test of the measurement itself:

```text
ok  accept cycles leak no handles (40 cycles, before 484, after 484,
      signed delta 0, a real leak would advance ~1280, refusing at 640)
ok  accept cycles leak no handles: the oracle still separates noise from a leak
      (noise budget 150, leak floor 640)
ok  poller open/close cycles leak no handles (60 cycles, before 512, after 512,
      signed delta 0, a real leak would advance ~960, refusing at 480)
ok  poller open/close cycles leak no handles: the oracle still separates noise
      from a leak (noise budget 150, leak floor 480)
ok  50 failed initiations leak no descriptors
      (before 480, after 484, delta 4, allowance 100)
```

Proof suite: 51 files, unchanged, rerun with standalone `z3` 4.8.12 — 18 `unsat`
and 33 `sat`, every file matching its declared verdict.

### An unresolved pre-existing flake, found but deliberately not "fixed"

A confirming run of the same tree (docs-only diff) hit this on **Windows
x86-64**, in W2:

```text
FAIL  50 failed initiations leak no descriptors
        (before 416, after 516, delta 100, allowance 100)
```

It is recorded here rather than quietly re-run away, and it is **not** an ARM64
finding — the identical assertion passed on ARM64 in the same commit.

The check compares the *handle value* of a fresh listen socket before and after
50 rejected initiations. On Windows those values advance in steps of 4 and
wander with unrelated process activity, so the assertion carries a noise
allowance of `2 * attempts` = 100 against a real-leak floor of roughly
`4 * attempts` = 200. The observed 100 is ambient churn landing exactly on the
allowance boundary, not a leak: a genuine leak of 50 sockets would have to show
about 200. The x86-64 lane measured 0, 0, and 8 on the three preceding runs, and
an immediate re-run of this very job measured 36 and passed; ARM64 measured 4 on
the same commit.

The right fix is to use a process handle **count** with an explicit noise
budget, a leak floor, and a live non-vacuity assertion that deliberate sockets
raise the count — rather than to widen the allowance. Widening it is exactly
the kind of gate-weakening this sequence forbids. W7 therefore left the finding
as-found; the bounded W7.1 follow-up below fixes both W2 and W4.

One defect was found and fixed during the task, and it is worth recording
because it was a check that could never have passed: the machine-type witness
originally used `scheme.exe --eval`, and Chez has no `--eval` flag — it tried to
open the string as a file. The gate failed closed, which is the right behavior
for an unusable check, but it meant the witness was decorative until it was
rewritten to ask through a one-line `--script`. Fixed in `0e4265e`.

## Task W7.1: resource-count leak oracle correction

Source review after W7 compared its report to the exact test implementation and
found the evidence mismatch above. Revision `1090b3e` replaced every
Windows-only allocator-position leak check in W2 and W4 with a small test-only
binding to `GetCurrentProcess` plus `GetProcessHandleCount`.

The measurement has two complementary guards:

1. Each suite opens six real loopback listeners concurrently and requires the
   process count to rise by at least six. A constant counter, wrong process
   handle, or counter that excludes sockets therefore fails rather than making
   every zero delta look healthy.
2. The visible ambient-noise budget is 10 handles and must remain strictly
   below the modeled leak floor. The measured absolute drift must stay within
   that budget; a large negative drift therefore fails as inconclusive instead
   of masking retained handles. W2's 50 rollback attempts have a leak floor of
   25.
   W4 was subsequently strengthened at `c374738` to model the minimum systematic
   defect — **one** retained handle per cycle — rather than only a complete
   operation leaking all of its owned handles. Its 60 poller cycles have a
   leak floor of 30 and its 40 accept cycles a floor of 20; both still permit
   only the explicit absolute-noise budget of 10.

The first hosted correction run, revision `1090b3e`, [CI run
30314204823](https://github.com/casselc/jolt-net/actions/runs/30314204823),
passed all 13 jobs. Both Windows architectures produced identical gate counts:

| Gate | Count | Exit |
|---|---:|---:|
| W1 blocking sockets | 184/184 | 0 |
| W2 non-blocking I/O | 58/58 | 0 |
| W3 `WSAPoll` readiness | 124/124 | 0 |
| W4 public poller and wake | 74/74 | 0 |

On x86-64 W2 measured 134 → 134, W4 pollers 135 → 135, and W4 accepts
135 → 135. On ARM64 the corresponding measurements were 135 → 135,
136 → 136, and 136 → 136. Every delta was zero; both runs first observed the
six deliberately open sockets. The stronger one-handle-per-cycle thresholds
retain the same assertion counts. Revision `8681535` then passed all 13 jobs in
[CI run
30322347132](https://github.com/casselc/jolt-net/actions/runs/30322347132),
including those stronger thresholds on both Windows architectures and all four
POSIX runtime suites against the publicly mirrored v0.5.7 Hegel integration.

This correction changes test instrumentation and evidence only. It changes no
production source, ABI descriptor, ownership transition, readiness behavior, or
wake ordering, so no new solver model is owed. The complete unchanged model
suite was nevertheless rerun: 51 files under standalone Z3 4.8.12, 18 `unsat`
and 33 `sat`, matching the W7 verdict totals.

## Alternate backends after the portable path

Once W1-W5 provide a behavioral oracle, the poller SPI can host follow-on
implementations:

- Windows IOCP and overlapped Winsock;
- Linux `io_uring`;
- Darwin `kqueue`, libdispatch, or `dispatch_io`.

Those are performance and scalability backends. They must preserve the same
owned-handle, error, token, wake, cancellation, and close contracts and pass the
same black-box suite before replacing the portable backend.
