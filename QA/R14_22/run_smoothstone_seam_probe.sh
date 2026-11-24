#!/bin/sh
set -eu
ROOT=$(CDPATH= cd -- "$(dirname -- "$0")/../.." && pwd)
TMP=$(mktemp -d)
trap 'rm -rf "$TMP"' EXIT
mvn -B -ntp -q dependency:build-classpath -DincludeScope=compile -Dmdep.outputFile="$TMP/classpath"
OUT="$ROOT/target/qa-smoothstone-seam"
mkdir -p "$OUT"
CP="$ROOT/target/classes:$(cat "$TMP/classpath")"
javac -cp "$CP" -d "$OUT" \
  "$ROOT/QA/R14_22/SmoothstoneSeamProbe.java" \
  "$ROOT/QA/R14_22/BuriedFallbackCapProbe.java"
java -Xmx128m -cp "$OUT:$CP" ch.k42.metropolis.generator.SmoothstoneSeamProbe
java -Xmx128m -cp "$OUT:$CP" ch.k42.metropolis.grid.urbanGrid.forest.BuriedFallbackCapProbe
