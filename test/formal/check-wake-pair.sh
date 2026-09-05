#!/bin/sh
set -eu

root=$(CDPATH= cd -- "$(dirname -- "$0")/../.." && pwd)

cd "$root"
bb --config "$root/bb.edn" \
  -m jolt.aspect-packs.formal-antivacuity \
  "$root/docs/proofs/models/wake-pair.contract.edn"
