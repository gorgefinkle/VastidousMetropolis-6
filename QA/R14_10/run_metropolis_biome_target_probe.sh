#!/usr/bin/env sh
set -eu
ROOT="$(CDPATH= cd -- "$(dirname -- "$0")/../.." && pwd)"
cd "$ROOT"
OUT="target/qa-metrobiome-targets"
mkdir -p "$OUT"
javac --release 25 -d "$OUT" \
  src/main/java/ch/k42/metropolis/grid/urbanGrid/wild/WildBiome.java \
  src/main/java/ch/k42/metropolis/commands/MetropolisBiomeTargets.java \
  QA/R14_10/MetropolisBiomeTargetProbe.java
java -cp "$OUT" MetropolisBiomeTargetProbe
