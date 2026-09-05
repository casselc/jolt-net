# Socket invariant models

These are bounded counterexample queries, not unbounded proofs of every operating
system behavior. Each model records its finite domain and abstraction in
comments. A `sat` result for a `-buggy` or `-control` model is an intentional
witness; an `unsat` result means no violating execution exists inside that
model. A separate `-nonvacuity` model demonstrates that each corrected design
still permits useful work.

Every `.smt2` file contains its solver queries, so a standalone Z3 can run it
directly:

```sh
for model in docs/proofs/models/*.smt2; do
  printf '%s: ' "$model"
  z3 "$model"
done
```

The consolidated contract models are additionally checked through the
structural anti-vacuity CLI pinned in `bb.edn`:

```sh
sh test/formal/check-all.sh
```

That gate rejects reference aliases, shared derived decision helpers, missing
or unclassified controls, vacuous boundaries, and unexpected query drift. It
currently covers the consolidated errno, idempotent-close, non-blocking connect
ownership/completion, readiness-token, non-blocking-transition,
accept-terminal-close, wake-pair, and wake-epoch models; the other proof
families retain their standalone query conventions.

The 2026-07-24 audit covered the then-current 27 files with Chiasmus's SMT-LIB
linter and embedded `z3-solver`, because that host did not have a standalone
`z3` executable. Consolidating the errno, idempotent-close, connect
ownership/completion, readiness-token, non-blocking-transition,
accept-terminal-close, wake-pair, and wake-epoch families leaves 10 `.smt2`
files in the current tree. The formal CI above now checks all eight contracts
with pinned Babashka 1.13.220 and standalone Z3 4.8.12 in addition to the direct
model queries.

## Expected results

| Model | Expected | Essential witness or unsat core |
|---|---:|---|
| `errno-capture-ordering.smt2` | `unsat`, `sat`, `sat`, `sat` | corrected disagreement, capture-after-cleanup fault, accepted immediate capture, rejected clobbered report |
| `idempotent-close.smt2` | `unsat`, `sat`, `sat`, `sat`, `sat` | corrected consistency, split check-then-act safety fault, omitted-close progress fault, accepted single close, rejected double close |
| `connect-ownership-completion.smt2` | `unsat`, four `sat` mutants, five `sat` boundaries | corrected consistency; rollback leak, ownerless EINPROGRESS, completion-owned close, pre-SO_ERROR native close; accepted sync/immediate/completion outcomes and rejected ownerless return |
| `readiness-token.smt2` | `unsat`, four `sat` mutants, nine `sat` boundaries | corrected consistency; omitted generation/revision/current-registration/lease checks; current-token dispatch, complete-token suppressions, and rejected stale dispatch |
| `short-lease-post-close-corrected.smt2` | `unsat` | syscall inside lease, drain before close, post-close violation |
| `blocking-lease-deadlock-control.smt2` | `sat` | syscall/close/release wait cycle; `deadlock = true` |
| `nonblocking-transition.smt2` | `unsat`, five `sat` mutants, nine `sat` boundaries | corrected consistency; omitted ABI/read-back/observed-bit/mark/admission requirements; accepted useful and fail-closed outcomes plus rejected deviations |
| `accept-terminal-close.smt2` | `unsat`, five `sat` mutants, nine `sat` boundaries | corrected consistency; missing terminal/ordinary callback, incomplete active close, late admission, listener-close cycle; both callback orders and active/late boundaries |
| `wake-pair.smt2` | `unsat`, three `sat` mutants, six `sat` boundaries | corrected consistency; late admission, count-before-lease release, omitted drain; crossing/completed/rejected writers and corrected rejections |
| `wake-epoch.smt2` | `unsat`, three `sat` mutants, six `sat` boundaries | corrected consistency; omitted epoch, drain restore, entry restore; three producer positions and corrected rejections |

The full unsat cores observed in that run were:

```text
errno corrected:
  failure-sets-errno reference-distance-definition
  reference-classifier-definition implementation-capture-definition
  implementation-classifier-definition violation-definition
  corrected-selector corrected-counterexample-query

idempotent close corrected:
  cas-atomicity close-owner-progress reference-distance-definition
  reference-classifier-definition implementation-t1-close-definition
  implementation-t2-close-definition implementation-count-definition
  implementation-classifier-definition violation-definition
  corrected-selector corrected-counterexample-query

short lease corrected:
  syscall_requires_admitted_lease syscall_is_inside_short_lease
  drain_before_native_close violation_iff_syscall_begins_after_native_close
  property_violated

readiness token corrected:
  reference-blocker-count-definition reference-dispatch-definition
  reference-classifier-definition implementation-dispatch-definition
  implementation-classifier-definition violation-definition
  corrected-selector corrected-counterexample-query

nonblocking transition corrected:
  reference-nonblock-observed-definition reference-penalty-count-definition
  reference-classifier-definition implementation-nonblock-observed-definition
  implementation-transition-definition implementation-classifier-definition
  violation-definition corrected-selector corrected-counterexample-query

accept terminal close corrected:
  reference-penalty-count-definition reference-classifier-definition
  implementation-classifier-definition violation-definition
  corrected-selector corrected-counterexample-query

wake pair corrected:
  reference-penalty-count-definition reference-classifier-definition
  implementation-classifier-definition violation-definition
  corrected-selector corrected-counterexample-query

wake epoch corrected:
  wake-context-is-bounded producer-write-count-is-bounded
  drain-restore-write-count-is-bounded entry-restore-write-count-is-bounded
  reference-penalty-count-definition reference-classifier-definition
  implementation-classifier-definition violation-definition
  corrected-selector corrected-counterexample-query

connect ownership/completion corrected:
  constructor-owner-count-is-bounded
  reference-initiation-penalty-definition
  reference-completion-penalty-definition reference-lease-penalty-definition
  reference-order-penalty-definition reference-distance-definition
  reference-classifier-definition implementation-return-rule-definition
  implementation-raw-lifetime-rule-definition
  implementation-constructor-owner-rule-definition
  implementation-completion-domain-rule-definition
  implementation-finish-run-rule-definition
  implementation-finish-close-rule-definition
  implementation-completion-owner-rule-definition
  implementation-lease-admission-rule-definition
  implementation-getsockopt-rule-definition
  implementation-lease-order-rule-definition
  implementation-native-close-rule-definition
  implementation-classifier-definition violation-definition
  corrected-selector corrected-counterexample-query
```

## Source and runtime oracles

The models deliberately stay small; the implementation and forced
interleaving tests supply the semantic oracle:

- `jolt.net.handle/acquire!`, `release!`, and `close!` implement the
  open/admit, drain, and native-close transitions used by the lease models.
- `jolt.net.poller/await-ready` compares the complete captured registration
  token with the current token after native poll, corresponding to the
  generation and revision models.
- `jolt.net.ffi/p-fcntl` places `:varargs` after its two fixed arguments, and
  `jolt.net.nonblocking/set-raw!` reads `F_GETFL` back before any handle is
  marked. Constructor call sites complete `set-raw!` before ownership transfer;
  handle call sites mark only after it, and `with-nonblocking-lease` invokes the
  operation only after the mark. `test/jolt/net/poller_test.clj` independently
  observes the bit on a returned listener, checks neighboring flag values,
  verifies the transition/mark/admission order, and injects both a thrown
  transition and the buggy apparent-success/missing-bit outcome. These are the
  source and runtime oracles for the non-blocking-transition model.
- `jolt.net/accept` installs a terminal listener before poller registration;
  `jolt.net.poller/close!` retires admission and waits for an active await to
  exit. `jolt.net.handle/release!` can release the await's listener lease while
  listener notification is still in progress. The runtime suite verifies active
  await completion, late-await rejection, terminal lifecycle, and neighboring
  callbacks around reentrant listener removal. These are the source and runtime
  oracles for the accept-terminal-close model.
- `jolt.net.poller/acquire-wake-write!`, `release-wake-write!`,
  `retire-wake-writes!`, and `finish-close!` correspond to the wake-pair gate,
  release, drain, and write-first/read-last transitions. The forced runtime race
  holds an admitted writer across retirement, proves close waits and preserves
  the read end, rejects a later writer, then verifies completion after release.
- `signal-wake!`, `drain-wake-pipe!`, and the await-entry epoch comparison
  correspond to the wake-epoch model. The runtime suite combines a repeated
  enter/drain race with a deterministic hook that advances the epoch after the
  drain snapshot and observes exactly one restored byte.
- `jolt.net/try-connect-address` transfers both successful initiation statuses
  through `h/own`, while its pre-transfer catch uses `h/raw-close!`.
  `jolt.net/finish-connect!` reads `SO_ERROR` inside `h/with-lease` and contains
  no close path; these are the source oracles for the connect models.
- `test/jolt/net/socket_test.clj` waits for both accept close paths, then bounds
  listener close and accept completion while callbacks on both sides of the
  internal listeners prove reentrant map mutation does not skip callbacks.
- `test/jolt/net/poller_test.clj` exercises connect classification, real
  `SO_ERROR` success/refusal, ownership after failure, rollback leak detection,
  absolute-deadline composition, lease drain, stale token filtering, forced
  write-vs-close retirement, and the enter/drain/reset wake race against the
  real implementation.

The models omit scheduler fairness, native ABI implementation, weak-memory
behavior beneath Clojure atom linearizability, kernel bugs, numeric descriptor
allocation policy, and the
unbounded number of producers or registrations. The implementation therefore
still needs the runtime race tests and platform CI; these models do not replace
them.
