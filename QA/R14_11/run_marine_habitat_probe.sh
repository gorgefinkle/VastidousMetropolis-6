#!/usr/bin/env bash
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
OUT="$ROOT/target/qa-marine-habitats"
mkdir -p "$OUT"

javac -d "$OUT" \
  "$ROOT/src/main/java/ch/k42/metropolis/grid/urbanGrid/ocean/MarineHabitatModel.java" \
  "$ROOT/QA/R14_11/MarineHabitatProbe.java"
java -cp "$OUT" MarineHabitatProbe
