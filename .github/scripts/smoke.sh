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

# A profile round trip: Jackson writes it and reads it back. Listing alone proves little on a
# fresh runner, where there is nothing to read. The name is unique and the profile is deleted.
profile="floci-smoke-$$"
trap '"$bin" config profile delete "$profile" > /dev/null 2>&1 || true' EXIT
"$bin" config profile create "$profile" --container floci-smoke --port 14566 > /dev/null
shown=$("$bin" config show --profile "$profile" -o json | tr -d ' \r\n')
case "$shown" in
  *'"container":"floci-smoke"'*'"port":14566'*) ;;
  *) echo "::error::profile $profile did not round-trip: $shown"; exit 1 ;;
esac
"$bin" config profile list -o json | grep -q "$profile" \
  || { echo "::error::profile $profile is missing from 'config profile list'"; exit 1; }

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
