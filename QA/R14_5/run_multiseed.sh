#!/bin/bash
set -u
J=/mnt/data/jdk25/usr/lib/jvm/jdk-25.0.5.1-oracle-x64/bin
CP="/mnt/data/r14_5/classes:/mnt/data/r14_4/QA/R14_4/classes:/mnt/data/r14_3/QA/build:/mnt/data/r14_2/QA/r14_2_build_classes:/mnt/data/r14deps/worldedit.jar:/mnt/data/r14deps/gson-2.14.0.jar:/mnt/data/r14deps/stubs_classes:$(find /mnt/data/r14deps/nested -name '*.jar' -print | paste -sd : -)"
while read -r seed a b c d; do
  echo "BEGIN seed=$seed $(date -u +%T)" >> /mnt/data/r14_5/multiseed_status.txt
  timeout -k 5 170 "$J/java" -Xmx1850m -cp "$CP" ch.k42.metropolis.grid.urbanGrid.layout.ActualRoadRuleProbeR144 /mnt/data/r14_2/QA/r14_2_assets/Metropolis /mnt/data/r14_2/QA/r14_2_assets/metadata.json "$seed" "$a" "$b" "$c" "$d" > "/mnt/data/r14_5/road_rules_${seed}.log" 2>&1
  result=$?
  echo "END seed=$seed status=$result $(date -u +%T)" >> /mnt/data/r14_5/multiseed_status.txt
  grep -E '^(ROAD_RULES|REGION_ADJACENCY|DRY_CORNER_ASSERT|MISSING|DEAD_END|UNSHAPED_ROAD)' "/mnt/data/r14_5/road_rules_${seed}.log" | head -14 >> /mnt/data/r14_5/multiseed_status.txt
  if [ "$result" -ne 0 ];then echo "GATE FAILED seed=$seed" >> /mnt/data/r14_5/multiseed_status.txt;fi
done <<'DATA'
13 -128 128 96 352
42 -30 6 96 358
1750414970189483479 -30 10 96 190
123456789 -128 128 96 352
987654321 -128 128 96 352
DATA
printf 'finished\n' > /mnt/data/r14_5/multiseed_done.txt
