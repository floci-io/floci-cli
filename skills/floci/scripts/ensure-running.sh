#!/usr/bin/env bash
# Ensure a Floci emulator is running and print its environment for eval.
#
# Usage:
#   eval "$(<skill dir>/scripts/ensure-running.sh <aws|gcp|az|oci> [profile])"
#
# Starts the emulator only if it is not already healthy, waits for readiness,
# then prints the `floci <tree> env` exports on stdout. Progress goes to stderr.
# Env: FLOCI_WAIT_TIMEOUT (default 120s).
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

if grep -Eq '"state"[[:space:]]*:[[:space:]]*"running"' <<<"$status_json"; then
  echo "floci $tree${profile:+ ($profile)} container already running" >&2
else
  # start removes a stopped container with the same name before recreating it, so its
  # state survives only with --persist (or a profile with persistDir).
  echo "starting floci $tree${profile:+ ($profile)}..." >&2
  floci "$tree" start --detach -q ${args[@]+"${args[@]}"} >&2
fi

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
