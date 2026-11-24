# R14.13 — Inland Karst, open landbridges and deep geology

This release candidate changes the Karst landform field and its painter handoff. It is intended to be checked on fresh chunks; it does not repair or repaint generated terrain.

## Intended generation rules

- The complete limestone massif is kept inland of the ocean, the two main rivers and accepted bounded creeks. Karst suitability eases from zero at 1,600 blocks from a water bank to full strength at 2,400 blocks.
- The massif uplands remain broad and buildable. Dissolution cuts have wider graded shoulders to reduce abrupt escarpments.
- Landbridges are allowed only in the fully weighted, un-faded interior of a single forest-owned region.
- A bridge's upper relief and undercut reconstruction use the same absolute surface height. Partial biome blending is not allowed to scale the bridge crown while leaving the floor reconstruction unscaled.
- Limestone, tuff, calcite, diorite and andesite lenses extend well below the surface. Existing block types ending in `_ORE` are retained during the lithology pass.

## Automated checks

The Java 25 Maven build and the existing worldgen probes run in the R14.13 workflow. The Karst profile probe now checks the 95th-percentile height change across 16-block shoulder samples in addition to upland, cut-depth and buildable-area metrics. The landbridge probe checks open clearance, continuous natural floor and central keystone thickness.

These checks validate deterministic fields and painter geometry; a fresh Paper world still needs visual review for massif enclosure, water separation, slope quality, open bridge gaps and geological depth.

## Bloomlands discovery safeguards

- The biome-teleport search uses a 32-block sampling lattice because the final wilderness region cells are hundreds of blocks across. A candidate chunk is still verified column-by-column against the exact production picker and actual safe ground; Bloomlands share and region scale are not changed by this search optimization.
- A destination chunk request is bounded by a 180-second timeout. If Paper never completes a candidate future, that candidate is counted as a generation failure and the search continues instead of remaining stuck on one request.
- Search progress/final output distinguishes field matches, planner-owned candidates, ownership rejections, safe landings, unsafe/no-landing chunks and generation failures.
- The production-picker regression exercises the same WildField.pick() and ForestTerrain per-column hash used by the painter, then checks that a coarser-than-production square-ring search can encounter Bloomlands inside the default world border. The older label-map probe remains as a separate lower-level check.

The new production-picker and full-border tests do not replace an in-game visual test. If the search fails in a live world, its new counters identify whether the remaining blocker is world-plan ownership, destination chunk completion, or safe-surface verification.
