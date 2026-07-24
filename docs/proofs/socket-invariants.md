# Socket invariants

Two properties in jolt-net are not testable by example in any convincing way,
because both are about *orderings and interleavings* rather than values. A test
can show that one run behaved; it cannot show that no run misbehaves. Those two
are modelled here and checked with z3.

Each invariant has three models, following the convention already used in
`jolt-upstream/test/chez/formal/` and `jolt-tcp/docs/proofs/`:

| Suffix | Expected | Meaning |
|---|---|---|
| `-buggy` | **sat** | The naive implementation really does violate the property, and here is the counterexample. Without this, "corrected is unsat" might just mean the property was trivial. |
| `-corrected` | **unsat** | No run of the implemented design violates the property. |
| `-nonvacuity` | **sat** | The corrected model still describes a real scenario. Without this, unsat could mean the constraints were contradictory and the proof held for free. |

Run them with `chiasmus_verify`, solver `z3`. The files are Chiasmus-ready
fragments: `(check-sat)` and `(get-model)` are supplied by the tool, so they are
deliberately absent here.

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
calls, and that 50 failed `listen` attempts leak no descriptors.

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
- **Anything involving the poller.** Out of scope in this slice. The wakeup and
  lost-wake invariants are where the next round of modelling belongs — the
  existing `jolt-tcp/docs/proofs/` models cover today's reactor.
