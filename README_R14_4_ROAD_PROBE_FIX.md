# Metropolis R14.4 — Road-frame and Morphomos probe/fix (DEVELOPMENT)

Date: 2026-11-21 Pacific. Target: Paper 26.2, Java 25.

**Status: development-only. Do not treat this as a server-approved build or begin overnight generation.** This checkpoint includes a compiled DEV JAR and complete production source, but source compilation used locally reconstructed dependency stubs for unavailable Guava/Paper transitive dependencies, and the final block-placement/visual checks are not certified.

## Intended invariant

Every *planned district rectangle* has a complete N/E/S/W road frame and valid right-angle corners; neighboring district faces share the same road. Only internal streets strictly between like-context Morphomos districts (e.g. FARM/FARM or FOREST/FOREST) may dissolve. Disconnected groups of complete rectangles are legal; no global highway-connection requirement is imposed. Creek, river, forest and terrain parcels must not erase retained roads. No post-generation road-patching pass may be substituted for correct planning and ownership.

## Reproduced code defects and changes

1. `PlanRegions.walkEndsDead()` used `nx == -1` as "not found". Negative world coordinate `-1` is valid; it can falsely terminate a walk. Replaced with a boolean `foundNext` flag.
2. A two-step offset between mandatory forest/water road faces could be trimmed at the elbow by the dead-chain pass, leaving four one-ended roads around `(-10,135)` and `(-10,170)` on seed `1750414970189483479`. R14.4 recognizes the **already-planned** short street turn and its straight approach between mandatory, independent road anchors. It never creates roads inside a BLOCK or a water/ZONE parcel.
3. Dry rural forest margins were not reliably recognized as distinct exterior boundaries; R14.4 preserves existing planned street frontage against `NATURAL` or explicitly unclaimed dry exterior, without transferring any creek/tree ownership.
4. A corner of forest district `[75..79,235..238]` at `(80,239)` on seed `13` was `ZONE` through zoning, later converted to `NATURAL`, and never became a road. `WorldLayout.reserveDryFrameCorners()` runs **during zoning**: it reserves an otherwise omitted dry corner only if both existing straight road arms already exist and it is not a strictly like-context internal seam. Real water, lots and non-street arms cannot be overwritten.
5. **Rejected experiment:** a broader elbow rule retained two isolated road cells at `(-12,243)` and `(-12,260)` on seed `987654321`. Their production `ruleDissolves` was `true` (same-context forest/forest internal seam). R14.4 now explicitly rejects use of short-turn preservation where `ruleDissolves` applies. Both isolated cells returned to legal Morphomos dissolution in the focused retest.
6. Existing `R14.1` forest/water retention behavior remains in place. `UrbanGrid.java`, `ForestGenerator.java` and `RiverParcel.java` match R14.1 byte-for-byte; **the abandoned creek ownership reassignment has not been reintroduced**.

## Independent road-plan probes

`QA/R14_4/ActualRoadRuleProbeR144.java` exercises production `WorldLayout`, `PlanRegions`, `RoadShapes`, `WaterField` and seed metadata. The Python gate `QA/R14_4/assert_planner_logs.py` fails on missing edge, urban corner, unshaped street, one-ended street, invalid Morphomos adjacency, insufficient rectangle coverage, or incomplete run. The exact test windows/outputs are in `QA/R14_4/batch_status.log` and `QA/R14_4/batch_summary.log`.

The `ActualRoadRuleProbe` originally only *logged* failures; output alone did not cause nonzero exit. A strict, separately compiled `ActualRoadRuleProbeR144` now also throws on any such failure and specifically checks the `seed=13` `(80,239)` corner is a retained road.

## Build and QA limits

- 253 production Java sources compile under JDK 25 to 531 class files.
- The included JAR is packaged **only** from these project class files and `src/main/resources` (one DEV JAR, no shaded QA stub classes). The offline compilation used substitute Guava type definitions; a clean Maven/Paper dependency build is still required for certification.
- The seed probes validate the *committed layout and road shape*, not blocks in a running Paper world. Actual road schematics, creek bridge approaches and bank slope/forest painting may still need a real server/in-game verification.
- No buffer/backpressure code was changed. Do not proceed with buffer work until road/block placement is proven.

## Confirmed final R14.4 probe results

The five-seed deterministic gate was executed against the patched production planner and finished with a successful assertion-script exit. Across **1,918 distinct sampled district rectangles**:

| Seed | Sampled rectangles | Missing required edges | Missing urban corners | Unshaped road cells | One-ended road cells |
| --- | ---: | ---: | ---: | ---: | ---: |
| `13` | 625 | 0 | 0 | 0 | 0 |
| `42` | 54 | 0 | 0 | 0 | 0 |
| `1750414970189483479` | 34 | 0 | 0 | 0 | 0 |
| `123456789` | 640 | 0 | 0 | 0 | 0 |
| `987654321` | 565 | 0 | 0 | 0 | 0 |

A separate strict direct-corner probe on seed `13` reports `DRY_CORNER_ASSERT seed=13 x=80 z=239 plannedKind=2 retained=true` and zero errors across another 181-rectangle sample. This confirms **planned-road ownership**, not that blocks have been placed in a running Paper world.

The first broad retention experiment failed on seed `987654321` with two unshaped roads and was discarded. The final logic explicitly exempts legitimate same-context seams from the corner-retention rule, and the final five-seed gate passes. These tests **do not** justify resuming buffer work or claiming the in-game road failures are fully resolved until actual parcel and block placement have been independently verified.
