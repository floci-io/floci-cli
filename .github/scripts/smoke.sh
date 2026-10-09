#!/usr/bin/env bash
# Runs a freshly built floci binary through the paths a native image can get wrong while the JVM
# build stays green: startup, every product tree's command wiring, a Jackson read, and the
# parameter-error path. No emulator or Docker daemon is needed.
#
#   .github/scripts/smoke.sh <binary> [expected-version]
set -euo pipefail

bin="$1"
expected="${2:-}"

"$bin" version

if [ -n "$expected" ]; then
  reported=$("$bin" version -o json | tr -d ' \r\n')
  case "$reported" in
    *"\"cli\":\"$expected\""*) ;;
    *) echo "::error::$bin reports $reported, expected cli version $expected"; exit 1 ;;
  esac
fi

for tree in aws gcp az oci; do
  "$bin" "$tree" --help > /dev/null
done

"$bin" config profile list -o json > /dev/null

# An unknown profile is a usage error: exit 2, not a crash.
set +e
"$bin" start --profile floci-smoke-no-such-profile > /dev/null 2>&1
code=$?
set -e
if [ "$code" -ne 2 ]; then
  echo "::error::'start --profile <missing>' exited $code, expected 2"
  exit 1
fi

echo "smoke test passed: $bin"
