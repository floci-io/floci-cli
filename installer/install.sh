#!/usr/bin/env sh
# Floci CLI installer — curl -fsSL https://floci.io/install.sh | sh
set -e

VERSION="${FLOCI_CLI_VERSION:-latest}"
INSTALL_DIR="${FLOCI_INSTALL_DIR:-/usr/local/bin}"
BINARY="floci"
REPO="floci-io/floci-cli"
SKIP_CHECKSUM="${FLOCI_SKIP_CHECKSUM:-0}"
DOWNLOAD_RETRIES="${FLOCI_DOWNLOAD_RETRIES:-4}"

# Download $1 to $2, retrying on transient failures. GitHub's CDN intermittently
# returns 5xx (e.g. 504) on release assets, and can hand back a 0-byte body, so
# we wrap curl in our own loop with backoff: an empty file is treated as a
# failure and retried. curl's own --retry handles mid-transfer hiccups; the
# outer loop covers cases it doesn't and works uniformly across curl versions.
download() {
    _url="$1"
    _out="$2"
    _attempt=1
    while :; do
        if curl -fsSL --retry 2 --retry-delay 1 "$_url" -o "$_out" && [ -s "$_out" ]; then
            return 0
        fi
        if [ "$_attempt" -ge "$DOWNLOAD_RETRIES" ]; then
            return 1
        fi
        _delay=$((_attempt * 2))
        echo "  download failed (attempt ${_attempt}/${DOWNLOAD_RETRIES}), retrying in ${_delay}s..." >&2
        sleep "$_delay"
        _attempt=$((_attempt + 1))
    done
}

detect_platform() {
    OS=$(uname -s | tr '[:upper:]' '[:lower:]')
    ARCH=$(uname -m)
    case "$ARCH" in
        x86_64)  ARCH="amd64" ;;
        aarch64|arm64) ARCH="arm64" ;;
        *) echo "Unsupported architecture: $ARCH" >&2; exit 1 ;;
    esac
    case "$OS" in
        linux)  PLATFORM="linux-${ARCH}" ;;
        darwin) PLATFORM="darwin-${ARCH}" ;;
        *) echo "Unsupported OS: $OS" >&2; exit 1 ;;
    esac
    echo "$PLATFORM"
}

fetch_latest_version() {
    _tmp=$(mktemp)
    if ! download "https://api.github.com/repos/${REPO}/releases/latest" "$_tmp"; then
        rm -f "$_tmp"
        echo "Error: could not reach GitHub to determine the latest version." >&2
        echo "Check your connection, or pin a version with FLOCI_CLI_VERSION=<tag>." >&2
        exit 1
    fi
    _version=$(grep '"tag_name"' "$_tmp" | sed 's/.*"tag_name": "\(.*\)".*/\1/')
    rm -f "$_tmp"
    if [ -z "$_version" ]; then
        echo "Error: could not parse the latest version from the GitHub API response." >&2
        exit 1
    fi
    echo "$_version"
}

main() {
    echo "Installing Floci CLI..."

    PLATFORM=$(detect_platform)
    if [ "$VERSION" = "latest" ]; then
        VERSION=$(fetch_latest_version)
    fi

    DOWNLOAD_URL="https://github.com/${REPO}/releases/download/${VERSION}/floci-${PLATFORM}"
    TMP=$(mktemp)

    echo "Downloading floci ${VERSION} for ${PLATFORM}..."
    if ! download "$DOWNLOAD_URL" "$TMP"; then
        rm -f "$TMP"
        echo "Error: failed to download the Floci binary from:" >&2
        echo "  $DOWNLOAD_URL" >&2
        echo "GitHub may be returning a temporary error (e.g. HTTP 504). Please retry shortly." >&2
        exit 1
    fi
    chmod +x "$TMP"

    # Verify checksum if sha256sum is available
    if [ "$SKIP_CHECKSUM" != "1" ] && command -v sha256sum >/dev/null 2>&1; then
        SUMS_URL="https://github.com/${REPO}/releases/download/${VERSION}/sha256sums.txt"
        SUMS_TMP=$(mktemp)
        # Fetch to a file so a failed download is caught here, not silently
        # swallowed inside a pipeline (which would yield an empty expected sum
        # and a misleading "checksum mismatch").
        if ! download "$SUMS_URL" "$SUMS_TMP"; then
            rm -f "$TMP" "$SUMS_TMP"
            echo "Error: failed to download checksums from:" >&2
            echo "  $SUMS_URL" >&2
            echo "Cannot verify the binary's integrity. Please retry, or set" >&2
            echo "FLOCI_SKIP_CHECKSUM=1 to install without verification." >&2
            exit 1
        fi
        EXPECTED=$(grep "floci-${PLATFORM}\$" "$SUMS_TMP" | awk '{print $1}')
        rm -f "$SUMS_TMP"
        if [ -z "$EXPECTED" ]; then
            rm -f "$TMP"
            echo "Error: no checksum entry for floci-${PLATFORM} in sha256sums.txt." >&2
            echo "Please report this at https://github.com/${REPO}/issues." >&2
            exit 1
        fi
        ACTUAL=$(sha256sum "$TMP" | awk '{print $1}')
        if [ "$EXPECTED" != "$ACTUAL" ]; then
            rm -f "$TMP"
            echo "Checksum mismatch for floci-${PLATFORM} — the download may be corrupted." >&2
            echo "  expected: $EXPECTED" >&2
            echo "  actual:   $ACTUAL" >&2
            echo "Please retry the install." >&2
            exit 1
        fi
        echo "Checksum verified."
    fi

    # Install
    if [ -w "$INSTALL_DIR" ]; then
        mv "$TMP" "${INSTALL_DIR}/${BINARY}"
    else
        echo "Installing to ${INSTALL_DIR} requires sudo..."
        sudo mv "$TMP" "${INSTALL_DIR}/${BINARY}"
    fi

    echo ""
    echo "Floci CLI ${VERSION} installed to ${INSTALL_DIR}/${BINARY}"
    echo ""
    echo "Quick start:"
    echo "  floci start            # launch Floci"
    echo "  floci doctor           # check your environment"
    echo "  export AWS_ENDPOINT_URL=http://localhost:4566"
    echo "  aws s3 mb s3://my-bucket  # use the AWS CLI against Floci"
}

main "$@"
