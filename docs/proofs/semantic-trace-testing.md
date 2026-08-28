# Semantic trace testing for transport lifecycles

This test slice checks the real `jolt.net.handle` state machine with generated
operation prefixes and a bounded `hegel.trace` model.  Identity is the handle's
ownership generation, not its numeric descriptor: an operating system may
reuse an fd immediately after native close.

The checked contract is:

- leases are admitted only while the generation is open;
- each admitted lease token is released at most once, with duplicate release
  rejected before it can decrement generation ownership;
- the first close attempt wins and later attempts are no-ops;
- native close occurs exactly once;
- native close cannot occur while an admitted lease remains; and
- every generated prefix is completed to a closed, lease-free snapshot.

The test adapter emits the same `:seq`, `:operation-id`,
`:parent-operation-id`, `:aspect`, `:library`, `:role`, and `:phase` vocabulary
as the compiler-aspect observation journal.  It is intentionally test-side so
the ordinary suite still runs on released Jolt v0.7.28.  On an aspect-capable
compiler, advice around `jolt.net.handle/acquire!`, `release!`, `close!`, and
the owned `jolt.net.ffi/close` call can supply the same maps to the unchanged
rules.

## Reuse in the owned transport stack

`jolt-tcp` should scope registration, readiness, EOF, queued-write completion,
and retirement events by `[generation revision]`.  The first reuse is to prove
that stale readiness never acts after registration retirement and that peer EOF
does not retire a generation before queued output reaches a terminal outcome.

`jolt-http` should add request/response operation ids above the TCP generation.
The first reuse is to prove that a parsed request has exactly one terminal
response or exception, response completion precedes connection retirement, and
a peer half-close cannot preempt an already accepted request's response.

The rule itself is not transport-specific.  A small shared helper should live
next to `hegel.trace` once a second consumer confirms the event names: a
generation-scoped linear-resource model parameterized by acquire, release,
close-attempt, and terminal predicates.  Keeping that helper in Hegel avoids
copying subtly different lease counters into net, TCP, HTTP, database handles,
and FFI arenas.
