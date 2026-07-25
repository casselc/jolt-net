# Socket invariant models

These are bounded counterexample queries, not unbounded proofs of every operating
system behavior. Each model records its finite domain and abstraction in
comments. A `sat` result for a `-buggy` or `-control` model is an intentional
witness; an `unsat` result means no violating execution exists inside that
model. A separate `-nonvacuity` model demonstrates that each corrected design
still permits useful work.

Every `.smt2` file includes its own `(check-sat)` and either `(get-model)` or
`(get-unsat-core)`, so a standalone Z3 can run it directly:

```sh
for model in docs/proofs/models/*.smt2; do
  printf '%s: ' "$model"
  z3 "$model"
done
```

Chiasmus removes those query commands before invoking its embedded solver. On
2026-07-24 all 27 files passed the Chiasmus SMT-LIB linter with zero errors and
were solver-checked with its local `z3-solver` package. The host did not have a
standalone `z3` executable; Chiasmus verification, rather than the shell example
above, is the recorded solver run.

Three more files were added on 2026-07-24 for task W1
(`docs/WINDOWS-RUNTIME-SEQUENCE.md`) and solver-checked the same way:
`winsock-init-once-buggy.smt2`, `winsock-init-once-corrected.smt2`, and
`winsock-init-once-nonvacuity.smt2`.

## Expected results

| Model | Expected | Essential witness or unsat core |
|---|---:|---|
| `errno-capture-ordering-buggy.smt2` | `sat` | failure is positive, runtime reactivation leaves `0`, late capture reports `0` |
| `errno-capture-ordering-corrected.smt2` | `unsat` | `failing_call_sets_errno`, `capture_in_foreign_return`, `reported_from_pair`, `property_violated` |
| `errno-capture-ordering-nonvacuity.smt2` | `sat` | failure `1`, runtime reactivation `0`, captured pair still reports `1` |
| `idempotent-close-buggy.smt2` | `sat` | both callers win; `close_count = 2` |
| `idempotent-close-corrected.smt2` | `unsat` | `cas_atomicity`, `someone_closes`, `property_violated` |
| `idempotent-close-nonvacuity.smt2` | `sat` | contention with exactly one CAS winner |
| `descriptor-reuse-buggy.smt2` | `sat` | steps `0/1/2/3`, fd `8`, generation `9 -> 10` |
| `short-lease-post-close-corrected.smt2` | `unsat` | syscall inside lease, drain before close, post-close violation |
| `blocking-lease-deadlock-control.smt2` | `sat` | syscall/close/release wait cycle; `deadlock = true` |
| `readiness-generation-mismatch.smt2` | `unsat` | generation mismatch conflicts with complete-token dispatch |
| `readiness-revision-mismatch.smt2` | `unsat` | revision mismatch conflicts with complete-token dispatch |
| `readiness-current-token-nonvacuity.smt2` | `sat` | fd `8`, generation `10`, revision `3`, dispatch allowed |
| `nonblocking-transition-buggy.smt2` | `sat` | fixed declaration, successful return, absent bit, marked handle, blocking-capable admission |
| `nonblocking-transition-corrected.smt2` | `unsat` | explicit ABI boundary plus observed-bit mark postcondition exclude both violation branches |
| `nonblocking-transition-nonvacuity.smt2` | `sat` | observed bit permits a marked handle and useful short operation |
| `accept-terminal-close-buggy.smt2` | `sat` | pre-entry wake consumed; poller remains open; accept remains blocked after listener close |
| `accept-terminal-close-corrected.smt2` | `unsat` | active await exits, late await is rejected, and no callback/native-close wait cycle exists |
| `accept-terminal-close-nonvacuity.smt2` | `sat` | removal callback first; active await exits; listener close succeeds |
| `wake-pair-buggy.smt2` | `sat` | admit/read-close/write/release steps `0/2/3/4` |
| `wake-pair-corrected.smt2` | `unsat` | CAS gate, lease-before-count release, drain, write-first/read-last |
| `wake-pair-nonvacuity.smt2` | `sat` | writer crosses retirement, drains, and both ends close |
| `wake-epoch-buggy.smt2` | `sat` | drain/producer/reset/park steps `0/1/2/3`; no byte remains |
| `wake-epoch-corrected.smt2` | `unsat` | entry-epoch restore guarantees a byte for a new epoch |
| `wake-epoch-nonvacuity.smt2` | `sat` | coalesced producer; drain restores byte; await progresses |
| `connect-ownership-completion-buggy.smt2` | `sat` | `in_progress` returned with zero owners; native close step 2 precedes `getsockopt` step 3; completion takes close ownership |
| `connect-ownership-completion-corrected.smt2` | `unsat` | returned ownership, rollback, completion preservation, and lease-drain facts exclude all four violation branches |
| `connect-ownership-completion-nonvacuity.smt2` | `sat` | in-progress/refusal close race orders events `0/1/2/3/4` and retains one owner |
| `winsock-init-once-buggy.smt2` | `sat` | two distinct gate entrants both win; `attempt_count = 2`; thrown attempt leaves outcome/delivery/terminal/completion false |
| `winsock-init-once-corrected.smt2` | `unsat` | one CAS winner plus explicit outcome, delivery, terminal, completion, and observer definitions exclude the shared violation |
| `winsock-init-once-nonvacuity.smt2` | `sat` | two distinct entrants, one winner, thrown exception normalized to one delivered terminal error observed by both |

The full unsat cores observed in that run were:

```text
errno corrected:
  failing_call_sets_errno capture_in_foreign_return reported_from_pair
  property_violated

idempotent close corrected:
  cas_atomicity someone_closes property_violated

short lease corrected:
  syscall_requires_admitted_lease syscall_is_inside_short_lease
  drain_before_native_close violation_iff_syscall_begins_after_native_close
  property_violated

generation mismatch:
  generation_mismatch_iff_tokens_differ dispatch_iff_current_complete_token
  violation_iff_mismatch_is_dispatched property_violated

revision mismatch:
  revision_mismatch_iff_tokens_differ dispatch_iff_current_complete_token
  violation_iff_mismatch_is_dispatched property_violated

nonblocking transition corrected:
  binding_declares_varargs_after_two
  mark_iff_success_and_observed_postcondition
  short_operation_requires_marked_handle declaration_violation_definition
  lease_violation_definition violation_definition property_violated

accept terminal close corrected:
  listener_close_owns_the_transition accept_installs_terminal_callback
  listener_close_invokes_captured_terminal_callback
  active_await_exits_before_poller_close_returns
  terminal_callback_retires_poller_lifecycle
  late_admission_iff_attempt_on_open_poller
  active_survival_iff_terminal_return_did_not_release_await
  poller_wake_close_is_independent_of_listener_native_close
  callback_cycle_iff_all_three_wait_edges_exist
  blocked_iff_active_wait_survives_or_late_wait_is_admitted
  violation_iff_blocked_after_return_or_callback_cycle property_violated

wake pair corrected:
  one_cas_admission_gate late_writer_iff_admitted_after_retirement
  handle_lease_released_before_writer_count
  admitted_writers_drain_before_write_close
  write_end_closes_before_read_end active_writer_iff_handle_lease_not_released
  violation_iff_late_admission_or_live_writer property_violated

wake epoch corrected:
  entry_restore_iff_epoch_advanced byte_iff_written_or_restored
  new_epoch_iff_wake_after_entry violation_iff_new_epoch_has_no_byte
  property_violated

connect ownership/completion corrected:
  return_iff_immediate_or_in_progress
  rollback_closes_every_unreturned_failure
  ownership_transfers_for_every_returned_status
  finish_runs_iff_completion_selected
  completion_only_for_in_progress_in_this_bound
  finish_never_closes_or_transfers finish_preserves_owner_count
  lease_admission_rejects_after_close getsockopt_requires_admitted_lease
  getsockopt_inside_short_lease native_close_waits_for_completion_release
  returned_owner_violation_definition rollback_violation_definition
  post_close_completion_violation_definition
  completion_owner_violation_definition violation_definition violation_query

winsock init once corrected:
  cas_at_most_one cas_has_a_winner attempt_count_definition
  canonical_outcome_definition outcome_production_definition
  delivery_definition terminal_definition completion_definition
  t1_observation_definition t2_observation_definition
  violation_definition violation_query
```

## Source and runtime oracles

The models deliberately stay small; the implementation and forced
interleaving tests supply the semantic oracle:

- `jolt.net.ffi/captured-call` contains every failure-sensitive blocking
  binding, and `invoke-captured` has the single result shape
  `[native-result native-error]`. `jolt.net.error/checked-captured`,
  `jolt.net.poller/poll-once`, and `jolt.net.resolver/resolve` consume that pair
  without a later error-slot read. The errno-ordering models treat this foreign
  return as their `capture` event; Chez convention correctness is established
  by the core fork's `docs/ffi-native-error-capture.md` and native controls, not
  by these bounded models.
- `jolt.net.handle/acquire!`, `release!`, and `close!` implement the
  open/admit, drain, and native-close transitions used by the lease models.
- `jolt.net.poller/await-ready` compares the complete captured registration
  token with the current token after native poll, corresponding to the
  generation and revision models.
- `jolt.net.ffi/p-fcntl` declares `{:varargs-after 2}`, and
  `jolt.net.nonblocking/set-raw!` reads `F_GETFL` back before any handle is
  marked. `test/jolt/net/poller_test.clj` independently observes the bit on a
  returned listener and injects the buggy control where `F_SETFL` appears to
  succeed but read-back still lacks the bit. These are the source and runtime
  oracles for the non-blocking-transition models.
- `jolt.net/accept` installs a terminal listener before poller registration;
  `jolt.net.poller/close!` retires admission and waits for an active await to
  exit. `jolt.net.handle/release!` can release the await's listener lease while
  listener notification is still in progress. These are the source oracles for
  the accept-terminal-close models.
- `jolt.net.poller/acquire-wake-write!`, `release-wake-write!`,
  `retire-wake-writes!`, and `finish-close!` correspond to the wake-pair gate,
  release, drain, and write-first/read-last transitions.
- `signal-wake!`, `drain-wake-pipe!`, and the await-entry epoch comparison
  correspond to the wake-epoch models.
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
- `jolt.net.ffi/ensure-once!` implements the nil-to-promise CAS gate, catches a
  thrown attempt into the canonical error outcome, publishes terminal state,
  delivers the promise, and only then resolves the winner's public call.
  `ensure-subsystem!` supplies the process-global Winsock state and native
  attempt. `jolt.net.resolver/resolve` calls it before `getaddrinfo`, which is
  the source oracle for the ordering half of task W1. The runtime oracle first
  exercises fresh-state returned-error and thrown-exception controls, then puts
  32 distinct first-use futures behind a latch/start barrier and asserts exactly
  one real `WSAStartup` attempt.

The models omit scheduler fairness, native ABI implementation, weak-memory
behavior beneath Clojure atom linearizability, failure of the atom/promise
primitives themselves, thread death/cancellation, kernel bugs, numeric
descriptor allocation policy, and the unbounded number of producers or
registrations. The implementation therefore still needs the runtime race tests
and platform CI; these models do not replace them.
