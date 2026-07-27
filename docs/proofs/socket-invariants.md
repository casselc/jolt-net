# Socket invariants

Several properties in jolt-net are not testable by example in any convincing
way because they are about *orderings and interleavings* rather than values. A
test can show that one run behaved; it cannot show that no run misbehaves. The
formalized invariants below are checked with z3; runtime-only controls are
labelled explicitly.

Most proof families have three models, following the convention already used in
`jolt-upstream/test/chez/formal/` and `jolt-tcp/docs/proofs/`:

| Suffix | Expected | Meaning |
|---|---|---|
| `-buggy` | **sat** | The naive implementation really does violate the property, and here is the counterexample. Without this, "corrected is unsat" might just mean the property was trivial. |
| `-corrected` | **unsat** | No run of the implemented design violates the property. |
| `-nonvacuity` | **sat** | The corrected model still describes a real scenario. Without this, unsat could mean the constraints were contradictory and the proof held for free. |

The lease/token family uses one focused query per failure mode plus a shared
non-vacuity control. Every file is directly runnable with `z3`; Chiasmus strips
the included `(check-sat)` and model/core query before invoking its embedded
solver. See [`models/README.md`](models/README.md) for commands, exact expected
results, unsat cores, bounds, and omitted behavior.

---

## 0. Winsock subsystem initialization is ordered and once-only

**Property.** Every Winsock operation, including `getaddrinfo`, runs only
after `WSAStartup` has succeeded. Concurrent first callers perform exactly one
`WSAStartup` attempt, and every caller -- racing or arriving later -- observes
the same memoized success or the same memoized failure.

**Why it needs a proof.** `getaddrinfo` is itself a Winsock call on Windows,
not a POSIX-portable resolver primitive that happens to also exist there. The
live witness that motivated this task was `jolt.net resolve: Name resolution
failed (code 10093)` -- `WSANOTINITIALISED` -- because `jolt.net/listen` and
`jolt.net/connect` resolve the endpoint before reaching `socket-for`, which was
the only call site invoking `ensure-subsystem!`. Separately, the original
`ensure-subsystem!` was `(let [s @subsystem] (if (some? s) s (do (wsastartup)
(reset! subsystem ...))))` -- a check-then-reset atom, not a once-only
protocol: two threads can both observe "untried" before either writes, so both
call `WSAStartup`, and each can return its own call's result even when they
disagree.

**Design.** `jolt.net.resolver/resolve` calls `jolt.net.ffi/ensure-subsystem!`
before `getaddrinfo`, not only through `socket-for`, so resolution can never
race initialization on any call path. `ensure-subsystem!` itself holds one of
three states in a single atom: untried (`nil`), one attempt in flight (a
`promise`), or a resolved outcome (`:ok` or `{:error ex}`, permanently
memoized). Exactly one caller wins the `nil -> promise` `compare-and-set!` and
performs the single `WSAStartup` call; every other caller blocks on
`(deref promise)` or reads the already-resolved outcome, so it never attempts
its own call and always returns exactly what the winner delivered. The attempt
boundary normalizes both a returned WSA error and a managed allocation/FFI
exception into one `{:error ex}` outcome, publishes that terminal state, and
delivers the promise before the winner itself returns or throws. An exceptional
first attempt therefore cannot leave the atom holding an unresolved promise.
`WSAStartup` is process-scoped and individual sockets never call `WSACleanup`,
so tearing down the subsystem under other users is not possible from this code.

**Result.**

- `winsock-init-once-buggy.smt2` -- **sat**. Its known-bad check-then-reset
  control admits both distinct entrants (`attempt_count = 2`), while an
  exceptional attempt produces no canonical outcome, delivery, terminal state,
  or completed caller.
- `winsock-init-once-corrected.smt2` -- **unsat** for all three bounded attempt
  kinds (success, returned error, thrown exception). The core includes the CAS
  winner constraints plus explicit outcome-production, delivery,
  terminalization, completion, and both-observer definitions.
- `winsock-init-once-nonvacuity.smt2` -- **sat**. Two distinct callers reach
  the gate, one wins, and even the thrown-exception case reaches a terminal
  memoized error observed by both. Contention is derived from the two entrant
  facts rather than asserted as a free Boolean.

The runtime counterpart is `test/jolt/net/blocking_test_main.clj`'s
`winsock-init-stress!`: 32 futures first count down a shared latch and block on
one start promise; only after every distinct entrant has reached that barrier
are they released through `ensure-subsystem!` as the process's actual first
Winsock use. The test asserts `jolt.net.ffi/winsock-startup-attempts` is exactly
1 afterward, then confirms a later caller reuses the memoized outcome without
incrementing that counter. Fresh-state deterministic controls inject both a
returned error and a thrown exception, require exactly one attempt, require a
timed later call not to strand, and compare the identical memoized exception.
The complete blocking suite has a 60-second in-process watchdog and the native
PowerShell runner has a 90-second outer process watchdog.

The 10093 witness disappearing is proved by the resolver and socket suites that
run immediately afterward in the same process succeeding at all, not by a test
that bypasses resolution.

---

## 1. Native result and error stay paired through cleanup

**Property.** The native error a caller reports is the error of the operation
that *failed*, not of runtime reactivation or cleanup that ran afterward.

**Why it needs a proof.** `errno`/Windows last-error is a per-thread slot that is
valid only until intervening runtime or native work. A collect-safe foreign call
may reactivate the Scheme runtime before source code can call an error accessor;
first-use binding/resolution paths can also disturb the slot; `close()`, `free()`,
and `closesocket()` can then overwrite it again during rollback. So lexical
adjacency in Scheme is not a sufficient foreign-call contract.
This is not hypothetical: `teensyp.ffi-net`'s constructors call `close` before
reading `errno`, so a failed `bind` can report whatever `close` happened to set.
Windows W1 found both forms before cleanup: blocking `connect` returned failure
but a later `WSAGetLastError` observed zero, and after that path was paired, the
first duplicate `bind` also reported zero while a later duplicate bind happened
to retain `10048`.

**Design.** Every sentinel-returning binding whose error slot is consumed opts
into core `{:capture-native-error true}` and lives only in
`jolt.net.ffi/captured-call`; `invoke-captured` always returns
`[native-result native-error]`. The scalar `call`/`invoke` surface has a
different, invariant result shape and owns only error-independent `close`.
`checked-captured` consumes only the pair and ignores stale error state on
success. Socket creation, bind/listen, endpoint inspection, options, blocking
and nonblocking accept/connect/read/write, `fcntl`, pipe wake, `poll`, shutdown,
and `getaddrinfo` all use the captured table. POSIX `getaddrinfo` consults the
second element only for `EAI_SYSTEM`; `WSAStartup` remains scalar because its
nonzero return value is itself the error.

**Result.**

- `errno-capture-ordering-buggy.smt2` — **sat**. Counterexample:
  `fail_code>0`, intervening return work leaves `0`, and late capture reports
  `0`.
- `errno-capture-ordering-corrected.smt2` — **unsat**, with core
  `{failing_call_sets_errno, capture_in_foreign_return, reported_from_pair,
  property_violated}`.
  The core is the informative part: the property follows from the capture
  boundary *alone* and does not depend on later work or cleanup's code at all.
- `errno-capture-ordering-nonvacuity.smt2` — **sat**. Witness has
  `errno_after_reactivation=0 ≠ errno_after_fail=1` and `reported=1`: a real
  clobber occurred, and the failure's own code was still reported.

These models abstract the capture boundary as an ordering fact; they do not
prove Chez's `__errno`/`__get_last_error` convention. The core FFI tests and
`docs/ffi-native-error-capture.md` establish that source oracle. jolt-net adds
deterministic controls for paired success/failure, `poll`, and POSIX
`EAI_SYSTEM`; the real socket suite requires double bind to report
`EADDRINUSE`. Native Windows revision `11142a3` reports exact refused-connect
`10061` and first-attempt duplicate-bind `10048` in the same process, with no
post-call error accessor and before rollback cleanup.

---

## 2. A handle closes at most once

**Property.** However many times `close!` is called, and from however many
threads, at most one `close(2)` is issued for the descriptor — and it is issued.

**Why it needs a proof.** Descriptors are reused aggressively. A second close on
the same number can close an *unrelated* socket that has since inherited it, and
the failure then appears in whatever code owns the new descriptor — arbitrarily
far from the double close that caused it. This is among the hardest classes of
bug to attribute after the fact, and a test that calls `close!` twice from one
thread proves nothing about the racing case.

**Design.** `jolt.net.handle` guards the transition with `compare-and-set!`, so
the read and the write are one atomic step.

**Result.**

- `idempotent-close-buggy.smt2` — **sat**. The check-then-act version
  (`(when-not @closed (close raw) (reset! closed true))`) admits a run where both
  threads observe `:open` and `close_count=2`.
- `idempotent-close-corrected.smt2` — **unsat**, with core
  `{cas_atomicity, someone_closes, property_violated}`.
- `idempotent-close-nonvacuity.smt2` — **sat** under genuine contention, with
  `close_count=1`.

The runtime counterpart asserts `close!` returns `true` then `false` on repeated
calls, and that 50 failed `listen` attempts leak no descriptors. The next section
covers the lease protocol added around that unique close owner.

---

## 3. Leases and tokens prevent stale descriptor use

**Bounded claims.**

1. A syscall admitted by a short nonblocking lease completes before native close;
   close rejects later leases and waits for admitted leases to drain.
2. A readiness event is delivered only when the registration still exists and
   the generation/revision token captured in the native poll snapshot remains
   current.

**Why they need proofs.** A raw fd can be selected, closed, and reused with the
same integer while another thread still holds stale work. An acknowledged
interest update can invalidate a native snapshot without changing its fd. Tests
can force representative remove, update, and close races; they cannot enumerate
all relevant orderings.

**Design.** Ownership assigns every socket a monotonic generation. Nonblocking
socket operations acquire only a short lease; close changes the handle to
`:closing`, rejects new leases, notifies readiness owners, and defers native
close until admitted leases drain. Poll registration and acknowledged updates
carry revisions. `await-ready` retains the complete token and compares it with
the current registration after native poll returns.

**Verified models and controls.**

- `descriptor-reuse-buggy.smt2` is **sat**: select at step 0, close at 1,
  reuse at 2, then issue the stale syscall at 3 on fd 8 after generation 9
  became 10.
- With nonblocking syscall admission, reject-new-on-close, and
  drain-before-native-close, `short-lease-post-close-corrected.smt2` makes a
  post-close syscall **unsat**.
- A deliberately blocking syscall that retains the same lease makes shutdown
  deadlock **sat** in `blocking-lease-deadlock-control.smt2`. This is a semantic
  control and a design constraint: the implementation does not wrap
  uninterruptible blocking `accept` or `connect` in these leases. A poll
  snapshot is wakeable and uses a finite native safety interval.
- `readiness-generation-mismatch.smt2` makes a reused-fd dispatch whose
  generation differs from the current generation **unsat**.
- A stale-interest dispatch whose acknowledged revision differs from the current
  revision is **unsat** in `readiness-revision-mismatch.smt2`.
- `readiness-current-token-nonvacuity.smt2` is **sat** with matching current
  generation/revision plus an admitted lease, proving that the safety
  constraints do not reject all valid work.

The executable counterpart in `jolt.net.poller-test` invalidates blocked native
snapshots by acknowledged remove and socket close, then requires an empty event
set. It proves that an updated token supersedes the old revision while the
successor still receives readiness, and that native close remains deferred until
a held lease is released.

**Wake-pipe pair invariant.** Per-handle leases are insufficient for a pipe:
closing the read end while an already-admitted writer still holds the write end
can deliver SIGPIPE to the process. Wake writes and close therefore linearize on
one owner-independent CAS state:

1. A writer increments `:writers` only while admission is `:open`, then acquires
   the write-handle lease.
2. Close changes admission to `:retired`, rejecting later writers.
3. Close waits for the counted writers to release their handle leases, closes
   the write end, and leaves the read end open until the active await releases.
   The winning close then waits for that await and returns only after lifecycle
   is `:closed` and both pipe handles are closed.

The ordering inside writer release is load-bearing: handle lease first, admission
count second. Once close observes zero admitted writers, no thread can still issue
`write(2)`, and no new writer can enter.

- `wake-pair-buggy.smt2` is **sat** with admission at step 0, read close at 2,
  the stale write at 3, and release at 4.
- `wake-pair-corrected.smt2` makes both violation branches **unsat**: the shared
  CAS gate rejects a late uncounted writer, while lease-before-count release,
  writer drain, and write-first/read-last retirement reject a previously
  admitted writer that survives read close.
- `wake-pair-nonvacuity.smt2` is **sat** with an admitted writer held across
  retirement; after it drains, close still completes both pipe ends.

The forced-interleaving runtime test holds one admitted writer across close and
proves close cannot retire the read end until that writer releases.

**Non-blocking transition invariant.** Every lease proof above has a premise
that is easy to overlook: an operation called “short” must really be
non-blocking at the native boundary. `fcntl` is declared in C as
`fcntl(int, int, ...)`. Giving Jolt three typed arguments does not make it a
fixed-arity C function. In particular, Apple arm64 places arguments after `...`
on the stack while a fixed third argument would still use a register.

The original binding omitted that ABI boundary and marked a handle
non-blocking from `F_SETFL`'s return code alone. A Darwin accept could therefore
park inside `accept(2)` while retaining the listener lease: listener close
returned after deferring native close, but the accept could neither return nor
release the lease. This was the missing premise in the earlier accept analysis,
not a counterexample to the poller-close ordering once await had actually begun.

The core fork now exposes `{:varargs-after 2}` on `jolt.ffi/defcfn`, which lowers
to Chez's `(__varargs_after 2)` convention. jolt-net additionally reads
`F_GETFL` after `F_SETFL` and does not mark or return a handle unless
`O_NONBLOCK` is observable. The read-back is intentionally retained even with
the corrected ABI declaration: it makes a future compiler, libc, or binding
regression fail closed before any short lease is admitted.

Task W2 added Windows, but the two targets do **not** provide equivalent proof
boundaries. Winsock reaches non-blocking mode through
`ioctlsocket(FIONBIO)` and exposes no portable getter for a socket's blocking
mode. Inventing a substitute getter by making a speculative socket call would
be a worse oracle: it can consume input, accept a connection, or say nothing
when data is already ready.

An adversarial review caught the first W2 model overstating production. It made
the Windows handle mark depend on a later would-block observation, while the
code correctly marks immediately after successful `ioctlsocket`. The proof is
now split at the real trusted boundary:

| Target | In-process mark boundary | What is proved or assumed |
|---|---|---|
| POSIX | successful `F_SETFL` plus `F_GETFL` observing `O_NONBLOCK` | Per-handle fail-closed guard; the bit remains unconstrained in the model and an absent bit prevents marking. |
| Windows | successful `ioctlsocket(FIONBIO)` | Conditional on the header-matching binding, exact command representation, a fully initialized nonzero `u_long` at `argp`, and documented Winsock semantics. Later would-block tests are cross-boundary conformance evidence, not an admission guard. |

`jolt.net.nonblocking/postcondition-kind` names the in-process boundary. It
does not claim that Windows has gained an observable per-handle postcondition.

- `posix-nonblocking-transition-buggy.smt2` is **sat** for the original Apple
  arm64 witness: fixed-arity declaration, apparent `F_SETFL` success, absent
  `O_NONBLOCK`, marked handle, and blocking-capable admission.
- `posix-nonblocking-transition-corrected.smt2` is **unsat** while
  `nonblocking_observed` stays unconstrained: the variadic declaration and
  read-back prevent a handle without the bit from being marked.
- `posix-nonblocking-transition-nonvacuity.smt2` is **sat** with the bit
  observed and a useful short operation admitted.
- `windows-nonblocking-contract-buggy.smt2` is **sat** when the `u_long` at
  `argp` contains zero: the valid FIONBIO call requests blocking mode and can
  return success, after which the apparent successful-return mark admits a
  still-blocking socket. Marking on return is the production ordering; the
  wrong requested value, not that ordering, creates this witness.
- `windows-nonblocking-contract-corrected.smt2` is **unsat**, explicitly
  conditional on `binding_matches_ioctlsocket_header`,
  `command_argument_has_probed_long_width_and_fionbio_bits`,
  `argp_contains_fully_initialized_nonzero_u_long`, and
  `trusted_winsock_fionbio_semantics`. The model does not pretend to prove the
  operating-system contract.
- `windows-nonblocking-contract-nonvacuity.smt2` is **sat** with a useful
  operation and records `mark_step = 1`, `behavioral_evidence_step = 2`. This
  makes the production/test ordering explicit instead of smuggling later
  evidence into the mark.

All six split models were re-run through Chiasmus on 2026-07-24: the two
`-corrected` queries were `unsat`, both `-buggy` controls and both
`-nonvacuity` controls were `sat`.

The runtime counterpart independently calls `F_GETFL` on a newly returned
listener and requires `O_NONBLOCK` before the rest of the poller suite runs. It
also injects the motivating bad outcome—apparent `F_SETFL` success followed by
a read-back without the bit—and requires a fail-closed exception after the
second `F_GETFL`. The core FFI test exercises the same variadic `fcntl` binding
and transition.

On Windows the runtime counterpart is conformance evidence rather than a
read-back or per-handle guard:
`test/jolt/net/nonblocking_test_main.clj` requires `try-accept` on a listener
with no pending client, and a read on a freshly accepted socket with no pending
data, to return `::would-block` as a VALUE. A descriptor still in blocking mode
would park the thread instead, so the suite's own watchdog would report a
timeout rather than a pass. The observations occur after production has marked
the handles. That gate ran green on native Windows x86-64; see
`docs/PLATFORM-COVERAGE.md`. The complete Darwin arm64 gate passed with
source-built Chez 10.4.1 for commit `65a0f1e` in
[CI run 30078697403](https://github.com/casselc/jolt-net/actions/runs/30078697403),
closing the platform-specific runtime evidence for this bounded surface. The
SMT model still does not pretend to model Apple's calling convention.

**Wake-epoch invariant.** A Boolean-only coalescing gate has another independent
race. Await can drain the old byte, a producer can observe the old `true` gate
and omit its write, and await can then reset the gate and park with no byte for
the new wake. `signal-wake!` increments a monotonic sequence before attempting
the coalesced write. The drain/reset path restores a byte if its captured epoch
changed; await's entry-epoch comparison covers the complementary interleaving
where drain captured the newer epoch.

- `wake-epoch-buggy.smt2` is **sat** for the drain/producer/reset/park schedule
  `0/1/2/3`, with `lost_wake = true`.
- `wake-epoch-corrected.smt2` makes the same lost-wake query **unsat**: for one
  admitted new epoch, either the producer writes or a drain/entry comparison
  restores a byte before park.
- `wake-epoch-nonvacuity.smt2` is **sat** for the hard case where the producer
  really coalesces, drain restores the byte, and await progresses.

What this trio does **not** cover is its own boundary. Every one of its claims is
relative to `entry_epoch`, and its violation is literally
`(> current_epoch entry_epoch)` — a wake strictly after await entry. A wake that
is *already visible* when `await-ready` begins is outside the model by
construction, and the model is silent about whether discarding it is safe. That
silence was the hole; see the next invariant. The trio remains correct for what
it does claim: transport coalescing across the drain/reset window.

**Wake-cursor ordering invariant.** "The await had not started yet" is not the
same fact as "the consumer had already seen the state behind that wake", and
`await-ready` used to conflate them. It sampled `entry-wake-sequence` at its own
entry and consumed any already-visible epoch as stale. A consumer that reads
producer-owned state *before* calling `await-ready` — which is what every reactor
does — leaves a window between its read and that sample. A publication landing in
that window is real, unobserved, and was discarded.

jolt-tcp's reactor is exactly that shape: `process-pending!` drains its `:pending`
set, a worker's `finish-work!` then publishes a generation and calls
`net/wake!`, and only then does the reactor call `await-ready`. The wake predated
the *await* but not the *read*. The reactor parked until the 1000 ms native
safety tick, which is the `base + k * ~1000 ms` latency jolt-http's backpressure
property measured (seed 9157075391771664454, 93,388 bytes, 1 KB read buffer).
Bytes were never lost — only delayed — because the tick eventually delivered the
same pending state.

The fix makes the boundary the caller's, not the callee's. `poller/wake-cursor`
reads the monotonic sequence; the consumer samples it *before* its own read and
passes it to `await-ready`, which then refuses to discard anything above it. The
two halves compose into one contract:

    producer:  publish state       then  wake!  (advances the cursor)
    consumer:  sample the cursor   then  read that state  then  await

A wake at or below the sampled cursor was published before the consumer's read,
so the read necessarily observed it and parking is correct. A wake above it
landed after the read and arms the wait. `await-ready`'s two-argument arity keeps
the old entry-relative sampling, which is the sound degenerate case for a waiter
with no prior read; a cursor ahead of the live sequence is refused rather than
honoured, since honouring it would mark undelivered wakes stale.

- `wake-cursor-ordering-corrected.smt2` is **unsat** for the lost-wake query: no
  publication can be both missed by the consumer's read and unarmed at native
  entry. The unsat core names `boundary_is_the_caller_cursor` as load-bearing.
- `wake-cursor-ordering-buggy.smt2` changes exactly one assertion — the boundary
  is read at await entry instead of supplied by the caller — and is **sat**. The
  witness is the production interleaving in miniature, on strictly distinct
  instants: sample `-1`, read `0`, publish `1`, wake `2`, await entry `3`, native
  entry `4`, giving `cursor = 0`, `entry_boundary = 1 = current_seq`, no restore,
  `lost_wake = true`.
- `wake-cursor-ordering-nonvacuity.smt2` is **sat** for the execution the fix must
  keep: the consumer's read *did* observe the publication and the wait parks
  unarmed. This is what rules out the degenerate "always arm" fix, which would
  also make the corrected query unsat while replacing a 1000 ms park with a spin.

All three were run through standalone Z3 4.8.12 and through Chiasmus, with
matching verdicts, cores, and witnesses.

The runtime counterpart is `test/jolt/net/wake_cursor_test.clj`, and it measures
no elapsed time at all. Both the clock and the native call are hooks, so each
case fixes one interleaving exactly. The observable is `:wake-pending` at native
entry: a byte in the receiver means poll(2)/WSAPoll returns immediately whatever
timeout it was handed, so arming — not duration — is the park predicate. It
checks the forced race (armed), the same interleaving with no cursor (**not**
armed, which is the deterministic reproduction of the defect and what keeps the
first case non-vacuous), a publication before the cursor (not armed, correctly),
and a publication landing inside an already-parked wait (armed on re-entry).

**Accept-terminal invariant.** Given the verified non-blocking transition
above, a registration-removal wake is intentionally a best-effort state-change
notification, not a cancellation boundary. Listener close can race just before
accept enters `await-ready`: the already-published wake is then a pre-entry
epoch, may be drained before the native snapshot, and cannot by itself
guarantee prompt accept completion.

This is the same defect class as the wake-cursor invariant above, seen from the
other side, and the accept loop now also samples `poller/wake-cursor` before
`try-accept` so a wake published after that attempt arms the wait instead of
being consumed. The terminal close listener below is **not** removed on the
strength of that: the cursor closes the notification window, while the close
listener additionally makes a closed poller *refuse* a later await. Those are
different guarantees, and retiring the second one is not part of this task.

`accept` therefore owns a second close listener whose only job is terminal:
it calls `poller/close!`. The listener installs this callback before poller
registration, so a close anywhere in setup either rejects registration or
retires the poller. If await is already active, terminal poller close retires
wake admission, closes the independent wake-pipe write end, and waits for await
to exit. If await enters later, the closed poller rejects it.

The synchronous callback does not form a lease deadlock. Await may hold a short
listener lease in its snapshot, but `handle/release!` does not wait for listener
notification or native close. Its wait-for path is:
poller write-close → native poll return → listener lease release → await exit →
terminal callback return → listener notification/native close. The ordinary
registration callback can run before or after the terminal callback; both
orders use the same independent terminal path.

- `accept-terminal-close-buggy.smt2` is **sat**: a best-effort wake occurs
  before await entry, is no longer visible after entry, and the still-open
  poller admits an accept that remains blocked after listener close.
- `accept-terminal-close-corrected.smt2` makes both violation branches
  **unsat**. An active await cannot survive terminal callback return, a later
  await cannot enter the retired poller, and the await does not depend on
  listener native close, so the three-edge callback cycle cannot form.
- `accept-terminal-close-nonvacuity.smt2` is **sat** with the ordinary removal
  callback first and an active await crossing terminal close. The await exits,
  the poller becomes terminal, and listener close completes.

The runtime counterpart waits until accept has installed both close listeners
instead of sleeping for a guessed scheduler interval. It bounds listener close
and accept completion independently, and places callbacks before and after the
accept-owned listeners to verify their reentrant removal does not skip a
neighboring callback.

---

## 4. Non-blocking connect ownership survives native connection completion

**Bounded claim.** For one initiation and at most one completion:

1. a synchronous failure returns no socket and rolls its raw descriptor back;
2. immediate success and `EINPROGRESS` each return exactly one caller-owned
   non-blocking socket;
3. `finish-connect!` neither closes nor transfers that ownership when
   `SO_ERROR` is zero or nonzero; and
4. when caller close races an admitted `finish-connect!`, native close occurs
   only after the `getsockopt(SO_ERROR)` lease releases.

**Why it needs a proof.** `EINPROGRESS` is a successful *ownership transfer*
even though the connection is not yet successful. Treating it like an ordinary
failure leaks or prematurely closes the descriptor. At the other boundary,
readiness is only permission to inspect `SO_ERROR`; concurrent socket close must
not recycle the descriptor between selecting it and that inspection. Finally,
an asynchronous refusal does not grant `finish-connect!` permission to consume
the caller's close responsibility.

**Live source facts.** `try-connect-address` performs non-blocking setup and the
native call before `h/own`; its catch closes only a raw, unreturned descriptor.
Both result values then pass through the same `h/own` expression.
`finish-connect!` wraps allocation, `getsockopt`, and the `SO_ERROR` read in
`h/with-lease`, and has no `close!` call. The handle lease's existing CAS/drain
protocol supplies the native-close ordering.

**Verified models and controls.**

- `connect-ownership-completion-buggy.smt2` is **sat**. Its concrete witness
  returns `in_progress` with `owner_count_after_constructor = 0`, closes at step
  2 before `getsockopt` at step 3, and lets completion failure take close
  ownership.
- `connect-ownership-completion-corrected.smt2` is **unsat** for the asserted
  violation. The named core contains the return/rollback/ownership equivalences,
  completion owner preservation, lease admission, `getsockopt`-inside-lease,
  drain-before-native-close, all four violation definitions, and the violation
  query.
- `connect-ownership-completion-nonvacuity.smt2` is **sat** for the hard valid
  scenario: `EINPROGRESS`, asynchronous failure, and caller close crossing the
  completion lease with event order
  acquire/close/getsockopt/release/native-close = `0/1/2/3/4`. Both owner counts
  remain one and completion initiates no close.

The finite model has one socket, one completion, one closer, five distinct event
positions, and atomic atom/lease transitions. It omits repeated completion
polls, multiple close callers, kernel `SO_ERROR` behavior, resolver scheduling,
and weak memory below atom linearizability.

Here “completion” means `finish-connect!` checking `SO_ERROR`; it is unrelated
to the future-shaped operation/lease completion abstraction proposed for
jolt-tcp.

The executable companion in `jolt.net.poller-test` pins immediate and
in-progress classification, completes a real loopback connect only after
readiness plus `SO_ERROR`, observes real `ECONNREFUSED`, proves a completion
failure leaves the returned socket open for exactly one caller close, and
repeats 50 pre-transfer failures to detect descriptor leaks. Its connector
helper recomputes finite waits from one absolute monotonic deadline.

Resolver-candidate policy is deliberately not reduced to a solver model.
`try-connect` returns every untried owned resolved-address value; synchronous
attempt failures retain and ultimately throw the last real exception. An
asynchronous failure is the most recent real exception, and the connector can
close that attempt and pass the returned remainder back to `try-connect`.
Runtime resolver-order and native-code checks are the stronger oracle for that
data-flow property.

---

## 5. Peer stream effects become observable through readiness, not synchronization

**Bounded claim.** A successful peer send or `shutdown(:write)` orders the
sender's stream but does not synchronize receiver readiness. A non-blocking
receive may still return `would-block`; after payload becomes readiness-visible
it is consumed in order, and after FIN becomes readiness-visible and payload is
exhausted, the next positive-length receive returns the distinct EOF value. A
zero-length receive alone returns numeric zero; it is never evidence of FIN.

**Counterexample to the stronger claim.** Native macOS arm64 CI run
`30144054281` first completed a three-byte sender write, then immediately
attempted the peer's non-blocking read. Darwin returned `would-block`. The
queued payload consequently remained ahead of the later FIN, so a subsequent
read after shutdown returned one payload byte rather than EOF. This disproves
both stronger assumptions: neither successful send nor peer shutdown is a
cross-socket synchronization barrier. Linux commonly made the same test pass
because bytes and FIN happened to be observable before the reads, but that
timing was not a portable contract.

**Design and executable control.** `jolt.net.poller-test` registers the receiver
before send. Bounded progress helpers advance source and destination offsets
through arbitrary positive short writes/reads; only `would-block` awaits the
matching write or read/hangup readiness, and every retry uses the operation's
one absolute monotonic deadline. The test requires the three payload bytes and
guard offsets to be exact before shutting down the peer write side, then
requires the eventual positive-length read to return `jolt.net/eof`. It
separately requires a zero-length read to return zero. The helpers are a
consumer-level composition check, not a claim that one native call transfers a
whole window.

This is an environmental temporal premise, not a useful SMT state-space claim:
an SMT model saying “FIN is visible after readiness” would merely assume the
kernel contract it appeared to prove. The Darwin counterexample, synchronized
native runtime test, and portable TCP contract are the appropriate evidence.

---

## 6. Readiness dispatch is gated on the complete token, on every backend

**Bounded claim.** A readiness snapshot is captured BEFORE the native wait, so
by the time event bits are decoded the world may have moved: the registration
may have been updated to a new revision, removed outright, or its descriptor
number reused by a different socket under a new ownership generation. No decoded
event is delivered unless its captured token still equals the registration's
live token, compared whole. A current registration with genuine native readiness
does remain deliverable, so this is not a deny-all rule.

**Design and source anchor.** `jolt.net.poller/current-token?` is the single
gate, and `jolt.net.poller/await-ready` applies it to every decoded entry after
the native wait. Task W3 extracted it from the body of `await-ready` into a
named function precisely so both the models and the Windows gate could anchor to
one location. It is backend independent: `jolt.net.readiness` supplies the
per-target struct layout, flag values, and native call -- POSIX `poll` over
`struct pollfd`, Windows `WSAPoll` over `WSAPOLLFD` -- but neither backend may
dispatch an event this gate rejects.

**Models.** `readiness-generation-mismatch.smt2` and
`readiness-revision-mismatch.smt2` are `unsat` for the two supersession arms;
`readiness-removed-registration.smt2` is `unsat` for the removal arm;
`readiness-current-token-nonvacuity.smt2` is `sat`, ruling out a vacuous
deny-all reading. `readiness-stale-dispatch-buggy.smt2` deletes exactly the
token comparison and is `sat`, which is what makes the three `unsat` results
non-trivial rather than an artifact of over-constrained premises.

**Executable control.** `test/jolt/net/poller_test.clj` covers the POSIX
backend. `test/jolt/net/wsapoll_test_main.clj` covers Windows over real
`WSAPoll`: it registers a genuinely readable socket, requires the current token
to be delivered with real `:read` readiness, then requires the superseded,
removed, and closed-generation tokens all to fail `current-token?` and never
appear in a dispatch.

**What this does NOT claim.** Dispatch only. It says nothing about interrupting
a native wait already in progress. On Windows there is no wake transport until
task W4, so an acknowledged mutation is visible to the NEXT await rather than to
one already parked in `WSAPoll`; that is why the public Windows poller stays
fail-closed and W3's evidence comes from an internal adapter whose awaits are
sequential. Nor does it claim anything about Winsock's own event semantics --
those are trusted OS premises, pinned by the probe and by the native gate, not
by these models.

---

## 7. Wake-less close refusal and await admission share one atomic state

**Bounded claim.** Until task W4 supplies a Windows wake transport, the internal
W3 adapter may close only when no await is active. If an await is active, close
must refuse while leaving the lifecycle `:open`; it may not enter `:closing` and
wait for an operation it has no mechanism to interrupt. This claim covers one
close and one competing await admission.

**Counterexample and correction.** The original W3 implementation read
`:awaiting?` before entering close's lifecycle CAS loop. A concrete interleaving
was therefore possible: close observed false, await CASed the lifecycle to
`{:phase :open :awaiting? true}`, then close CASed that newer value to
`:closing` and waited with no wake transport. The corrected
`jolt.net.poller/close!` examines `:awaiting?` on the exact value it either
rejects or supplies to `compare-and-set!`. If await wins, close refuses. If
close wins, the await admission CAS no longer sees `:open` and fails.

**Models and controls.**
`windows-wakeless-close-race-buggy.smt2` is `sat` with the interleaving above.
`windows-wakeless-close-race-corrected.smt2` is `unsat`; its four-label core is
the atomic guard, corrected transition, violation definition, and violation
query. `windows-wakeless-close-race-nonvacuity.smt2` is `sat` with no admitted
await and a useful transition to `:closing`. Chiasmus verified all three after
linting them without errors.

**Executable companion.** The Windows W3 gate constructs the real wake-less
adapter, admits an await, and blocks it at the native-call seam. `close!` must
throw `:windows-wake-transport` and leave the lifecycle `:open`; releasing the
seam must still deliver the current token, after which close succeeds normally.
Real `WSAPoll` behavior remains covered by the independent socket cases in the
same suite.

**Limit.** This is a refusal invariant for the internal adapter, not the Windows
close protocol. Task W4 supplied the transport and discharged that obligation in
§8; this section is retained because the adapter still exists and still refuses
exactly as described, and because that evidence is about a poller built *without*
a waker. It must not be read as evidence for the W4 transport.

---

## 8. On a byte-only transport, close's terminal wake is the only cancellation

**Bounded claim.** Once close has won the lifecycle transition on a poller whose
transport is `:byte-only`, no await can park in the native wait with nothing
able to release it. This covers one close and one await.

**The premise that makes this a distinct claim.** The POSIX self-pipe and the
Windows datagram pair differ in exactly one respect, and it is decisive:

| Transport | Sender retirement observable to receiver? | Cancellation sources |
|---|---|---|
| POSIX self-pipe | yes, `POLLHUP` | published byte **or** hangup |
| Windows datagram | **no** | published byte **only** |

`jolt.net.wake/terminal-wake` names this: `:byte-or-hangup` versus `:byte-only`.
Any `WSAECONNRESET` a connected UDP receiver may surface after its peer is
retired comes from a best-effort ICMP port-unreachable; it is not a protocol
guarantee, and `jolt.net.wake` classifies it as benign drain noise precisely so
that nothing can come to depend on it. Datagram peer close is not a hangup
oracle.

**Counterexample and correction.** Publishing a byte is not by itself enough.
The await's own pre-snapshot drain exists to consume a byte left by an earlier
acknowledged mutation, and it will just as happily consume close's terminal
byte — after which the await parks with nothing left. The drain's epoch-restore
cannot save it either: that restore goes through ordinary writer admission,
which close has retired by then. On POSIX this defect is invisible, because the
retired write end still supplies `POLLHUP`.

Two changes close it, and both are ordering rather than mechanism:

1. `jolt.net.poller/close!` publishes the terminal byte at step 3, while sends
   are still admitted, and only then retires admission at step 4. The publish
   deliberately bypasses the coalescing gate, because that gate can read `true`
   for a producer whose own send has not landed.
2. `jolt.net.poller/await-ready` re-reads the lifecycle *after* its last drain
   and *before* the native call. For the terminal byte to have been drained,
   the publish — and therefore the `:closing` transition preceding it — must
   already have happened, so that read cannot still see `:open`. Parking is
   refused in exactly the case where nothing could wake it.

**Models and controls.** `windows-terminal-wake-corrected.smt2` is `unsat`;
deleting only the pre-entry check makes `windows-terminal-wake-buggy.smt2`
`sat` with the interleaving above, and `-nonvacuity` is `sat` with a real park
really cancelled, so the check is not "never wait".
`close-completion-ordering-corrected.smt2` is `unsat` for the publish/retire
order and the `:closed`-before-return rule; its buggy control swaps steps 3 and
4 and shows close reporting completion having delivered nothing.
`wake-receiver-lease-corrected.smt2` covers receiver lifetime and records that
close's ordering and the owned handle's deferred native close are
*independently* sufficient — it is still `unsat` with either deleted.
`posix-pipe-hangup-independence-control.smt2` is `sat` and exists to keep the
POSIX second path from being silently borrowed by a Windows claim; no Windows
model in this directory contains a hangup term.

**Executable companion.** The native W4 gate drives the public
`jolt.net/open-poller` over real Winsock sockets: an empty poller woken, a
blocked await woken, acknowledged update and removal against a parked wait,
registered-socket close, terminal close, repeated and concurrent close, close
against an admitted wake sender, a wake injected inside the drain/reset window,
an await admission racing close's CAS, and receiver lifetime sampled from inside
the wait. No test uses elapsed time as its success oracle: a blocked await is
given a 120 s timeout and observed with a 15 s watchdog, so returning at all
proves it was woken.

**Limit.** This is a bounded interleaving argument over one await and one close.
It assumes the published datagram is delivered to the connected loopback peer
and that the native wait is level-triggered on the receiver. Those are Winsock
premises exercised by the native gate, not established here.

---

## Applicability: these models are keyed to an OS contract, not to an ISA

Task W7 added native Windows **aarch64** to the platforms these invariants are
claimed for. No model was added, changed, or re-derived for it, and that is a
claim needing justification rather than an omission.

Every model above is stated over an abstraction — a CAS gate, a lifecycle value,
a lease interval, an epoch counter, a token pair, an ordering of publish and
retire — that names no instruction set, no register width, and no calling
convention. Re-deriving them per architecture would not make them stronger; it
would produce identical files under different names and dilute what "verified"
means here.

Exactly one Windows family has an architecture-sensitive premise:
`windows-nonblocking-contract-*` assumes `ioctlsocket`'s command is
representable in the declared `long` and that the argument is a nonzero `u_long`
of the probed width. That premise is about **numbers**, and the numbers were
checked rather than assumed: a native ARM64 probe reports `:ioctl-cmd-bytes 4`,
`:ioctl-arg-bytes 4`, and `:fionbio -2147195266`, identical to the values the
model was discharged against on x86-64. Had any of them differed, this section
would say so and a corrected model would exist.

The models remain conditional on their premises. What the ARM64 native gate
supplies is evidence that those premises hold on that machine — that the probed
facts are the facts Winsock uses there, that the marshalling they describe
executes, that the datagram wake transport really delivers, and that the two
`WSAPoll` divergences recorded in `docs/PLATFORM-COVERAGE.md` also hold. It does
not, and is not reported to, make any model unconditional.

## What is deliberately not modelled

- **Resolver copy-before-free.** This is a memory-lifetime property, and the
  runtime test is stronger evidence than a model would be: it scribbles 800 KB of
  fresh native memory over the freed `addrinfo` chain, forces a collection, and
  then asserts the copied bytes are unchanged and still able to bind. A borrowed
  pointer fails there loudly. A model would only restate the intent.
- **Struct layouts.** Not a logic property. These are checked against the
  platform's own headers by `tools/probe-constants.sh`, which is ground truth
  rather than a model of it. The Linux/aarch64 and Darwin/x86-64 candidate jobs
  also normalize only the probe's architecture label and require every native
  constant, width, `sizeof`, and `offsetof` to match the explicitly shared
  descriptor before running socket tests. Darwin/x86-64 remains a separately
  uploaded artifact rather than being silently relabeled as arm64 evidence, and
  Windows/aarch64 was probed by a separate compiler on a separate machine and
  only then compared with Windows/x86-64 — the two agree completely, but the
  agreement is a regenerated CI result, not a copy.
- **Native calling conventions.** The POSIX non-blocking model proves the
  source-level admission and fail-closed read-back postcondition. The Windows
  model is conditional on the header-matching binding, requested value, command
  representation, and Winsock semantics. Core FFI regressions, header probes,
  and native runtime gates—not SMT—are the cross-boundary evidence for those
  premises.
- **Interrupted syscalls and process signals.** The invariant models do not
  pretend to model kernel signal delivery. Deterministic syscall hooks verify
  `EINTR` retry, while an injected monotonic clock advances across the call to
  verify remaining-deadline recomputation and expiry without scheduler timing.
  A subprocess writes after peer close and must report a structured reset
  instead of dying from SIGPIPE.
- **Winsock event semantics.** `WSAPoll`'s own behavior is an OS premise, not
  something these models establish. The divergences from POSIX `poll` that
  actually bite are recorded in `docs/PLATFORM-COVERAGE.md` and asserted by the
  native W3 gate: a FIN with no pending data raises `POLLHUP` alone rather than
  `POLLIN`, an unrecognized handle fails the whole call with `WSAENOTSOCK`
  rather than marking one entry `POLLNVAL`, and a refused connect surfaces as
  `POLLWRNORM|POLLERR|POLLHUP` only once the refusal lands. Real sockets on a
  real Windows runner are the evidence for each of those, not SMT.
- **Unbounded poller concurrency.** The wake models cover one candidate writer
  or producer and the critical bounded close or drain/reset ordering. They do
  not quantify over an unbounded number of producers, repeated epochs, scheduler
  fairness, or weak-memory behavior beneath atom linearizability. Runtime tests
  cover repeated enter/drain/reset races, blocked remove, socket close, terminal
  poller close, acknowledged mutation, stale-event filtering, and
  write-vs-close pipe retirement.
