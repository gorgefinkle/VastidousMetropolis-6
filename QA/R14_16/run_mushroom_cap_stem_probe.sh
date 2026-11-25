#!/usr/bin/env sh
set -eu
mkdir -p target/qa-mushroom-cap
javac -d target/qa-mushroom-cap \
  src/main/java/ch/k42/metropolis/grid/urbanGrid/forest/MushroomGeometry.java \
  QA/R14_16/MushroomCapStemProbe.java
java -Xmx128m -cp target/qa-mushroom-cap ch.k42.metropolis.grid.urbanGrid.forest.MushroomCapStemProbe
