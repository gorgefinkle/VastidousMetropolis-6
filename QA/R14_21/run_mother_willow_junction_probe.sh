#!/bin/sh
set -eu
grep -Fq 'bulb(a,radA+1.35,true' src/main/java/ch/k42/metropolis/grid/urbanGrid/forest/MotherWillowGeometry.java
mkdir -p target/qa-willow-junction
javac -d target/qa-willow-junction \
  src/main/java/ch/k42/metropolis/grid/urbanGrid/forest/MotherWillowGeometry.java \
  QA/R14_21/MotherWillowJunctionProbe.java
java -Xmx128m -cp target/qa-willow-junction ch.k42.metropolis.grid.urbanGrid.forest.MotherWillowJunctionProbe
