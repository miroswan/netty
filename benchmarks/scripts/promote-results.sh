#!/usr/bin/env bash
#
# Promotes staging benchmark results to a SHA-keyed directory.
# Requires a clean working tree so the SHA is trustworthy.
#
# Usage:
#   ./benchmarks/scripts/promote-results.sh
#
set -euo pipefail

readonly LOG_PREFIX="promote"
source "$(dirname "$0")/logging.sh"

readonly RESULTS_DIR="benchmarks/results"
readonly STAGING_DIR="$RESULTS_DIR/staging"

function check_staging_exists {
  if [ ! -d "$STAGING_DIR" ]; then
    log_fatal "No staging results found at $STAGING_DIR"
  fi
}

function check_clean_tree {
  if [ -n "$(git status --porcelain)" ]; then
    log_fatal "Working tree is dirty. Commit or stash your changes before promoting."
  fi
}

function promote {
  local sha
  sha=$(git rev-parse --short=7 HEAD) || {
    log_error "Failed to resolve HEAD"
    return 1
  }

  local dest_dir="$RESULTS_DIR/$sha"
  log_info "Promoting staging results to $dest_dir"

  for label_dir in "$STAGING_DIR"/*/; do
    [ -d "$label_dir" ] || continue
    local label
    label=$(basename "$label_dir")
    local target="$dest_dir/$label"
    mkdir -p "$target" || {
      log_error "Failed to create directory $target"
      return 1
    }
    mv "$label_dir"* "$target"/ || {
      log_error "Failed to move results for label $label"
      return 1
    }
    rmdir "$label_dir" || {
      log_error "Failed to remove staging label directory $label_dir"
      return 1
    }
    log_info "  $label -> $dest_dir/$label"
  done

  rmdir "$STAGING_DIR" 2>/dev/null || true
  log_info "Done. Results promoted to $sha"
}

function main {
  check_staging_exists || return $?
  check_clean_tree || return $?
  promote || return $?
}

main "$@"
exit $?
