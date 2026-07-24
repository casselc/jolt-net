# Platform coverage

What is actually verified, and by what means. The distinction matters: a green table
test proves the *selection logic*, not that the constants in the table are right.

Do not summarize this file as "supports Linux, macOS and Windows."

## Levels of evidence

| Level | Meaning |
|---|---|
| **runtime** | Real sockets opened, real syscalls made, on this platform in CI. |
| **header** | Constants and struct offsets extracted from that platform's real system headers by `tools/probe-constants.sh` and diffed against the committed table. Proves the numbers; proves nothing about calls. |
| **table** | Only the selection logic is exercised. The numbers themselves are unverified. |
| **none** | Not covered. |

## Current state

| Platform | Constants / layouts | Socket calls | Notes |
|---|---|---|---|
| Linux x86-64 | runtime + header | **runtime** | The development and CI platform. |
| Linux aarch64 | table | none | Aliases the x86-64 socket facts: same kernel UAPI, same LP64. An explicit table entry with the checked facts listed — never a `:linux` fallback. |
| Windows x86-64 | **header** | none | Extracted from real `winsock2.h`/`ws2tcpip.h` via mingw gcc, cross-checked against .NET's `SocketError`/`AddressFamily`. Numbers are trustworthy; **no Winsock call has ever been made from jolt on Windows.** |
| macOS arm64 / x86-64 | **table** | none | No macOS machine and no compiler available. Constants are from documentation and headers not present on this system. This is the weakest coverage in the project. |

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

## Making this better

The cheap win is a macOS runner: it would move an entire column from *table* to
*runtime*. Second is a Windows runner, which would upgrade *header* to *runtime* and
retire the `:uptr` and `WSAStartup` residuals above.
