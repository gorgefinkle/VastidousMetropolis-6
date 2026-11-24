#!/bin/sh
set -eu
ROOT=$(CDPATH= cd -- "$(dirname -- "$0")/../.." && pwd)
OUT="$ROOT/target/qa-farm-road-routes"
mkdir -p "$OUT"
javac -d "$OUT" \
  "$ROOT/src/main/java/ch/k42/metropolis/grid/urbanGrid/farm/FarmRoadRoutePolicy.java" \
  "$ROOT/QA/R14_23/FarmRoadRoutePolicyProbe.java"
java -Xmx96m -cp "$OUT" ch.k42.metropolis.grid.urbanGrid.farm.FarmRoadRoutePolicyProbe
