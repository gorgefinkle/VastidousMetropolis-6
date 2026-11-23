#!/bin/bash
set -u
J=/mnt/data/jdk25/usr/lib/jvm/jdk-25.0.5.1-oracle-x64/bin
CP="/mnt/data/r14_4/QA/R14_4/classes:/mnt/data/r14_3/QA/build:/mnt/data/r14_2/QA/r14_2_build_classes:/mnt/data/r14deps/worldedit.jar:/mnt/data/r14deps/gson-2.14.0.jar:/mnt/data/r14deps/stubs_classes:$(find /mnt/data/r14deps/nested -name '*.jar' -print | paste -sd : -)"
D=/mnt/data/r14_4/QA/R14_4
for row in '13 -128 128 96 352' '42 -30 6 96 358' '1750414970189483479 -30 10 96 190' '123456789 -128 128 96 352' '987654321 -128 128 96 352'; do
  set -- $row
  seed=$1
  echo "BEGIN $seed $(date -u +%T)" >> "$D/batch_status.log"
  timeout -k 5 105 "$J/java" -Xmx1800m -cp "$CP" ch.k42.metropolis.grid.urbanGrid.layout.ActualRoadRuleProbe "/mnt/data/r14_2/QA/r14_2_assets/Metropolis" "/mnt/data/r14_2/QA/r14_2_assets/metadata.json" "$@" > "$D/seed_${seed}_final.log" 2>&1
  rc=$?
  echo "END $seed rc=$rc $(date -u +%T)" >> "$D/batch_status.log"
  grep -E '^(ROAD_RULES|REGION_ADJACENCY|MISSING|DEAD_END|UNSHAPED_ROAD)' "$D/seed_${seed}_final.log" | head -12 >> "$D/batch_summary.log"
done
