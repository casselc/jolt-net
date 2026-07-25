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
  `codex/upstream-improvements-6-8` at `e749f154` or later. That revision adds
  atomic native-error capture to the target, monotonic-clock, byte-slice, and
  FFI primitives jolt-net already used.
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
not-applicable Winsock skip. All 31 bounded models were run through a
standalone z3 5.0.0 and each matched its expected verdict. W3 may branch from
this revision.

Implement and probe `ioctlsocket(FIONBIO)`, Windows nonblocking accept/connect,
`recv`, `send`, and `getsockopt(SO_ERROR)`. Preserve the existing value contract
for would-block, EOF, in-progress, and connected states. Add real partial-slice,
EOF, half-close, refused-connect, ownership-after-failed-completion, and
capture-before-cleanup tests. Every failure-sensitive native call must consume
an explicit captured result; a separate post-call last-error read is not an
acceptable Windows implementation. Extend the nonblocking-transition proof so
the postcondition is platform-neutral. Do not add a poller in this task.

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
  and native behavior tests prove that accept/read then report would-block.
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
- the proof/model trio, source anchors, platform evidence, branch commits, and
  clean status are recorded.

### Evidence boundary handed to W3

- There is **no readiness backend on Windows**. `try-connect` initiates and
  `finish-connect!` completes, but nothing in production waits between them.
  The W2 gate closes that gap only with test-local coordination: a server's
  blocking `accept` as a real synchronization point where one exists, and
  otherwise a bounded wait for a terminal VALUE whose exhaustion fails the
  assertion. W3 owns the readiness-driven native integration and its evidence.
- A listener switched to non-blocking mode by `try-accept` makes a subsequent
  blocking `accept` report would-block on Windows, because `accept` there is
  still the native blocking call rather than the POSIX close-wakeable poller
  path. Reconciling those two paths belongs to W3's readiness lifecycle. No
  test in this slice depends on the current behavior.
- The `ioctlsocket(FIONBIO)` postcondition is behavioral, not read back. Winsock
  exposes no portable getter, so if W3 introduces one through `WSAPoll` state or
  another mechanism, `jolt.net.nonblocking/postcondition-kind` is the seam to
  revisit.
- The two Windows runners were exiting 0 unconditionally before this task, so
  any earlier "green" from them was the suite's printed text rather than a
  process exit code. Both are fixed here; treat pre-W2 runner exit codes as
  uninformative.

Stop after W2 and return the evidence.

## Task W3: `WSAPoll` readiness backend

Branch from reviewed W2: `claude/windows-wsapoll`

Probe and commit `WSAPOLLFD` widths, offsets, flags, and timeout behavior. Add a
Windows readiness adapter without changing the public poller API. Preserve
generation-bearing, revision-bearing tokens and stale-event rejection. Exercise
read, write, error, hangup, timeouts, interrupted/retried waits where applicable,
registration updates, removal, and nonblocking-connect completion. Do not
implement a wake transport or claim close-cancellation completion yet.

Stop after W3 and return the evidence.

## Task W4: owner-independent Windows wake and close lifecycle

Branch from reviewed W3: `claude/windows-poller-wake`

Add a Windows wake transport with explicit ownership and a bounded close
protocol. A loopback datagram pair is the initial design candidate; validate it
rather than assuming it. Preserve the existing wake-epoch semantics, mutation
acknowledgement, close admission, writer drain, and “close returns only after
the active await and wake handles are retired” contracts. Update all wake,
short-lease, descriptor-reuse, and close-race models and controls. Add real
blocked-await, close-vs-wake, close-vs-accept, and repeated-open/close stress.

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

## Alternate backends after the portable path

Once W1-W5 provide a behavioral oracle, the poller SPI can host follow-on
implementations:

- Windows IOCP and overlapped Winsock;
- Linux `io_uring`;
- Darwin `kqueue`, libdispatch, or `dispatch_io`.

Those are performance and scalability backends. They must preserve the same
owned-handle, error, token, wake, cancellation, and close contracts and pass the
same black-box suite before replacing the portable backend.
