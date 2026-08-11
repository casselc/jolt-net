/* probe-constants.c -- emit this platform's real socket ABI facts as EDN.
 *
 * The point of this file is that jolt-net's per-target tables are otherwise
 * numbers recalled from memory. Compiled against the platform's own headers,
 * this prints what the headers actually say, so a drift between table and
 * reality is a test failure rather than a memory-corruption bug months later.
 *
 * Windows builds must also RUN on the target. CI uses native MSVC for ARM64
 * and MinGW for x86_64, and executes both on Windows. Either way, each Windows
 * column is genuinely probed rather than guessed, and neither is copied from
 * the other -- the two files are produced by separate compilers on separate
 * machines and compared only afterwards.
 *
 * POSIX and Windows x86_64 use tools/probe-constants.sh. The native Windows
 * ARM64 lanes compile this source directly with MSVC and check the resulting
 * binary's PE machine type before trusting its output.
 */
#ifndef _WIN32
#  ifdef __APPLE__
     /* Defining only _POSIX_C_SOURCE hides the Darwin socket extensions this
        probe must verify, including SO_NOSIGPIPE, MSG_NOSIGNAL, and
        EAI_ADDRFAMILY.  Ask Apple's headers for their full public surface. */
#    define _DARWIN_C_SOURCE
#  else
#    define _POSIX_C_SOURCE 200112L
#  endif
#endif

#include <stdio.h>
#include <stddef.h>

#ifdef _WIN32
   /* WSAPOLLFD and WSAPoll are declared only for Vista and later. Raise the
      floor if the toolchain left it lower, but never lower a higher default:
      the point is to see the real declarations, not to pin an old SDK. */
#  ifndef _WIN32_WINNT
#    define _WIN32_WINNT 0x0600
#  elif _WIN32_WINNT < 0x0600
#    undef _WIN32_WINNT
#    define _WIN32_WINNT 0x0600
#  endif
#  include <winsock2.h>
#  include <ws2tcpip.h>
#  define OS_NAME "windows"
#else
#  include <sys/types.h>
#  include <sys/socket.h>
#  include <netinet/in.h>
#  include <netinet/tcp.h>
#  include <netdb.h>
#  include <errno.h>
#  include <fcntl.h>
#  include <poll.h>
#  ifdef __APPLE__
#    define OS_NAME "darwin"
#  else
#    define OS_NAME "linux"
#  endif
#endif

#if defined(__x86_64__) || defined(_M_X64)
#  define ARCH_NAME "x86-64"
#elif defined(__aarch64__) || defined(_M_ARM64)
#  define ARCH_NAME "aarch64"
#else
#  define ARCH_NAME "unknown"
#endif

/* Values absent on a platform are emitted as nil rather than omitted, so a
   missing key in the table is distinguishable from a genuinely absent constant. */
#define K(name, val) printf("  %s %d\n", name, (int)(val))
#define KNIL(name)   printf("  %s nil\n", name)
#define SZ(name, val) printf("  %s %zu\n", name, (size_t)(val))

int main(void) {
    printf("{:os :%s\n", OS_NAME);
    printf(" :arch :%s\n", ARCH_NAME);
    printf(" :pointer-bits %zu\n", sizeof(void *) * 8);
    printf(" :socket-handle-bytes %zu\n",
#ifdef _WIN32
           sizeof(SOCKET)
#else
           sizeof(int)
#endif
    );
    /* Winsock uses int for the address-length parameters accepted and
       returned by bind/connect/accept/getsockname/getpeername. MinGW exposes
       a compatibility socklen_t typedef, but the native MSVC headers do not;
       spell the Windows ABI fact directly so this probe compiles with the
       platform toolchain instead of accidentally depending on MinGW. */
    printf(" :socklen-bytes %zu\n",
#ifdef _WIN32
           sizeof(int)
#else
           sizeof(socklen_t)
#endif
    );
#ifdef _WIN32
    printf(" :nfds-bytes nil\n");
    /* ioctlsocket is `int ioctlsocket(SOCKET, long cmd, u_long *argp)`. NEITHER
       of those two types is pointer-width on Win64 -- both are 32 bits, unlike
       the LP64 platforms this codebase otherwise targets. A wider little-endian
       cell containing 1 might expose the same first four bytes, but would not
       establish the declared ABI. Probe both widths so the writer can fully
       initialize exactly one u_long without relying on incidental layout. */
    printf(" :ioctl-cmd-bytes %zu\n", sizeof(long));
    printf(" :ioctl-arg-bytes %zu\n", sizeof(u_long));
    /* WSAPoll's exact declared signature. The typed function-pointer
       initialization below does not merely record widths -- it FAILS TO
       COMPILE unless <winsock2.h> really declares
           int WSAAPI WSAPoll(LPWSAPOLLFD fdArray, ULONG fds, INT timeout)
       so the sizes printed under it describe the prototype the linker will
       actually call rather than a plausible reconstruction of one. Note that
       `fds` is a 32-bit ULONG, NOT the pointer-width nfds_t of POSIX poll, and
       that the timeout is a signed INT in milliseconds. */
    {
        int (WSAAPI *wsapoll_signature)(LPWSAPOLLFD, ULONG, INT) = WSAPoll;
        (void)wsapoll_signature;
    }
    printf(" :wsapoll {:fds-bytes %zu :timeout-bytes %zu :result-bytes %zu}\n",
           sizeof(ULONG), sizeof(INT), sizeof(int));
#else
    printf(" :nfds-bytes %zu\n", sizeof(nfds_t));
    printf(" :ioctl-cmd-bytes nil\n");
    printf(" :ioctl-arg-bytes nil\n");
#endif

    printf(" :const {\n");
    K(":af-unspec", AF_UNSPEC);
    K(":af-inet", AF_INET);
    K(":af-inet6", AF_INET6);
    K(":sock-stream", SOCK_STREAM);
    K(":sock-dgram", SOCK_DGRAM);
    K(":ipproto-tcp", IPPROTO_TCP);
    K(":ipproto-ipv6", IPPROTO_IPV6);
    K(":sol-socket", SOL_SOCKET);
    K(":so-reuseaddr", SO_REUSEADDR);
    K(":so-error", SO_ERROR);
    K(":so-rcvbuf", SO_RCVBUF);
    K(":so-sndbuf", SO_SNDBUF);
    K(":tcp-nodelay", TCP_NODELAY);
    K(":ipv6-v6only", IPV6_V6ONLY);
    K(":ai-passive", AI_PASSIVE);
    K(":ai-numerichost", AI_NUMERICHOST);
#ifdef AI_NUMERICSERV
    K(":ai-numericserv", AI_NUMERICSERV);
#else
    KNIL(":ai-numericserv");
#endif
    /* shutdown(2) directions: Winsock spells them SD_*, POSIX SHUT_*. Same
       meaning, and both are just 0/1/2, but read them rather than assume. */
#ifdef _WIN32
    K(":shut-rd", SD_RECEIVE);
    K(":shut-wr", SD_SEND);
    K(":shut-rdwr", SD_BOTH);
#else
    K(":shut-rd", SHUT_RD);
    K(":shut-wr", SHUT_WR);
    K(":shut-rdwr", SHUT_RDWR);
#endif
    /* SIGPIPE suppression: Linux passes MSG_NOSIGNAL per send, BSD/macOS sets
       SO_NOSIGPIPE per socket, Windows has no SIGPIPE at all. */
#ifdef MSG_NOSIGNAL
    K(":msg-nosignal", MSG_NOSIGNAL);
#else
    KNIL(":msg-nosignal");
#endif
#ifdef SO_NOSIGPIPE
    K(":so-nosigpipe", SO_NOSIGPIPE);
#else
    KNIL(":so-nosigpipe");
#endif
#ifdef O_NONBLOCK
    K(":o-nonblock", O_NONBLOCK);
    K(":f-getfl", F_GETFL);
    K(":f-setfl", F_SETFL);
#else
    KNIL(":o-nonblock"); KNIL(":f-getfl"); KNIL(":f-setfl");
#endif
    /* Windows has no fcntl; non-blocking mode is ioctlsocket(FIONBIO). Guarded
       on _WIN32 rather than on `#ifdef FIONBIO` because POSIX also defines a
       FIONBIO (in <sys/ioctl.h>, which this probe does not include) with a
       different value -- keying on the platform keeps this fact unambiguously
       about the Winsock call. The header defines it as _IOW('f', 126, u_long),
       whose value 0x8004667E does NOT fit a signed 32-bit int; the macro itself
       casts to long, so print the signed long the ABI actually passes instead
       of truncating it through this file's int-valued K(). */
#ifdef _WIN32
    printf("  :fionbio %ld\n", (long)FIONBIO);
#else
    KNIL(":fionbio");
#endif
    /* Readiness flags. Both platforms spell them POLL*, and jolt.net's poller
       reads them through the same descriptor keys -- but the VALUES are
       unrelated, so they must be probed on each platform rather than carried
       across. Winsock builds POLLIN out of POLLRDNORM|POLLRDBAND and POLLOUT
       out of POLLWRNORM alone; those components are emitted separately so the
       composition is visible evidence instead of an assumed identity. */
    K(":pollin", POLLIN);
    K(":pollout", POLLOUT);
    K(":pollerr", POLLERR);
    K(":pollhup", POLLHUP);
    K(":pollnval", POLLNVAL);
#ifdef _WIN32
    /* Windows-only keys. They are NOT emitted as nil on POSIX: the committed
       linux/darwin probe files are compared byte-for-byte by CI, and this
       probe cannot be re-run on a Mac from a Windows or WSL host. Adding a key
       to the POSIX column would invalidate a baseline nothing here can
       regenerate. Absent means absent. */
    K(":pollrdnorm", POLLRDNORM);
    K(":pollrdband", POLLRDBAND);
    K(":pollpri", POLLPRI);
    K(":pollwrnorm", POLLWRNORM);
    K(":pollwrband", POLLWRBAND);
#endif
    printf(" }\n");

    /* Struct layouts. Every offset a caller would otherwise hardcode. */
    printf(" :layout {\n");
    printf("  :sockaddr-in {:size %zu :family %zu :port %zu :addr %zu}\n",
           sizeof(struct sockaddr_in),
           offsetof(struct sockaddr_in, sin_family),
           offsetof(struct sockaddr_in, sin_port),
           offsetof(struct sockaddr_in, sin_addr));
    printf("  :sockaddr-in6 {:size %zu :family %zu :port %zu :flowinfo %zu :addr %zu :scope-id %zu}\n",
           sizeof(struct sockaddr_in6),
           offsetof(struct sockaddr_in6, sin6_family),
           offsetof(struct sockaddr_in6, sin6_port),
           offsetof(struct sockaddr_in6, sin6_flowinfo),
           offsetof(struct sockaddr_in6, sin6_addr),
           offsetof(struct sockaddr_in6, sin6_scope_id));
    printf("  :sockaddr-storage {:size %zu}\n", sizeof(struct sockaddr_storage));
#ifndef _WIN32
    printf("  :pollfd {:size %zu :fd %zu :events %zu :revents %zu}\n",
           sizeof(struct pollfd),
           offsetof(struct pollfd, fd),
           offsetof(struct pollfd, events),
           offsetof(struct pollfd, revents));
#else
    /* WSAPOLLFD is NOT struct pollfd. Its first member is a pointer-width
       SOCKET rather than an int, so events/revents do not sit where the POSIX
       layout puts them and the struct carries tail padding the POSIX one does
       not. Emitted under its own key, with every field width spelled out, so
       nothing here can be satisfied by a POSIX pollfd fact that happens to be
       nearby. The struct-tag spelling is deliberate: MinGW and MSVC agree on
       the WSAPOLLFD typedef, and reading its members is the whole point. */
    printf("  :wsapollfd {:size %zu :fd %zu :events %zu :revents %zu"
           " :fd-bytes %zu :events-bytes %zu :revents-bytes %zu}\n",
           sizeof(WSAPOLLFD),
           offsetof(WSAPOLLFD, fd),
           offsetof(WSAPOLLFD, events),
           offsetof(WSAPOLLFD, revents),
           sizeof(((WSAPOLLFD *)0)->fd),
           sizeof(((WSAPOLLFD *)0)->events),
           sizeof(((WSAPOLLFD *)0)->revents));
#endif
    printf("  :addrinfo {:size %zu :flags %zu :family %zu :socktype %zu :protocol %zu :addrlen %zu :canonname %zu :addr %zu :next %zu :addrlen-bytes %zu}\n",
           sizeof(struct addrinfo),
           offsetof(struct addrinfo, ai_flags),
           offsetof(struct addrinfo, ai_family),
           offsetof(struct addrinfo, ai_socktype),
           offsetof(struct addrinfo, ai_protocol),
           offsetof(struct addrinfo, ai_addrlen),
           offsetof(struct addrinfo, ai_canonname),
           offsetof(struct addrinfo, ai_addr),
           offsetof(struct addrinfo, ai_next),
           sizeof(((struct addrinfo *)0)->ai_addrlen));
    /* sin_len: BSD/macOS put the struct length in byte 0 and the family in
       byte 1. Detected structurally -- if sin_family is at offset 1, this is a
       sin_len platform. */
    printf("  :sin-len? %s\n",
           offsetof(struct sockaddr_in, sin_family) == 1 ? "true" : "false");
    printf(" }\n");

    /* Error codes. Winsock does not use errno for sockets at all. */
    printf(" :errno {\n");
#ifdef _WIN32
    K(":eagain", WSAEWOULDBLOCK);
    K(":ewouldblock", WSAEWOULDBLOCK);
    K(":einprogress", WSAEWOULDBLOCK); /* non-blocking connect on Winsock */
    K(":econnrefused", WSAECONNREFUSED);
    K(":econnreset", WSAECONNRESET);
    K(":eaddrinuse", WSAEADDRINUSE);
    K(":eaddrnotavail", WSAEADDRNOTAVAIL);
    K(":enetunreach", WSAENETUNREACH);
    K(":ehostunreach", WSAEHOSTUNREACH);
    K(":etimedout", WSAETIMEDOUT);
    K(":eintr", WSAEINTR);
    K(":eacces", WSAEACCES);
    KNIL(":epipe");
#else
    K(":eagain", EAGAIN);
    K(":ewouldblock", EWOULDBLOCK);
    K(":einprogress", EINPROGRESS);
    K(":econnrefused", ECONNREFUSED);
    K(":econnreset", ECONNRESET);
    K(":eaddrinuse", EADDRINUSE);
    K(":eaddrnotavail", EADDRNOTAVAIL);
    K(":enetunreach", ENETUNREACH);
    K(":ehostunreach", EHOSTUNREACH);
    K(":etimedout", ETIMEDOUT);
    K(":eintr", EINTR);
    K(":eacces", EACCES);
    K(":epipe", EPIPE);
#endif
    printf(" }\n");

    printf(" :gai {\n");
    K(":noname", EAI_NONAME);
    K(":again", EAI_AGAIN);
    K(":fail", EAI_FAIL);
    K(":family", EAI_FAMILY);
    K(":service", EAI_SERVICE);
    K(":memory", EAI_MEMORY);
#ifdef EAI_SYSTEM
    K(":system", EAI_SYSTEM);
#else
    KNIL(":system");
#endif
#ifdef EAI_ADDRFAMILY
    K(":addrfamily", EAI_ADDRFAMILY);
#else
    KNIL(":addrfamily");
#endif
    printf(" }}\n");
    return 0;
}
