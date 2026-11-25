#!/bin/sh
set -eu
BIO=src/main/java/ch/k42/metropolis/grid/urbanGrid/forest/BiomeEcology.java
TREES=src/main/java/ch/k42/metropolis/grid/urbanGrid/forest/ForestTrees.java
if grep -Eq '(^|[^[:alnum:]_])y[[:space:]]*<=[[:space:]]*2|(^|[^[:alnum:]_])y[[:space:]]*<[[:space:]]*1' "$BIO" "$TREES"; then
  echo "FAIL: hard-coded positive-Y vegetation cutoff remains" >&2
  exit 1
fi
mkdir -p target/qa-bloom-understory
javac -d target/qa-bloom-understory \
  src/main/java/ch/k42/metropolis/grid/urbanGrid/forest/BloomUnderstoryGeometry.java \
  src/main/java/ch/k42/metropolis/grid/urbanGrid/forest/VegetationHeightPolicy.java \
  QA/R14_21/BloomUnderstoryGeometryProbe.java
java -Xmx128m -cp target/qa-bloom-understory ch.k42.metropolis.grid.urbanGrid.forest.BloomUnderstoryGeometryProbe
