#!/usr/bin/env bash
set -euo pipefail

readonly LOG_PREFIX="install-fortio"
source "$(dirname "$0")/logging.sh"

if command -v fortio &> /dev/null; then
  log_info "Fortio already installed: $(fortio version)"
  exit 0
fi

if [[ "$(uname)" == "Darwin" ]]; then
  if command -v brew &> /dev/null; then
    brew install fortio
  else
    log_fatal "Homebrew not found. Install via: go install fortio.org/fortio@latest"
  fi
elif [[ "$(uname)" == "Linux" ]]; then
  if command -v go &> /dev/null; then
    go install fortio.org/fortio@latest
  else
    log_fatal "Go not found. Install Go first, then: go install fortio.org/fortio@latest"
  fi
else
  log_fatal "Unsupported platform. Install manually: go install fortio.org/fortio@latest"
fi

log_info "Fortio installed: $(fortio version)"
