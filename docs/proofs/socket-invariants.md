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

## 1. Capture precedes cleanup

**Property.** The native error a caller reports is the error of the operation
that *failed*, not of the cleanup that ran while unwinding.

**Why it needs a proof.** `errno` is a single per-thread slot that is valid only
until the next native call — and `close()`, `free()`, and `closesocket()` are all
native calls. So any rollback performed before the read destroys the evidence.
This is not hypothetical: `teensyp.ffi-net`'s constructors call `close` before
reading `errno`, so a failed `bind` can report whatever `close` happened to set.
An example-based test cannot rule this out in general, because whether the
clobber is *visible* depends on which two codes collide.

**Design.** `jolt.net.error/checked` reads the error lexically immediately after
the failing call, with nothing interposed, and callers put rollback in a `catch`
— which by construction runs later.

**Result.**

- `errno-capture-ordering-buggy.smt2` — **sat**. Counterexample:
  `fail_code=1`, `cleanup_code=2`, `reported=2`.
- `errno-capture-ordering-corrected.smt2` — **unsat**, with core
  `{failing_call_sets_errno, capture_before_cleanup, property_violated}`.
  The core is the informative part: the property follows from the capture
  ordering *alone* and does not depend on the cleanup's code at all.
- `errno-capture-ordering-nonvacuity.smt2` — **sat**. Witness has
  `errno_after_cleanup=2 ≠ errno_after_fail=1` and `reported=1`: a real clobber
  occurred, and the failure's own code was still reported.

The runtime counterpart is in `jolt.net.socket-test`: a double bind must report
`EADDRINUSE` against `:bind`, not a cleanup error. The proof covers the
interleavings; the test covers that the code is actually wired that way.

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

---

## What is deliberately not modelled

- **Resolver copy-before-free.** This is a memory-lifetime property, and the
  runtime test is stronger evidence than a model would be: it scribbles 800 KB of
  fresh native memory over the freed `addrinfo` chain, forces a collection, and
  then asserts the copied bytes are unchanged and still able to bind. A borrowed
  pointer fails there loudly. A model would only restate the intent.
- **Struct layouts.** Not a logic property. These are checked against the
  platform's own headers by `tools/probe-constants.sh`, which is ground truth
  rather than a model of it.
- **Interrupted syscalls and process signals.** The invariant models do not
  pretend to model kernel signal delivery. Deterministic syscall hooks verify
  `EINTR` retry and absolute-deadline expiry, while a subprocess writes after
  peer close and must report a structured reset instead of dying from SIGPIPE.
- **Unbounded poller concurrency.** The wake models cover one candidate writer
  or producer and the critical bounded close or drain/reset ordering. They do
  not quantify over an unbounded number of producers, repeated epochs, scheduler
  fairness, or weak-memory behavior beneath atom linearizability. Runtime tests
  cover repeated enter/drain/reset races, blocked remove, socket close, terminal
  poller close, acknowledged mutation, stale-event filtering, and
  write-vs-close pipe retirement.
