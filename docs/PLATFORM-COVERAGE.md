# Platform coverage

What is actually verified, and by what means. The distinction matters: a green table
test proves the *selection logic*, not that the constants in the table are right.

Do not summarize this file as "supports Linux, macOS and Windows."

## Levels of evidence

| Level | Meaning |
|---|---|
| **runtime** | Real sockets opened, real syscalls made, on this platform in CI. |
| **probed** | `tools/probe-constants.c` compiled against that platform's real system headers and **executed**, so every constant, `sizeof`, and `offsetof` is read from the platform itself and diffed against the committed table. Proves the numbers; proves nothing about calls. |
| **table** | Only the selection logic is exercised. The numbers themselves are unverified. |
| **none** | Not covered. |

## Current state

| Platform | Constants / layouts | Socket calls | Notes |
|---|---|---|---|
| Linux x86-64 | **probed** | **runtime** | The development and CI platform. |
| Linux aarch64 | table | none | Aliases the x86-64 socket facts: same kernel UAPI, same LP64. An explicit table entry with the checked facts listed — never a `:linux` fallback. |
| Windows x86-64 | **probed** | none | Probed on a real Windows CI runner (and independently via mingw + WSL interop, which agree). Numbers are trustworthy; **no Winsock call has ever been made from jolt on Windows** — there is no packaged Chez Scheme for Windows runners, so the suite cannot run there yet. |
| macOS arm64 | **probed** | **runtime** | Probed and fully exercised on a macOS arm64 CI runner. |
| macOS x86-64 | **table** | none | Shares the arm64 descriptor: these are SDK facts rather than arch facts on macOS. Only arm64 is machine-checked. |

## Specific residuals

- **Win64 handle width.** `SOCKET` is pointer-width unsigned and `INVALID_SOCKET` is
  all-bits-one, so `neg?` and an `:int` return type are both invalid tests. The
  predicate is exercised on Linux against synthetic high-bit values, which proves the
  predicate but not the FFI marshaling of a `:uptr` result ≥ 2^63.
- **`WSAStartup` once-only initialization** is unexercised.
- **macOS `sin_len`.** BSD puts the struct size in byte 0 and the family in byte 1.
  Encoded and asserted from Linux against the Darwin descriptor, so the *encoder* is
  tested; that byte 0 must be 16 is taken from documentation.
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

The table test is non-vacuous: corrupting `AF_INET6` or swapping
`ai_addr`/`ai_canonname` makes it fail with the exact field named.

## Making this better

The remaining gap is Windows *runtime* coverage. It needs a Chez Scheme build for
Windows runners; until then the `:uptr` handle marshaling, the `INVALID_SOCKET`
comparison against a real handle, and once-only `WSAStartup` stay unexercised.

CI (`.github/workflows/ci.yml`) re-probes every platform on each push and fails
the build if a committed descriptor disagrees with that platform's real headers,
so these tables cannot silently drift.
