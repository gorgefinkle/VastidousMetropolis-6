# Metropolis R14.5 — Correct redundant double frontage after corner reservation

**Status: development checkpoint; Paper 26.2 seed-13 placement + sampled chunk generation checked.**
Target: Minecraft/Paper 26.2, Java 25; WorldEdit 7.4.5.

## Root cause

R14.4 correctly started reserving previously omitted dry right-angle district corners,
but ran `WorldLayout.reserveDryFrameCorners()` *after* `DoubleFrontageRoads.preventWithPlan()`.
That left the frontage optimizer with an incomplete end cap at `(80,239)` on seed `13`,
so it rejected the four-cell redundant inner street column at `(80,235..238)`.
The neighboring domain's immutable lattice road `(81,235..238)` then remained beside
that inner strip; two adjacent road cells were rendered as malformed T-shaped roads.

The exact same production `DoubleFrontageRoads.valid()` returned true **after**
all corners were reserved, proving this was a planning-order issue. R14.5 makes
one surgical change: call the existing dry-corner reservation **before** the
frontage optimizer, retaining the later call for additional corners made available
by subsequent rural boundary donations. No new road-painting or repair pass.

The R14.5 production diff is limited to the call order in `WorldLayout.java`
and the plugin version metadata. `DoubleFrontageRoads.java` is unchanged from
R14.4. Creek/tree ownership and generation behavior are unchanged.

## Before and after, real seed

Seed 13, 64x64 chunk planning region around `(80,239)`:

- R14.4: forest `[75..79,235..238]`; `x=80` and `x=81` are both street for z=235..238.
- R14.5: forest `[75..80,235..238]`; only `x=81` remains `STREET` in the layout for z=235..238, and Morphomos lawfully dissolves that forest/forest seam at runtime (so both columns become forest, rather than two road columns).
- End-cap streets at z=234 and z=239 remain; corner (80,239) remains a street.

## Independent probes

- 250 deterministic double-frontage cases pass with production methods, covering
  both orientations, immutable fixed streets, protected urban frontage, highway
  lanes, missing end joins and rectangle size limits.
- `CornerFrontageOrderProbe` fails the frontage qualification before the required
  corner exists, and passes after the corner is reserved. Its four interior
  road cells are absorbed into the forest rectangle, preserving end connectors.
- Five seeded WorldLayout/PlanRegions/RoadShapes probes pass on 1,918 sampled
  district rectangles (13, 42, 1750414970189483479, 123456789, 987654321):
  zero missing mandatory edges, missing urban corners, unshaped roads,
  one-ended road cells or invalid cross-context Morphomos dissolutions.
- Java 25 clean build: 253 production source files and 531 class files against
  actual Paper 26.2, WorldEdit 7.4.5, Guava 33.6 and Gson 2.14 dependencies.

## Live Paper 26.2 integration: seed 13 (Java 25, supplied plugins.zip)

A real local Paper server loaded the R14.5 production JAR, imported the supplied
WorldEdit schematics and generated the seed-13 QA world. The corrected Paper QA
assertion distinguishes retained street parcels from legal FOREST/FOREST Morphomos
dissolution; an earlier assertion incorrectly demanded a live road on an intentionally
dissolved seam.

- Tile origin `(64,192)`: 122 retained planned streets, 122 matching road/bridge/highway parcels; **zero missing and zero unshaped** road parcels.
- Four targeted z rows 235–238: `x=80` forest/Morphomos, `x=81` forest/Morphomos, with FOREST on both opposing cardinal faces and `PlanRegions.dissolvedAt(81,z)=true`. All **4/4** exact-row assertions pass.
- Required corner `(80,239)` remains a `RoadParcel`, road shape `STREET_I_EW`.
- Actual populated block/chunk samples: road `(80,239)` top `STONE` at y63; former parallel frontage `(80,235)` top `PODZOL` at y64; dissolved forest seam `(81,235)` top `PODZOL` at y63. Additional road sample chunks `(79,192)` and `(81,192)` generated without QA failures.
- QA finishes `END seed=13 missing=0 unshaped=0`, server cleanly shut down.

Reproducible Paper integration QA source is at `QA/Paper_Integration/RoadPlacementQA.java`.
A condensed run trace is at `QA/Paper_Integration/Paper_R145_placed_roads_assertions.log`.

**Limits:** This confirms the reproduced double-width defect at the parcel and
sampled-block levels, not every bridge transition, seed, full district schematic,
or user-visible terrain smoothing. The five-seed tests remain planner-level only.
Buffers/backpressure were not modified. Creek ownership was not modified.
