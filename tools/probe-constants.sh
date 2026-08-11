#!/bin/sh
# probe-constants.sh -- emit real socket ABI facts for a platform, as EDN.
#
#   tools/probe-constants.sh              # this host (native cc)
#   tools/probe-constants.sh windows      # Win64, via mingw
#   tools/probe-constants.sh --all        # every platform reachable from here
#
# Output goes to tools/probed/<os>-<arch>.edn, which the target-table tests diff
# against the committed tables. Regenerate and commit the diff whenever a table
# changes; an unexplained diff means the table is wrong, not the probe.
#
# Windows works from WSL: mingw cross-compiles AND the resulting .exe runs
# directly through WSL interop, so the Windows column is genuinely probed rather
# than recalled. macOS needs a macOS host and is not reachable from here.
set -eu

root="$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd)"
src="$root/tools/probe-constants.c"
out="$root/tools/probed"
mkdir -p "$out"
tmp="$(mktemp -d)"
trap 'rm -rf "$tmp"' EXIT

MINGW="${MINGW_GCC:-/mnt/c/Users/chuck/scoop/apps/gcc/current/bin/gcc.exe}"

probe_native() {
    cc="${CC:-cc}"
    command -v "$cc" >/dev/null 2>&1 || { echo "probe: no native $cc; skipping" >&2; return 1; }
    "$cc" -O0 -o "$tmp/probe" "$src"
    "$tmp/probe" > "$tmp/native.edn"
    # Name the file from what the probe itself reported, not from a guess.
    o=$(sed -n 's/^{:os :\([a-z0-9-]*\).*/\1/p' "$tmp/native.edn")
    a=$(sed -n 's/^ :arch :\([a-z0-9-]*\).*/\1/p' "$tmp/native.edn")
    cp "$tmp/native.edn" "$out/$o-$a.edn"
    echo "probe: wrote tools/probed/$o-$a.edn (native)"
}

probe_windows() {
    [ -x "$MINGW" ] || { echo "probe: no mingw gcc at $MINGW; skipping windows" >&2; return 1; }
    # gcc.exe is a WINDOWS binary. It cannot create a file at a WSL-only path
    # such as mktemp's /tmp/..., and it does not translate one either -- the
    # link step just fails with "cannot open output file". Build inside the
    # checkout instead, which is necessarily on a Windows-visible drive
    # whenever this cross-compile is possible at all, and translate both paths
    # explicitly rather than relying on interop's current-directory mapping.
    wtmp="$root/.probe-tmp"
    mkdir -p "$wtmp"
    exe="$wtmp/probe.exe"
    if command -v wslpath >/dev/null 2>&1; then
        wexe="$(wslpath -w "$exe")"
        wsrc="$(wslpath -w "$src")"
    else
        wexe="$exe"
        wsrc="$src"
    fi
    # -lws2_32 IS required: the probe opens no socket, but it takes the address
    # of WSAPoll to pin that function's exact declared signature, so the symbol
    # has to resolve. That turns the signature fact into compile-AND-link
    # evidence rather than a header reading alone.
    "$MINGW" -O0 -o "$wexe" "$wsrc" -lws2_32
    chmod +x "$exe"
    # Runs through WSL interop. Strip CR: the Windows binary emits CRLF.
    "$exe" | tr -d '\r' > "$tmp/win.edn"
    rm -rf "$wtmp"
    a=$(sed -n 's/^ :arch :\([a-z0-9-]*\).*/\1/p' "$tmp/win.edn")
    cp "$tmp/win.edn" "$out/windows-$a.edn"
    echo "probe: wrote tools/probed/windows-$a.edn (mingw, executed via WSL interop)"
}

case "${1:-native}" in
    native)  probe_native ;;
    windows) probe_windows ;;
    --all)
        probe_native  || true
        probe_windows || true
        echo "probe: macOS is NOT reachable from this host -- its table stays unverified."
        ;;
    *) echo "usage: $0 [native|windows|--all]" >&2; exit 2 ;;
esac
