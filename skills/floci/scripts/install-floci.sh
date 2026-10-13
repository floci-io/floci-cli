#!/usr/bin/env bash
# Install floci-cli if it is missing. Linux and macOS.
# Windows: iwr https://floci.io/install.ps1 | iex  (installs to %LOCALAPPDATA%\floci\bin, no admin)
#
# Usage: install-floci.sh [--method brew|script] [--version <tag>]   (tags have no 'v': 0.4.0)
#
# Defaults: Homebrew when brew is on PATH, otherwise the official install script into
# ~/.local/bin, which needs no sudo (the script's own default, /usr/local/bin, prompts for
# sudo, which hangs a non-interactive agent). Override the dir with FLOCI_INSTALL_DIR.
set -euo pipefail

method=""
version="${FLOCI_CLI_VERSION:-latest}"
while [[ $# -gt 0 ]]; do
  case "$1" in
    --method)  method="${2:-}"; shift 2 ;;
    --version) version="${2:-}"; shift 2 ;;
    -h|--help) sed -n '2,10p' "$0"; exit 0 ;;
    *) echo "unknown argument: $1" >&2; exit 64 ;;
  esac
done

if command -v floci >/dev/null 2>&1; then
  echo "floci already installed: $(command -v floci)"
  floci version 2>/dev/null | head -1 || true
  if [[ "$version" != "latest" ]]; then
    echo "NOTE: --version $version was not applied. Use 'floci update --version $version' (or brew) to change the installed version." >&2
  fi
  exit 0
fi

case "$(uname -s)" in
  Linux|Darwin) ;;
  *) echo "Unsupported OS for this script. On Windows run: iwr https://floci.io/install.ps1 | iex" >&2; exit 1 ;;
esac

if [[ -z "$method" ]]; then
  if command -v brew >/dev/null 2>&1 && [[ "$version" == "latest" ]]; then
    method="brew"
  else
    method="script"
  fi
fi

case "$method" in
  brew)
    command -v brew >/dev/null 2>&1 || { echo "brew not found; use --method script" >&2; exit 1; }
    if [[ "$version" != "latest" ]]; then
      echo "NOTE: Homebrew installs the current release; --version $version is ignored. Use --method script to pin." >&2
    fi
    brew install floci-io/floci/floci
    ;;
  script)
    command -v curl >/dev/null 2>&1 || { echo "curl is required" >&2; exit 1; }
    dir="${FLOCI_INSTALL_DIR:-$HOME/.local/bin}"
    mkdir -p "$dir"
    tmp="$(mktemp)"
    trap 'rm -f "$tmp"' EXIT
    curl -fsSL https://floci.io/install.sh -o "$tmp"
    FLOCI_INSTALL_DIR="$dir" FLOCI_CLI_VERSION="$version" sh "$tmp"
    case ":$PATH:" in
      *":$dir:"*) ;;
      *)
        echo
        echo "NOTE: $dir is not on PATH. For this shell: export PATH=\"$dir:\$PATH\""
        echo "To make it permanent, add that line to ~/.bashrc or ~/.zshrc (ask the user first)."
        export PATH="$dir:$PATH"
        ;;
    esac
    ;;
  *)
    echo "unknown method: $method (use brew or script)" >&2; exit 64 ;;
esac

if ! command -v floci >/dev/null 2>&1; then
  echo "install finished but floci is not on PATH" >&2
  exit 1
fi
floci version 2>/dev/null | head -1 || true

# The emulators run as containers; warn early if there is no Docker-compatible runtime.
if ! command -v docker >/dev/null 2>&1; then
  echo "WARNING: docker CLI not found. Floci needs Docker (or Podman via DOCKER_HOST) to run the emulators." >&2
elif ! docker info >/dev/null 2>&1; then
  echo "WARNING: docker is installed but the daemon is not reachable. Start Docker, or set DOCKER_HOST for Podman." >&2
fi
