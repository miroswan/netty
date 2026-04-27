#!/usr/bin/env bash
#
# Builds upstream Netty 5.0 in a temporary worktree and runs the benchmark
# suite against those jars. Results are stored under benchmarks/target/upstream/5.0/.
#
# Usage:
#   ./benchmarks/scripts/run-upstream-baseline.sh [branch]
#
set -euo pipefail

readonly LOG_PREFIX="upstream-baseline"
source "$(dirname "$0")/logging.sh"

readonly UPSTREAM_BRANCH="${1:-5.0}"
readonly UPSTREAM_REF="upstream/$UPSTREAM_BRANCH"
readonly WORKTREE_DIR=".upstream-bench-worktree"
readonly SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
readonly REPO_ROOT="$(cd "$SCRIPT_DIR/../.." && pwd)"
readonly RESULTS_DIR="$REPO_ROOT/benchmarks/target/upstream/$UPSTREAM_BRANCH"
readonly UPSTREAM_M2="$REPO_ROOT/$WORKTREE_DIR/.m2-upstream"

function fetch_upstream {
  log_info "Fetching upstream..."
  git -C "$REPO_ROOT" fetch upstream || {
    log_error "Failed to fetch upstream"
    return 1
  }
}

function create_worktree {
  if [ -d "$REPO_ROOT/$WORKTREE_DIR" ]; then
    git -C "$REPO_ROOT" worktree remove --force "$WORKTREE_DIR" 2>/dev/null || {
      rm -rf "$REPO_ROOT/$WORKTREE_DIR"
    }
  fi
  log_info "Creating worktree for $UPSTREAM_REF..."
  git -C "$REPO_ROOT" worktree add "$WORKTREE_DIR" "$UPSTREAM_REF" --detach || {
    log_error "Failed to create worktree"
    return 1
  }
}

function cleanup_worktree {
  log_info "Cleaning up worktree..."
  cd "$REPO_ROOT"
  git worktree remove --force "$WORKTREE_DIR" 2>/dev/null || true
}

function build_upstream {
  log_info "Building upstream Netty $UPSTREAM_BRANCH (isolated repo)..."
  cd "$REPO_ROOT/$WORKTREE_DIR"

  if [ -f "$REPO_ROOT/.local/env.sh" ]; then
    source "$REPO_ROOT/.local/env.sh"
  fi

  mvn clean install -DskipTests -Dskip.bundle.plugin=true \
    -Dmaven.repo.local="$UPSTREAM_M2" -Dcheckstyle.skip=true -q || {
    log_error "Upstream build failed"
    return 1
  }
}

function collect_upstream_classpath {
  local cp=""
  while IFS= read -r jar; do
    if [ -n "$cp" ]; then
      cp="$cp:$jar"
    else
      cp="$jar"
    fi
  done < <(find "$REPO_ROOT/$WORKTREE_DIR" -path '*/target/*.jar' -name 'netty5-*.jar' \
    ! -name '*-sources.jar' ! -name '*-tests.jar' ! -name '*-javadoc.jar')

  if [ -z "$cp" ]; then
    log_error "Failed to find upstream jars"
    return 1
  fi

  echo "$cp"
}

function rebuild_benchmark_classes {
  log_info "Rebuilding benchmarks module..."
  cd "$REPO_ROOT"
  mvn install -DskipTests -Dskip.bundle.plugin=true -rf :netty5-benchmarks -q || {
    log_error "Benchmark build failed"
    return 1
  }
}

function build_server_classpath {
  local upstream_cp="$1"
  local bench_classes="$REPO_ROOT/benchmarks/target/classes"
  local bench_deps

  cd "$REPO_ROOT"
  bench_deps=$(mvn dependency:build-classpath -pl benchmarks -q -DincludeScope=runtime \
    -Dmdep.outputFile=/dev/stdout 2>/dev/null) || bench_deps=""

  local server_cp="$bench_classes:$upstream_cp"
  if [ -n "$bench_deps" ]; then
    server_cp="$server_cp:$bench_deps"
  fi

  echo "$server_cp"
}

function run_benchmark {
  local server_cp="$1"

  log_info "Running benchmark suite against upstream $UPSTREAM_BRANCH..."

  cd "$REPO_ROOT"
  mvn exec:java -pl benchmarks -Pbenchmark-quick \
    -Dbenchmark.label="upstream-$UPSTREAM_BRANCH" \
    -Dbenchmark.serverClasspath="$server_cp" \
    2>&1 || {
    log_error "Benchmark run failed"
    return 1
  }
}

function move_results_to_upstream {
  local staging_label="upstream-$UPSTREAM_BRANCH"
  local staging_dir="$REPO_ROOT/benchmarks/results/staging/$staging_label"

  if [ ! -d "$staging_dir" ]; then
    log_error "No staging results found for label: $staging_label"
    return 1
  fi

  mkdir -p "$RESULTS_DIR" || {
    log_error "Failed to create results directory $RESULTS_DIR"
    return 1
  }
  mv "$staging_dir"/* "$RESULTS_DIR"/ || {
    log_error "Failed to move staging results"
    return 1
  }
  rmdir "$staging_dir" 2>/dev/null || true
  rmdir "$REPO_ROOT/benchmarks/results/staging" 2>/dev/null || true

  log_info "Results stored in $RESULTS_DIR"
}

function main {
  cd "$REPO_ROOT"

  fetch_upstream || return $?
  create_worktree || return $?
  trap cleanup_worktree EXIT

  build_upstream || return $?

  local upstream_cp
  upstream_cp=$(collect_upstream_classpath) || return $?

  cd "$REPO_ROOT"
  rebuild_benchmark_classes || return $?

  local server_cp
  server_cp=$(build_server_classpath "$upstream_cp") || return $?

  run_benchmark "$server_cp" || return $?
  move_results_to_upstream || return $?

  log_info "Upstream baseline complete."
}

main "$@"
exit $?
