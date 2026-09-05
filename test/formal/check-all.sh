#!/bin/sh
set -eu

root=$(CDPATH= cd -- "$(dirname -- "$0")/../.." && pwd)

sh "$root/test/formal/check-errno-capture-ordering.sh"
sh "$root/test/formal/check-idempotent-close.sh"
sh "$root/test/formal/check-connect-ownership-completion.sh"
sh "$root/test/formal/check-readiness-token.sh"
sh "$root/test/formal/check-short-lease.sh"
sh "$root/test/formal/check-nonblocking-transition.sh"
sh "$root/test/formal/check-accept-terminal-close.sh"
sh "$root/test/formal/check-wake-pair.sh"
sh "$root/test/formal/check-wake-epoch.sh"
