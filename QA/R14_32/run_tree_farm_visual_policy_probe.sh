#!/bin/sh
set -eu
WILLOW=src/main/java/ch/k42/metropolis/grid/urbanGrid/forest/MotherWillowGeometry.java
FOREST=src/main/java/ch/k42/metropolis/grid/urbanGrid/forest/ForestTrees.java
COMPANION=src/main/java/ch/k42/metropolis/grid/urbanGrid/forest/CompanionTreeGeometry.java
MUSHROOM=src/main/java/ch/k42/metropolis/grid/urbanGrid/forest/MushroomGeometry.java
LAYOUT=src/main/java/ch/k42/metropolis/grid/urbanGrid/farm/FarmLayout.java
PROPS=src/main/java/ch/k42/metropolis/grid/urbanGrid/farm/FarmProps.java
OUTBUILDINGS=src/main/java/ch/k42/metropolis/grid/urbanGrid/farm/FarmOutbuildings.java

grep -Fq 'axis.equals("none")?"minecraft:oak_wood"' "$WILLOW"
grep -Fq 'data | 12' "$FOREST"
grep -Fq 'axis.equals("none")?bark:wood+' "$COMPANION"
grep -Fq 'maker.curved(joint,midBranch,end,spruce?1.3:1.8,0.65,true)' "$COMPANION"
grep -Fq 'directionless?"none":"x"' "$COMPANION"
grep -Fq 'directionless?"none":"z"' "$COMPANION"
grep -Fq 'directionless?"none":"y"' "$COMPANION"
grep -Fq 'branchSegment(last,next' "$WILLOW"
grep -Fq 'minecraft:oak_fence[north=true,east=true,south=true,west=true,waterlogged=false]' "$WILLOW"
grep -Fq 'leaderIndex>=2?.34:.48' "$WILLOW"
grep -Fq 'leaderMid=leaderRoot*(i>=2?.52:.638)' "$WILLOW"
grep -Fq 'leaderTip=leaderMid*(i>=2?.26:.365)' "$WILLOW"
grep -Fq 'Math.sin(Math.PI*t)*bend' "$MUSHROOM"
grep -Fq 'emitStemVoxel(sink,terrain,stems,crown,current)' "$MUSHROOM"
! grep -Fq 'connectGroupedCapTops' "$MUSHROOM"
! grep -Eiq 'Tractor|Pickup|tractor|pickup' "$LAYOUT"
! grep -Eiq 'class Tractor|class Pickup' "$PROPS" "$OUTBUILDINGS"
! grep -Eiq 'new FarmProps.Tractor|new FarmOutbuildings.Pickup' src/main/java/ch/k42/metropolis/grid/urbanGrid/farm
echo "TreeFarmVisualPolicyProbe PASS: tapered upper leaders, bark-textured directionless branches, continuous thin willow trails, curved cap-connected grouped mushroom stalks, and no multiblock farm vehicles"
