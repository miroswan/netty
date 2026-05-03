#!/usr/bin/env bash
#
# Generates FFM bindings from vendored C headers using jextract.
#
# Usage:
#   ./scripts/jextract-tool.sh <config-file>
#
# The config file is a bash script that sets:
#   HEADER_DIR      — path to vendored headers (relative to config file)
#   OUTPUT_DIR      — java source output (relative to config file)
#   TARGET_PACKAGE  — java package for generated code
#   BINDINGS        — array of binding declarations
#
# Opinionated defaults (overridable in config):
#   INCLUDE_ERRNO=true         — generate errno binding automatically
#   ERRNO_FUNCTION             — platform-detected (__error on macOS, __errno_location on Linux)
#   ERRNO_CONSTANTS            — common POSIX errno values
#
# Each BINDINGS entry is: "<header> <class-name> [jextract flags...]"
# Flags are passed directly to jextract (--include-function, --include-constant, etc.)
#
# Example config:
#   HEADER_DIR="headers"
#   OUTPUT_DIR="src/main/java"
#   TARGET_PACKAGE="io.netty.channel.unix.generated"
#
#   BINDINGS=(
#     "socket.h BsdSocket --include-function socket --include-function bind --include-constant AF_INET"
#     "unistd.h Posix --include-function read --include-function write --include-function close"
#   )
#
set -euo pipefail

readonly LOG_PREFIX="jextract-tool"
source "$(git rev-parse --show-toplevel)/scripts/lib/logging.sh"

# --- Platform defaults ---

case "$(uname -s)" in
  (Darwin)
    DEFAULT_ERRNO_FUNCTION="__error"
    ;;
  (Linux)
    DEFAULT_ERRNO_FUNCTION="__errno_location"
    ;;
  (*)
    DEFAULT_ERRNO_FUNCTION=""
    ;;
esac

DEFAULT_ERRNO_CONSTANTS=(
  EAGAIN EINTR EWOULDBLOCK EINVAL EINPROGRESS EPIPE
  ECONNABORTED ECONNRESET ENOTCONN ESHUTDOWN ENOENT
  EBADF ECONNREFUSED
)

# --- Resolve jextract binary ---

: "${JEXTRACT:=""}"

# Locates the jextract binary. Checks, in order: JEXTRACT env var, PATH,
# ~/.jextract/bin/jextract. Sets the JEXTRACT variable on success.
# Returns 1 if jextract cannot be found.
function _resolve_jextract {
  if [ -n "$JEXTRACT" ]; then
    if [ ! -x "$JEXTRACT" ]; then
      log_error "JEXTRACT=$JEXTRACT is not executable"
      return 1
    fi
    return 0
  fi

  if command -v jextract &> /dev/null; then
    JEXTRACT="$(command -v jextract)"
    return 0
  fi

  local candidate="$HOME/.jextract/bin/jextract"
  if [ -x "$candidate" ]; then
    JEXTRACT="$candidate"
    return 0
  fi

  log_error "jextract not found. Install via: ./scripts/install-jextract.sh"
  return 1
}

# --- Config loading ---

# Sources the config file and resolves relative paths against the config file's
# directory. Initializes all config variables with defaults before sourcing so
# the config only needs to set what it wants to override.
#
# Args: <config-file>
# Sets: HEADER_DIR, OUTPUT_DIR, TARGET_PACKAGE, INCLUDE_ERRNO,
#       ERRNO_FUNCTION, ERRNO_CONSTANTS, BINDINGS
# Returns 1 if the config file does not exist.
function _load_config {
  local config_file="$1"

  if [ ! -f "$config_file" ]; then
    log_error "Config file not found: $config_file"
    return 1
  fi

  local config_dir
  config_dir="$(cd "$(dirname "$config_file")" && pwd)"

  HEADER_DIR=""
  OUTPUT_DIR=""
  TARGET_PACKAGE=""
  INCLUDE_ERRNO=true
  ERRNO_FUNCTION="$DEFAULT_ERRNO_FUNCTION"
  ERRNO_CONSTANTS=("${DEFAULT_ERRNO_CONSTANTS[@]}")
  BINDINGS=()

  source "$config_file"

  if [[ "$HEADER_DIR" != /* ]]; then
    HEADER_DIR="$config_dir/$HEADER_DIR"
  fi
  if [[ "$OUTPUT_DIR" != /* ]]; then
    OUTPUT_DIR="$config_dir/$OUTPUT_DIR"
  fi
}

# Validates that required config variables are set and that the header
# directory exists.
# Returns 1 on validation failure.
function _validate_config {
  if [ -z "$HEADER_DIR" ]; then
    log_error "Config must set HEADER_DIR"
    return 1
  fi
  if [ -z "$OUTPUT_DIR" ]; then
    log_error "Config must set OUTPUT_DIR"
    return 1
  fi
  if [ -z "$TARGET_PACKAGE" ]; then
    log_error "Config must set TARGET_PACKAGE"
    return 1
  fi
  if [ ! -d "$HEADER_DIR" ]; then
    log_error "Header directory not found: $HEADER_DIR"
    return 1
  fi
}

# --- Binding generation ---

# Runs jextract for a single header file. The first two arguments are the
# header filename (relative to HEADER_DIR) and the generated class name.
# All remaining arguments are forwarded directly to jextract as flags.
#
# Args: <header> <class-name> [jextract flags...]
# Returns 1 if the header file does not exist or jextract fails.
function _run_binding {
  local header="$1"
  local class_name="$2"
  shift 2

  local header_path="$HEADER_DIR/$header"
  if [ ! -f "$header_path" ]; then
    log_error "Header not found: $header_path"
    return 1
  fi

  log_info "$class_name <- $header"
  "$JEXTRACT" \
    --output "$OUTPUT_DIR" \
    --target-package "$TARGET_PACKAGE" \
    --header-class-name "$class_name" \
    "$@" \
    "$header_path" || {
    log_error "jextract failed for $header"
    return 1
  }
}

# Generates the errno binding using platform-detected defaults unless the
# config has set INCLUDE_ERRNO=false. Skips with a warning if errno.h is
# not found in HEADER_DIR.
# Returns 1 if jextract fails.
function _generate_errno_binding {
  if [ "$INCLUDE_ERRNO" != true ]; then
    return 0
  fi

  if [ -z "$ERRNO_FUNCTION" ]; then
    log_error "No errno function known for this platform. Set ERRNO_FUNCTION in config or INCLUDE_ERRNO=false"
    return 1
  fi

  if [ ! -f "$HEADER_DIR/errno.h" ]; then
    log_warn "errno.h not found in $HEADER_DIR — skipping errno binding"
    return 0
  fi

  local -a errno_args=()
  errno_args+=(--include-function "$ERRNO_FUNCTION")
  for constant in "${ERRNO_CONSTANTS[@]}"; do
    errno_args+=(--include-constant "$constant")
  done

  _run_binding errno.h Errno "${errno_args[@]}" || return $?
}

# Iterates the BINDINGS array and runs jextract for each entry. Each entry
# is word-split into: <header> <class-name> [jextract flags...].
# Returns 1 if any binding fails.
function _generate_bindings {
  for binding in "${BINDINGS[@]}"; do
    # shellcheck disable=SC2086
    _run_binding $binding || return $?
  done
}

# --- Main ---

function main {
  if [ $# -lt 1 ]; then
    log_error "Usage: jextract-tool.sh <config-file>"
    return 1
  fi

  _load_config "$1" || return $?
  _validate_config || return $?
  _resolve_jextract || return $?

  mkdir -p "$OUTPUT_DIR" || {
    log_error "Failed to create output directory: $OUTPUT_DIR"
    return 1
  }

  _generate_errno_binding || return $?
  _generate_bindings || return $?

  log_info "Done. Generated bindings in $OUTPUT_DIR/$(echo "$TARGET_PACKAGE" | tr '.' '/')"
}

main "$@"
exit $?
