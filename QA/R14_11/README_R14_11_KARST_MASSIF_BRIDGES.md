# R14.11 — Karst Massifs and Safe Landbridges

This revision corrects Karst's terrain identity and landbridge ownership.

## Karst terrain
- The Karst biome now selects the whole limestone massif, not just the narrow valley-channel suitability mask.
- Massifs rise as broad upland benches with low-frequency terrain variation and enough continuous ground for substantial structures.
- Long dissolution channels are cut deeply through the massif toward the normal build-height datum. The valley floor and the upper massif are independent parts of the same relief field.
- Bloomlands relief and its legacy crevasse path remain separate.

## Landbridges
- A bridge is emitted only when the entire 13-by-13 chunk safety footprint belongs to the same forest Morphomos region.
- Roads, water, city parcels, other regions, EmptyParcel and fallback parcels veto the span. Shore relief does not create Karst arches.
- The bridge floor is reconstructed from the unbridged relief and the actual build baseline, not from a partially blended surface height.
- A candidate is refused if its actual top disagrees with the expected complete-profile top, if the floor is below the safe datum, or if the open void / keystone dimensions are inadequate.
- The central span has a substantial limestone keystone and a broad open arch. No bridge carve loop starts at Y=4.

## Bloomlands search
The biome search now evaluates the deterministic requested-biome field before asking the GridProvider to construct or retrieve the expensive planner tile. Only actual target hits pay the planner ownership lookup cost.

## QA
The Karst clearance probe now checks safe floor height, a large open arch and a thick central keystone. The mountain profile probe checks mountain-scale uplands, deep inner cuts and a minimum proportion of broad low-gradient building ground. The existing R14.10 regression probes remain in the CI workflow.
