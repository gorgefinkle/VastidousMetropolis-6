#!/bin/sh
set -eu
ROOT=$(CDPATH= cd -- "$(dirname -- "$0")/../.." && pwd)
OUT="$ROOT/target/qa-smoothstone-seam"
mkdir -p "$OUT"
javac -cp "$ROOT/target/classes" -d "$OUT" \
  "$ROOT/QA/R14_22/SmoothstoneSeamProbe.java" \
  "$ROOT/QA/R14_22/BuriedFallbackCapProbe.java"
java -Xmx128m -cp "$OUT:$ROOT/target/classes" ch.k42.metropolis.generator.SmoothstoneSeamProbe
java -Xmx128m -cp "$OUT:$ROOT/target/classes" ch.k42.metropolis.grid.urbanGrid.forest.BuriedFallbackCapProbe
