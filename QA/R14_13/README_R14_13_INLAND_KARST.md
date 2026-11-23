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
