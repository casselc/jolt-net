#!/bin/sh
set -eu

root=$(CDPATH= cd -- "$(dirname -- "$0")/../.." && pwd)

sh "$root/test/formal/check-errno-capture-ordering.sh"
sh "$root/test/formal/check-idempotent-close.sh"
sh "$root/test/formal/check-connect-ownership-completion.sh"
