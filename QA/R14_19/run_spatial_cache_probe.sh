#!/bin/sh
set -eu
mkdir -p target/qa-spatial-cache
javac -d target/qa-spatial-cache \
  src/main/java/ch/k42/metropolis/grid/urbanGrid/wild/SpatialCache.java \
  QA/R14_19/SpatialCacheProbe.java
java -cp target/qa-spatial-cache SpatialCacheProbe
