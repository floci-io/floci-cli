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

work=$(mktemp -d)
profile="floci-smoke-$$"
cleanup() {
  "$bin" config profile delete "$profile" > /dev/null 2>&1 || true
  rm -rf "$work"
}
trap cleanup EXIT

expect_exit() {
  local expected_code="$1"
  shift
  local actual_code=0
  "$@" > "$work/out" 2> "$work/err" || actual_code=$?
  if [ "$actual_code" -ne "$expected_code" ]; then
    cat "$work/out" "$work/err"
    echo "::error::command exited $actual_code, expected $expected_code: $*"
    exit 1
  fi
}

# A bare grep or test would stop the script under set -e with nothing in the CI log saying why.
require_output() {
  local pattern="$1" file="$2" what="$3"
  if ! grep -q "$pattern" "$file"; then
    cat "$file"
    echo "::error::$what: expected output matching '$pattern'"
    exit 1
  fi
}

# The shorthand and the plain-marks flag parse in the shipped binary. What --no-emoji does cannot
# be seen here: piped output is already plain. ErgonomicsTest covers that.
reported=$("$bin" version --json --no-emoji | tr -d ' \r\n')
case "$reported" in
  *'"cli":'*) ;;
  *) echo "::error::version --json did not return a version object: $reported"; exit 1 ;;
esac

"$bin" completion bash > "$work/completion"
[ -s "$work/completion" ] || { echo "::error::completion bash printed nothing"; exit 1; }
expect_exit 2 "$bin" completion fish
require_output 'Use bash or zsh' "$work/err" "completion fish"

# SnakeYAML aliases/merge keys must also work in a native image, not just the JVM.
cat > "$work/compose.yml" <<'YAML'
x-emulator: &emulator
  image: floci/floci:latest
  ports: ["14566:4566"]
  volumes: ["/var/run/docker.sock:/var/run/docker.sock"]
services:
  floci:
    <<: *emulator
YAML
validated=$("$bin" aws config validate --file "$work/compose.yml" --json | tr -d ' \r\n')
case "$validated" in
  *'"valid":true'*'"errors":[]'*) ;;
  *) echo "::error::native Compose validation failed: $validated"; exit 1 ;;
esac

# A rejected snapshot name and a declined reset must finish without contacting Docker/server.
expect_exit 2 "$bin" snapshot save bad/name
expect_exit 2 "$bin" reset --endpoint http://127.0.0.1:1 < /dev/null

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
"$bin" config profile create "$profile" --container floci-smoke --port 14566 > /dev/null
shown=$("$bin" config show --profile "$profile" -o json | tr -d ' \r\n')
case "$shown" in
  *'"container":"floci-smoke"'*'"port":14566'*) ;;
  *) echo "::error::profile $profile did not round-trip: $shown"; exit 1 ;;
esac
"$bin" config profile list -o json | grep -q "$profile" \
  || { echo "::error::profile $profile is missing from 'config profile list'"; exit 1; }

# Edit through a real child process, then read its new value via Jackson in the binary. Not on
# Windows: launching 'sh' with a Git Bash path from a native exe has not been proven there, and
# this script runs on Windows only while a release is being cut, so a false failure would stop it.
case "$(uname -s)" in
  MINGW*|MSYS*|CYGWIN*) echo "skipping profile edit on Windows" ;;
  *)
    cat > "$work/editor.sh" <<'SH'
printf 'container: floci-smoke\nport: 14566\nnamespace: smoke-native\n' > "$1"
SH
    edited=$(VISUAL="sh \"$work/editor.sh\"" EDITOR="sh \"$work/editor.sh\"" \
      "$bin" config profile edit "$profile" --json | tr -d ' \r\n')
    case "$edited" in
      *'"action":"edited"'*) ;;
      *) echo "::error::profile edit did not report success: $edited"; exit 1 ;;
    esac
    "$bin" config profile show "$profile" --json > "$work/profile.json"
    require_output 'smoke-native' "$work/profile.json" "profile show after edit"
    ;;
esac
"$bin" config show --profile "$profile" -o yaml > "$work/config.yaml"
require_output 'container: "\{0,1\}floci-smoke' "$work/config.yaml" "config show -o yaml"

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
