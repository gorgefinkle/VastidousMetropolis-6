#!/usr/bin/env sh
set -eu
mkdir -p target/qa-growth-timeout-lifecycle
javac -d target/qa-growth-timeout-lifecycle \
  src/main/java/ch/k42/metropolis/grid/urbanGrid/provider/BranchGrowthTimeoutLifecyclePolicy.java \
  QA/R14_17/GrowthTimeoutShutdownPolicyProbe.java
java -Xmx64m -cp target/qa-growth-timeout-lifecycle ch.k42.metropolis.grid.urbanGrid.provider.GrowthTimeoutShutdownPolicyProbe
