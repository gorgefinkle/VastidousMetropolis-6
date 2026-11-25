#!/bin/sh
set -eu
LAYOUT=src/main/java/ch/k42/metropolis/grid/urbanGrid/farm/FarmLayout.java
PAINTER=src/main/java/ch/k42/metropolis/grid/urbanGrid/farm/FarmPainter.java
HOUSE=src/main/java/ch/k42/metropolis/grid/urbanGrid/farm/FarmHouse.java
BUNKER=src/main/java/ch/k42/metropolis/grid/urbanGrid/farm/FarmBunker.java
SILO=src/main/java/ch/k42/metropolis/grid/urbanGrid/farm/FarmMissileSilo.java
grep -Fq 'new FarmHouse' "$LAYOUT"
grep -Fq 'boolean cellar = this.extra.nextInt(100) < 42' "$LAYOUT"
grep -Fq 'this.planHiddenBunker(gy, used)' "$LAYOUT"
grep -Fq 'this.planMissileSilo(gy, used)' "$LAYOUT"
grep -Fq 'if(this.cellar) paintCellar(p)' "$HOUSE"
grep -Fq 'cellarSet(p,cx,cz,3,y,1,FarmPainter.LADDER,4)' "$HOUSE"
grep -Fq 'p.set(hatchX+1,y,hatchZ,wall,0)' "$BUNKER"
grep -Fq 'this.axisX?cx+dx:cx+dz' "$HOUSE"
grep -Fq 'x + 12' "$HOUSE"
grep -Fq 'findSpot(this.extra, 16, 16' "$LAYOUT"
grep -Fq 'this.variant==0?6:this.variant==1?8:11' "$BUNKER"
grep -Fq 'this.variant==0?9:this.variant==1?15:19' "$BUNKER"
grep -Fq 'SHAFT_RADIUS = 20' "$SILO"
grep -Fq 'SHAFT_DEPTH = 82' "$SILO"
grep -Fq 'super(cx - 22, cz - 22, cx + 22, cz + 22)' "$SILO"
grep -Fq 'Eight substantial foundation piers transfer launch-table loads to bedrock.' "$SILO"
grep -Fq 'this.block(p, dx, baseY, dz' "$SILO"
grep -Fq 'for (int y = baseY + 1; y <= baseY + 3; y++)' "$SILO"
grep -Fq 'clampLevels = {baseY + 10, baseY + 25, baseY + 39}' "$SILO"
grep -Fq 'this.paintHoldDownGantry(p, bottom, baseY)' "$SILO"
grep -Fq 'this.markUsed(used,a,a+2,c,c+2)' "$LAYOUT"
grep -Fq 'getMinHeight()' "$PAINTER"
echo "FarmUndergroundSitesProbe PASS: root cellars, three bunker layouts, 41-wide x 82-deep silo, supported rocket, 3x3 reservation and negative-Y painter bounds"
