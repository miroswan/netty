#!/usr/bin/env bash
set -euo pipefail

if command -v fortio &> /dev/null; then
  echo "Fortio already installed: $(fortio version)"
  exit 0
fi

if [[ "$(uname)" == "Darwin" ]]; then
  if command -v brew &> /dev/null; then
    brew install fortio
  else
    echo "Homebrew not found. Install via: go install fortio.org/fortio@latest"
    exit 1
  fi
elif [[ "$(uname)" == "Linux" ]]; then
  if command -v go &> /dev/null; then
    go install fortio.org/fortio@latest
  else
    echo "Go not found. Install Go first, then: go install fortio.org/fortio@latest"
    exit 1
  fi
else
  echo "Unsupported platform. Install manually: go install fortio.org/fortio@latest"
  exit 1
fi

echo "Fortio installed: $(fortio version)"
