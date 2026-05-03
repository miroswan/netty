#!/usr/bin/env bash
#
# Installs jextract for Java 25 to ~/.jextract.
#
# Usage:
#   ./scripts/install-jextract.sh
#
set -euo pipefail

readonly LOG_PREFIX="install-jextract"
source "$(git rev-parse --show-toplevel)/scripts/lib/logging.sh"

readonly INSTALL_DIR="$HOME/.jextract"
readonly BASE_URL="https://download.java.net/java/early_access/jextract/25/2"

function resolve_download_url {
  local os arch

  case "$(uname -s)" in
    (Linux)                os="linux" ;;
    (Darwin)               os="macos" ;;
    (MINGW*|MSYS*|CYGWIN*) os="windows" ;;
    (*)                    log_fatal "Unsupported OS: $(uname -s)" ;;
  esac

  case "$(uname -m)" in
    (aarch64|arm64) arch="aarch64" ;;
    (x86_64|amd64)  arch="x64" ;;
    (*)             log_fatal "Unsupported architecture: $(uname -m)" ;;
  esac

  if [ "$os" = "windows" ] && [ "$arch" = "aarch64" ]; then
    log_fatal "No jextract build available for Windows aarch64"
  fi

  echo "$BASE_URL/openjdk-25-jextract+2-4_${os}-${arch}_bin.tar.gz"
}

function download_jextract {
  local url="$1"
  local tar_file="/tmp/jextract.tar.gz"

  log_info "Downloading jextract from $url"
  curl -fL "$url" -o "$tar_file" || {
    log_fatal "Failed to download jextract"
  }

  log_info "Extracting to $INSTALL_DIR"
  mkdir -p "$INSTALL_DIR"
  tar -xzf "$tar_file" -C "$INSTALL_DIR" --strip-components=1
  rm "$tar_file"

  if [ "$(uname -s)" = "Darwin" ]; then
    log_info "Removing macOS quarantine attribute..."
    sudo xattr -r -d com.apple.quarantine "$INSTALL_DIR" 2>/dev/null || {
      log_info "No quarantine attribute found (this is fine)"
    }
  fi
}

function verify_installation {
  local jextract_bin="$INSTALL_DIR/bin/jextract"

  if [ ! -f "$jextract_bin" ]; then
    log_fatal "jextract binary not found at $jextract_bin"
  fi

  chmod +x "$jextract_bin"

  log_info "Verifying installation..."
  "$jextract_bin" --version || {
    log_fatal "jextract verification failed"
  }
}

function main {
  if command -v jextract &> /dev/null; then
    log_info "jextract already installed: $(jextract --version 2>&1)"
    exit 0
  fi

  if [ -x "$INSTALL_DIR/bin/jextract" ]; then
    log_info "jextract already installed at $INSTALL_DIR/bin/jextract"
    "$INSTALL_DIR/bin/jextract" --version
    exit 0
  fi

  local url
  url=$(resolve_download_url)

  download_jextract "$url"
  verify_installation

  log_info "Installation complete. Add to PATH:"
  log_info "  export PATH=\"\$HOME/.jextract/bin:\$PATH\""
}

main "$@"
exit $?
