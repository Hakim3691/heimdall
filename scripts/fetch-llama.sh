#!/usr/bin/env bash
# Fetches llama.cpp at the commit pinned in native/llama.cpp.pin into native/third_party/llama.cpp.
#
# The submodule-style checkout is deliberately *not* committed: llama.cpp is ~1 GB with history and
# the workspace checkpoint is size-limited. Pinning by commit hash keeps builds reproducible.
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
PIN_FILE="$ROOT/native/llama.cpp.pin"
DEST_DIR="${1:-$ROOT/native/third_party/llama.cpp}"

if [ ! -f "$PIN_FILE" ]; then
  echo "error: $PIN_FILE is missing (it must contain the pinned llama.cpp commit)" >&2
  exit 1
fi

PIN="$(tr -d '[:space:]' < "$PIN_FILE")"
if [ -z "$PIN" ]; then
  echo "error: $PIN_FILE is empty" >&2
  exit 1
fi

if [ -d "$DEST_DIR/.git" ] && [ "$(git -C "$DEST_DIR" rev-parse HEAD)" = "$PIN" ]; then
  echo "llama.cpp already at $PIN"
  exit 0
fi

echo "Fetching llama.cpp @ $PIN into $DEST_DIR"
rm -rf "$DEST_DIR"
mkdir -p "$(dirname "$DEST_DIR")"
# Blobless partial clone keeps the download small while still allowing an exact commit checkout.
git clone --filter=blob:none --no-checkout https://github.com/ggml-org/llama.cpp "$DEST_DIR"
git -C "$DEST_DIR" fetch --depth 1 origin "$PIN"
git -C "$DEST_DIR" checkout --detach "$PIN"

echo "llama.cpp ready at $(git -C "$DEST_DIR" rev-parse HEAD)"
