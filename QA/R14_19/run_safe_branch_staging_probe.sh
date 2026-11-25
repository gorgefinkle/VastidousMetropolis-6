#!/bin/sh
set -eu
SOURCE="src/main/java/ch/k42/metropolis/grid/urbanGrid/provider/BranchGrowthDrain.java"
test -f "$SOURCE"
if grep -Eq '\.loadChunk[[:space:]]*\(|\.getChunkAt[[:space:]]*\(' "$SOURCE"; then
  echo "FAIL: branch drain must not synchronously load or generate support chunks" >&2
  exit 1
fi
grep -q 'getChunkAtAsync(cx, cz, false, false)' "$SOURCE"
grep -q 'w.isChunkLoaded(cx, cz)' "$SOURCE"
grep -q 'retainChunk(job, w, value)' "$SOURCE"
echo "PASS: drain asynchronously requests existing chunks only and tickets them after load"
