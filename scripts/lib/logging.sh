#!/usr/bin/env bash
#
# Logging utilities for shell scripts.
#
# Usage:
#   readonly LOG_PREFIX="my-script"
#   source "$(git rev-parse --show-toplevel)/scripts/lib/logging.sh"
#
#   log_info "Starting build"
#   log_warn "Skipping optional step"
#   log_error "Build failed"
#   log_fatal "Cannot continue"  # prints and exits 1
#

: "${LOG_PREFIX:=script}"

readonly _BOLD_WHITE="\033[1;37m"
readonly _BOLD_YELLOW="\033[1;33m"
readonly _BOLD_RED="\033[1;31m"
readonly _RESET="\033[0m"

function log_info {
  echo -e "${_BOLD_WHITE}[$LOG_PREFIX] INFO: $*${_RESET}" >&2
}

function log_warn {
  echo -e "${_BOLD_YELLOW}[$LOG_PREFIX] WARN: $*${_RESET}" >&2
}

function log_error {
  echo -e "${_BOLD_RED}[$LOG_PREFIX] ERROR: $*${_RESET}" >&2
}

function log_fatal {
  echo -e "${_BOLD_RED}[$LOG_PREFIX] FATAL: $*${_RESET}" >&2
  exit 1
}
