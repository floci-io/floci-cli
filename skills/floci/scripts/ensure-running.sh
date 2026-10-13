#!/usr/bin/env bash
# Ensure a Floci emulator is running and print its environment for eval.
#
# Usage:
#   exports="$(<skill dir>/scripts/ensure-running.sh <aws|gcp|az|oci> [profile])" && eval "$exports"
#
# Not eval "$(...)" in one step: that succeeds even when this script fails.
#
# Starts the emulator only if no container exists, waits for readiness, then prints
# the `floci <tree> env` exports on stdout. Progress goes to stderr.
# Exit 3: the container exists but is not running. `floci <tree> start` would remove it and
# create a new one, so this script leaves it alone unless FLOCI_REPLACE_STOPPED=1.
# Env: FLOCI_WAIT_TIMEOUT (default 120s), FLOCI_REPLACE_STOPPED.
set -euo pipefail

tree="${1:-}"
profile="${2:-}"

case "$tree" in
  aws|gcp|az|oci) ;;
  *) echo "usage: $0 <aws|gcp|az|oci> [profile]" >&2; exit 64 ;;
esac

if ! command -v floci >/dev/null 2>&1; then
  echo "floci CLI not found. Ask the user, then run: $(dirname "$0")/install-floci.sh" >&2
  exit 127
fi

args=()
if [[ -n "$profile" ]]; then
  args=(--profile "$profile")
fi

status_json="$(floci "$tree" status -o json ${args[@]+"${args[@]}"} 2>/dev/null || true)"

state="$(sed -nE 's/.*"state"[[:space:]]*:[[:space:]]*"([^"]*)".*/\1/p' <<<"$status_json" | head -n 1)"

start() {
  echo "starting floci $tree${profile:+ ($profile)}..." >&2
  floci "$tree" start --detach -q ${args[@]+"${args[@]}"} >&2
}

case "$state" in
  running)
    echo "floci $tree${profile:+ ($profile)} container already running" >&2
    ;;
  "not found")
    start
    ;;
  "")
    echo "could not read the state of floci $tree${profile:+ ($profile)}; run 'floci $tree doctor'" >&2
    exit 1
    ;;
  *)
    # start removes a container of the same name before creating a new one. Without a profile
    # the new one gets default settings, so a custom port, image or persist mount is lost.
    if [[ "${FLOCI_REPLACE_STOPPED:-}" != "1" ]]; then
      echo "floci $tree${profile:+ ($profile)} container exists but is not running (state: $state)." >&2
      echo "Starting it removes that container and creates a new one. Ask the user, then re-run with FLOCI_REPLACE_STOPPED=1." >&2
      exit 3
    fi
    start
    ;;
esac

if ! floci "$tree" wait --timeout "${FLOCI_WAIT_TIMEOUT:-120s}" -q ${args[@]+"${args[@]}"} >&2; then
  echo "floci $tree did not become ready; recent logs:" >&2
  floci "$tree" logs --tail 50 ${args[@]+"${args[@]}"} >&2 || true
  exit 1
fi

# -o text: a profile may set 'output: json', which eval cannot use.
env_out="$(floci "$tree" env -o text ${args[@]+"${args[@]}"})"

# Safety net: warn loudly if the endpoint is not local.
if ! grep -Eq '(localhost|127\.0\.0\.1|localhost\.floci\.io)' <<<"$env_out"; then
  echo "WARNING: floci $tree env does not point at localhost. Verify the endpoint before running mutating commands." >&2
fi

if [[ "$tree" == "az" ]] && ! grep -Eq 'AZURE_CONFIG_DIR|REQUESTS_CA_BUNDLE' <<<"$env_out"; then
  echo "note: run 'floci az setup${profile:+ --profile $profile}' before az control-plane commands" >&2
fi
if [[ "$tree" == "oci" ]] && ! grep -q 'OCI_CLI_PROFILE' <<<"$env_out"; then
  echo "note: run 'floci oci setup' before using the oci CLI" >&2
fi

printf '%s\n' "$env_out"
