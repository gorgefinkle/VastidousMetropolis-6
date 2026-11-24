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
grep -Fq 'p.set(cx,y,cz+4,FarmPainter.LADDER,4)' "$HOUSE"
grep -Fq 'p.set(hatchX+1,y,hatchZ,wall,0)' "$BUNKER"
grep -Fq 'this.axisX?cx+dx:cx+dz' "$HOUSE"
grep -Fq 'x + 12' "$HOUSE"
grep -Fq 'findSpot(this.extra, 16, 16' "$LAYOUT"
grep -Fq 'this.variant==0?6:this.variant==1?8:11' "$BUNKER"
grep -Fq 'this.variant==0?9:this.variant==1?15:19' "$BUNKER"
grep -Fq 'this.h-34' "$SILO"
grep -Fq '167,0' "$SILO"
grep -Fq 'getMinHeight()' "$PAINTER"
echo "FarmUndergroundSitesProbe PASS: occasional root cellars, three bunker layouts, sealed deep silo and negative-Y painter bounds"
