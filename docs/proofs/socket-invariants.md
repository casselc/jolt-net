# Socket invariants

Several properties in jolt-net are not testable by example in any convincing
way because they are about *orderings and interleavings* rather than values. A
test can show that one run behaved; it cannot show that no run misbehaves. The
formalized invariants below are checked with z3; runtime-only controls are
labelled explicitly.

Most legacy proof families have three standalone models, following the
convention already used in `jolt-upstream/test/chez/formal/` and
`jolt-tcp/docs/proofs/`:

| Suffix | Expected | Meaning |
|---|---|---|
| `-buggy` | **sat** | The naive implementation really does violate the property, and here is the counterexample. Without this, "corrected is unsat" might just mean the property was trivial. |
| `-corrected` | **unsat** | No run of the implemented design violates the property. |
| `-nonvacuity` | **sat** | The corrected model still describes a real scenario. Without this, unsat could mean the constraints were contradictory and the proof held for free. |

The errno, idempotent-close, connect ownership/completion, readiness-token,
short-lease, nonblocking-transition, accept-terminal-close, wake-pair, and
wake-epoch families use multi-query models and machine-readable anti-vacuity
contracts. Every file is directly
runnable with `z3`; Chiasmus
strips included query commands before invoking its embedded solver. See
[`models/README.md`](models/README.md) for commands, exact expected results,
unsat cores, bounds, and omitted behavior.

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

- `errno-capture-ordering.smt2` first asks whether the independently derived
  reference and immediate-capture implementation classify any observation
  differently. The result is **unsat** across all positive distinct failure and
  cleanup codes and every observed integer code.
- The same disagreement query under the capture-after-cleanup selector is
  **sat** with `fail-code=1`, `cleanup-code=2`, and
  `observed-reported-code=1`: the reference accepts the preserved failure while
  the faulty implementation rejects it in favor of cleanup's code.
- Separate **sat** boundaries explicitly accept the immediate-capture
  observation and reject a report containing the cleanup code. These are not
  mutation queries.

The corrected **unsat** result checks consistency between two independently
encoded classifiers; once immediate capture is selected their equivalence is
necessarily definitional. The fault and boundary witnesses are therefore
load-bearing evidence that the model distinguishes capture order and a visible
cleanup clobber.

The machine-readable contract is enforced with:

```sh
sh test/formal/check-errno-capture-ordering.sh
```

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

**Result.** `idempotent-close.smt2` independently classifies the observed native
close count against the reference value one and against the selected
implementation's derived close count.

- The corrected CAS implementation's disagreement query is **unsat**: atomicity
  supplies at most one winner and progress supplies at least one.
- The split check-then-act safety mutant is **sat** when both callers attempted
  close and observed `:open`, producing two implementation closes.
- The omitted-side-effect progress mutant is **sat** even though one caller won
  ownership, because it produces zero implementation closes.
- Separate **sat** boundaries accept one observed close and reject two while
  both callers attempted close and exactly one won the CAS.

The corrected **unsat** result checks consistency between independent
classifiers; the two mutants and classified boundaries are load-bearing evidence
for the safety and progress halves of exactly-once close. The machine-readable
contract runs directly with `check-idempotent-close.sh` and as part of
`test/formal/check-all.sh`.

The runtime counterpart asserts `close!` returns `true` then `false` on repeated
calls, and that 50 failed `listen` attempts leak no descriptors. It verifies the
real wiring but does not force a concurrent same-handle race; the bounded model
and source CAS supply that interleaving argument. The next section covers the
lease protocol added around the unique close owner.

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

- `readiness-token.smt2` gives the complete post-poll dispatch decision an
  arithmetic reference classifier and an independently derived implementation
  relation. The corrected disagreement query is **unsat**. Four localized
  **sat** mutants omit generation equality, revision equality, current
  registration existence, or snapshot-lease admission. The generation mutant
  preserves the concrete raw-fd witness: select at step 0, close at 1, reuse fd
  8 at 2 under generation 10, then dispatch generation 9 at 3.
- Nine **sat** boundaries admit one current complete-token dispatch, correctly
  suppress generation mismatch, revision mismatch, acknowledged removal,
  missing lease, empty native events, foreign poller id, and mismatched
  registration id, then independently reject a stale generation that is
  observed as dispatched.
- `short-lease.smt2` independently classifies the handle admission, syscall,
  release, native-close, and wait-cycle observations. Its corrected disagreement
  query is **unsat**.
- Four localized **sat** mutants admit after close begins, let a syscall escape
  its lease and begin after native close, close natively before the admitted
  lease releases, or accept the blocking return/close/release wait cycle.
- Eight **sat** boundaries accept an admitted close race, completion before
  close, a rejected late acquire, and an incomplete wait graph, then reject all
  four mutant observations under the corrected selector.

The blocking-cycle mutant is both an anti-vacuity control and a design
constraint: the implementation does not wrap uninterruptible blocking `accept`
or `connect` in these leases. A poll snapshot is wakeable and uses a finite
native safety interval. The machine-readable contract runs with
`check-short-lease.sh` and through `test/formal/check-all.sh`.

The readiness model follows Chiasmus's config-equivalence skeleton. Chiasmus
lint reported no structural errors, and all 14 query scopes independently
verified as one corrected **unsat** result with the same eight-label core plus
13 concrete **sat** controls. The reusable checker additionally enforces the
four mutants and nine classified boundaries on every CI run.

The executable counterpart in `jolt.net.poller-test` invalidates blocked native
snapshots by acknowledged remove and socket close, then requires an empty event
set. It proves that an updated token supersedes the old revision while the
successor still receives readiness, and that native close remains deferred until
a held lease is released. A synthetic native-call oracle additionally proves
that close rejects later acquisition and calls native close exactly once, only
after the admitted lease releases.

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

- `wake-pair.smt2` independently classifies seven-step observations with an
  arithmetic reference penalty and a source-shaped implementation relation.
  The corrected disagreement query is **unsat**.
- Three localized **sat** mutants admit a writer after retirement, decrement the
  writer count before releasing its handle lease, or close without draining an
  admitted writer. The first replaces the old contradictory late-writer arm,
  which required both admit-before-retire and retire-before-admit.
- Six **sat** boundaries accept a writer crossing retirement, a writer completed
  before retirement, and a rejected late attempt, then reject each mutant
  observation under the corrected selector.

The corrected **unsat** result checks consistency between independent
classifiers. The three mutants and six classified boundaries make admission,
release order, and drain independently load-bearing. The machine-readable
contract runs with `check-wake-pair.sh` and through `test/formal/check-all.sh`.

The forced-interleaving runtime test holds one admitted writer across close,
proves close cannot retire the read end, rejects a later writer after
retirement, and requires completion only after the admitted writer releases.

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

Released Jolt accepts a `:varargs` marker after the two fixed argument types and
lowers it to Chez's `(__varargs_after 2)` convention. jolt-net additionally
reads `F_GETFL` after `F_SETFL` and does not mark or return a handle unless
`O_NONBLOCK` is observable. The read-back is intentionally retained even with
the corrected ABI declaration: it makes a future compiler, libc, or binding
regression fail closed before any short lease is admitted.

- `nonblocking-transition.smt2` independently classifies bounded observations
  with an arithmetic reference penalty and a source-shaped implementation
  relation. The corrected disagreement query is **unsat**.
- Five localized **sat** mutants independently omit the variadic declaration,
  the post-`F_SETFL` read-back, the observed `O_NONBLOCK` bit, mark-after-return
  ordering, or admission-after-mark ordering. No mutant relies on another
  omitted requirement to produce its witness.
- Nine **sat** boundaries accept a useful verified transition, missing-bit and
  native-failure fail-closed outcomes, and a present bit with neighboring flag
  data; five rejected observations pin each individual requirement.

The corrected **unsat** result checks consistency between independent
classifiers. The five mutants and nine explicitly classified boundaries are
the load-bearing evidence that the model distinguishes the ABI, read-back,
flag, bookkeeping, and admission obligations. The machine-readable contract
runs directly with `check-nonblocking-transition.sh` and through
`test/formal/check-all.sh`.

The runtime counterpart independently calls `F_GETFL` on a newly returned
listener and requires `O_NONBLOCK` before the rest of the poller suite runs. It
classifies the bit with and without neighboring flags, forces the source order
transition → mark → operation, and proves a thrown transition reaches neither
later step. It also injects the motivating bad outcome—apparent `F_SETFL`
success followed by a read-back without the bit—and requires a fail-closed
exception after the second `F_GETFL`. The core FFI test exercises the same
variadic `fcntl` binding and transition. The complete Darwin arm64 gate passed with source-built Chez
10.4.1 for commit `65a0f1e` in
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

- `wake-epoch.smt2` independently classifies one coalesced producer with an
  arithmetic reference penalty and explicit implementation scenarios. The
  corrected disagreement query is **unsat**.
- Three localized **sat** mutants omit the epoch increment, drain-side restore,
  or entry-side restore. The drain mutant uses a post-poll drain between awaits,
  where the next await captures the already-current epoch and cannot recover a
  byte that drain failed to preserve. The entry mutant places the producer after
  drain returns, where only the current await's entry comparison can recover it.
- Six **sat** boundaries accept producer placement during drain, after drain,
  and between awaits, then reject each mutant observation under the corrected
  selector.

The corrected **unsat** result checks consistency between independent
classifiers. The mutants and boundaries make the epoch increment and both
restore positions independently load-bearing. The machine-readable contract
runs with `check-wake-epoch.sh` and through `test/formal/check-all.sh`.

The repeated runtime race exercises the combined entry/drain protocol. A
deterministic hook additionally advances the epoch after drain captures its
starting value and proves drain restoration writes exactly one byte while the
later entry ensure coalesces behind it.

**Accept-terminal invariant.** Given the verified non-blocking transition
above, a registration-removal wake is intentionally a best-effort state-change
notification, not a cancellation boundary. Listener close can race just before
accept enters `await-ready`: the already-published wake is then a pre-entry
epoch, may be drained before the native snapshot, and cannot by itself
guarantee prompt accept completion.

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

- `accept-terminal-close.smt2` independently classifies bounded observations
  with an arithmetic reference penalty and explicit implementation scenarios.
  The corrected disagreement query is **unsat**.
- Five localized **sat** mutants omit the terminal callback, lose a neighboring
  ordinary callback during reentrant removal, return before an active await
  exits, leave lifecycle open and admit a late await, or make await release
  depend on listener native close and enter the callback wait cycle.
- Nine **sat** boundaries accept active and late-await behavior under both
  callback orders, then reject each of the five mutant observations under the
  corrected selector.

The corrected **unsat** result checks consistency between independent
classifiers. The five mutants and nine explicitly classified boundaries make
callback preservation, completion, terminal admission, and the independent
wake-pipe release source load-bearing. The machine-readable contract runs with
`check-accept-terminal-close.sh` and through `test/formal/check-all.sh`.

The runtime counterpart waits until accept has installed both close listeners
instead of sleeping for a guessed scheduler interval. It bounds listener close
and accept completion independently, rejects an await attempted after terminal
close, and places callbacks before and after the accept-owned listeners to
verify their reentrant removal does not skip a neighboring callback.

---

## 4. Non-blocking connect ownership survives completion

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

**Verified model and controls.**

`connect-ownership-completion.smt2` independently classifies observable state
with an arithmetic reference distance and a source-shaped implementation
relation.

- The corrected implementation's disagreement query is **unsat**.
- Four localized **sat** mutants expose a synchronous rollback leak, an
  ownerless `EINPROGRESS` return, completion failure consuming caller ownership,
  and native close preceding an admitted `getsockopt(SO_ERROR)` read.
- Four accepted **sat** boundaries cover synchronous rollback, immediate
  success, and both asynchronous completion outcomes. The failure boundary
  carries a real close race ordered acquire/close/getsockopt/release/native-close
  at steps `0/1/2/3/4`.
- One rejected **sat** boundary holds the same completion race but returns the
  in-progress socket without an owner.

The corrected **unsat** result is a classifier-consistency check. The four
mutants and five explicitly classified boundaries are the load-bearing evidence
for rollback, ownership transfer, completion ownership, and lease ordering. The
machine-readable contract runs directly with
`check-connect-ownership-completion.sh` and through `test/formal/check-all.sh`.

The finite model has one socket, one completion, one closer, five distinct event
positions, and atomic atom/lease transitions. It omits repeated completion
polls, multiple close callers, kernel `SO_ERROR` behavior, resolver scheduling,
and weak memory below atom linearizability.

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

## What is deliberately not modelled

- **Resolver copy-before-free.** This is a memory-lifetime property, and the
  runtime test is stronger evidence than a model would be: it scribbles 800 KB of
  fresh native memory over the freed `addrinfo` chain, forces a collection, and
  then asserts the copied bytes are unchanged and still able to bind. A borrowed
  pointer fails there loudly. A model would only restate the intent.
- **Struct layouts.** Not a logic property. These are checked against the
  platform's own headers by `tools/probe-constants.sh`, which is ground truth
  rather than a model of it.
- **Native calling conventions.** The non-blocking model proves the source-level
  admission and fail-closed postcondition. The core FFI regression and Darwin
  runtime gate, not SMT, are the evidence that Chez's variadic convention maps
  to the platform ABI.
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
