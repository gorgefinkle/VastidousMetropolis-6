#!/bin/sh
set -eu
mkdir -p target/qa-bloom-understory
javac -d target/qa-bloom-understory \
  src/main/java/ch/k42/metropolis/grid/urbanGrid/forest/BloomUnderstoryGeometry.java \
  src/main/java/ch/k42/metropolis/grid/urbanGrid/forest/VegetationHeightPolicy.java \
  QA/R14_21/BloomUnderstoryGeometryProbe.java
java -Xmx128m -cp target/qa-bloom-understory ch.k42.metropolis.grid.urbanGrid.forest.BloomUnderstoryGeometryProbe
