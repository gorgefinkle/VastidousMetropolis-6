#!/usr/bin/env sh
set -eu
mkdir -p target/qa-camp-spacing
javac -d target/qa-camp-spacing \
  src/main/java/ch/k42/metropolis/grid/urbanGrid/forest/WildDecorSpacingPolicy.java \
  QA/R14_25/WildDecorCampSpacingProbe.java
java -cp target/qa-camp-spacing ch.k42.metropolis.grid.urbanGrid.forest.WildDecorCampSpacingProbe
