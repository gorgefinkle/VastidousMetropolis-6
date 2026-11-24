#!/bin/sh
set -eu
ROOT=$(CDPATH= cd -- "$(dirname -- "$0")/../.." && pwd)
TMP=$(mktemp -d)
trap 'rm -rf "$TMP"' EXIT
JAVA="$ROOT/src/main/java/ch/k42/metropolis/grid/urbanGrid"
javac -d "$TMP" \
  "$JAVA/enums/RoadType.java" \
  "$JAVA/highway/HighwayNetwork.java" \
  "$JAVA/river/CreekCityOriginPolicy.java" \
  "$ROOT/QA/R14_15/HighwayNetworkCityLookupProbe.java" \
  "$ROOT/QA/R14_15/CreekCityOriginPolicyProbe.java"
java -Xmx128m -cp "$TMP" ch.k42.metropolis.grid.urbanGrid.highway.HighwayNetworkCityLookupProbe
java -Xmx128m -cp "$TMP" ch.k42.metropolis.grid.urbanGrid.river.CreekCityOriginPolicyProbe
