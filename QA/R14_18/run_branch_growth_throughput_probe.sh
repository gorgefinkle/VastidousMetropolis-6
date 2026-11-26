#!/bin/sh
set -eu
ROOT="$(CDPATH= cd -- "$(dirname -- "$0")/../.." && pwd)"
OUT="$ROOT/target/qa-r14-18-throughput"
mkdir -p "$OUT"
javac -d "$OUT" \
  "$ROOT/src/main/java/ch/k42/metropolis/grid/urbanGrid/provider/BranchGrowthDrainThroughputPolicy.java" \
  "$ROOT/src/main/java/ch/k42/metropolis/grid/urbanGrid/provider/BranchGrowthDrainBatchPolicy.java" \
  "$ROOT/QA/R14_18/BranchGrowthDrainThroughputProbe.java" \
  "$ROOT/QA/R14_18/BranchGrowthDrainBatchProbe.java"
java -cp "$OUT" ch.k42.metropolis.grid.urbanGrid.provider.BranchGrowthDrainThroughputProbe
java -cp "$OUT" ch.k42.metropolis.grid.urbanGrid.provider.BranchGrowthDrainBatchProbe
javac -d "$OUT" \
  "$ROOT/src/main/java/ch/k42/metropolis/grid/urbanGrid/provider/PackedSupportHitBuffer.java" \
  "$ROOT/QA/R14_18/PackedSupportHitBufferProbe.java"
java -cp "$OUT" ch.k42.metropolis.grid.urbanGrid.provider.PackedSupportHitBufferProbe
