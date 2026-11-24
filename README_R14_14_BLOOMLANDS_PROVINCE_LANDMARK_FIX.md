# R14.14 — Bloomlands Province and Landmark Fix

R14.14 follows the R14.13 inland Karst, open landbridge and deep geology build. It addresses two independent causes behind a Bloomlands teleport landing on a tiny patch with no fungal landmarks.

## Province dimensions

R14.13 placed Bloomlands and Karst on a 2x2 final-cell lattice. That was appropriate at shipped defaults (scaleChunks: 520, zoomLevels: 4), where a province was about 1,352 blocks across. Existing configurations can legally use scaleChunks: 40 and zoomLevels: 6, however, making each final cell only 13 blocks and the whole rare province about 26 blocks wide.

R14.14 keeps the common biome resolution as configured but aggregates its cells into a separate rare-habitat lattice. A rare cell is at least 512 blocks wide, so each 2x2 Bloomlands/Karst province is at least 1,024 blocks across. Configured shares still govern how often provinces occur; this is a physical size floor, not a rarity increase.

## Landmark biome selection

The ordinary painter samples its per-column picker using worldSeed XOR 0x464F52455354 (the ForestTerrain seed). BiomeEcology.decorate() was passed the raw world seed and used it directly for the picker roll. Its landmark checks therefore sampled a different biome map from the one actually painted. R14.14 centralizes the world-seed salt and makes the decorator use the painter's exact roll. Giant mushroom and Mother Willow footprint checks are shared with QA.

Bloomlands fungi continue to use mushroom blocks rather than log-based tree substitutes. The Mother Willow remains its own large oak organism.

## Teleport search

A candidate previously required only one matching field point and a forest-owned chunk. R14.14 requires a nine-point 192-block footprint to remain in the requested biome and forest-owned planner area. Search diagnostics distinguish edge-sliver rejections from ownership rejections.

## Validation and release scope

- QA/R14_14/run_bloomlands_province_size_probe.sh checks the supported wilderness scale/zoom bounds.
- QA/R14_13/run_wild_biome_production_picker_probe.sh checks the exact production field and viable giant-mushroom and Mother Willow sites across multiple seeds.
- Existing R14.13 Karst, Morphomos, queue, backpressure, biome command and marine ecology probes remain in the release workflow.

This is a DEV build. Automated probes do not replace visual testing of fresh chunks, especially the density and appearance of giant mushrooms and Mother Willow trees.
