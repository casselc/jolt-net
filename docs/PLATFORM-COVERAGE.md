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
| Windows x86-64 | **probed** | none | mingw cross-compiles the probe and the resulting `.exe` runs through WSL interop, so the layouts are read from real `winsock2.h`/`ws2tcpip.h` rather than recalled; cross-checked against .NET's `SocketError`/`AddressFamily`. Numbers are trustworthy; **no Winsock call has ever been made from jolt on Windows.** |
| macOS arm64 / x86-64 | **table** | none | No macOS machine and no cross-compiler available. Every constant is documentation-derived; the descriptor is marked `:evidence :documented` and the suite emits a SKIP rather than a pass. This is the weakest coverage in the project. |

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

Two facts that a hand-written table gets wrong, both confirmed by running the probe:

- **`addrinfo` field order differs.** Linux places `ai_addr` at 24 and `ai_canonname`
  at 32; Windows (and macOS) reverse them. Reading one platform's offsets on the
  other yields a pointer to the wrong field.
- **Windows `ai_addrlen` is `size_t` (8 bytes)**, not `socklen_t` (4).
  `jolt.mvn-http` reads it as `:int` and only survives because Win64 is
  little-endian and address lengths are small.

The table test is non-vacuous: corrupting `AF_INET6` or swapping
`ai_addr`/`ai_canonname` makes it fail with the exact field named.

## Making this better

The cheap win is running `tools/probe-constants.sh` on any Mac and committing
`tools/probed/darwin-*.edn` — that alone moves macOS from *table* to *probed* with
no code change, because the test picks the file up automatically.

A real Windows runner would upgrade Windows from *probed* to *runtime* and retire
the `:uptr` marshaling and `WSAStartup` residuals above.
