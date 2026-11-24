# R14.11 — Underwater Ocean Ecology

This revision replaces the ocean's thin seabed vegetation pass with deterministic underwater habitat zoning.

## Added seabed habitats

- **Seagrass meadows** with occasional small sea-pickle colonies.
- **Kelp forests** formed from coherent, multi-column patches rather than isolated low-probability stalks.
- **Coral reefs** using the five vanilla coral colour families, coral blocks and waterlogged fans.
- **Sponge gardens** using wet sponge formations in deeper water.
- **Hydrothermal vent fields** using dark stone, basalt, tuff and very rare magma accents.
- Barren sediment and rock patches remain between habitats; habitat boundaries are seed-stable and spatially coherent.

All detail is painted in the existing ocean-owned seabed pass. It does not move the shoreline, change the water-column depth profile, or run a post-generation repair pass. OceanSeabed.maximumFeatureHeight() now reports the ecology's conservative feature-height envelope to the existing ship-hull clearance check.

## Default tuning

The default seabed patch scale is reduced from 56 to 44 blocks, rock density increased from 12% to 18%, seagrass from 17% to 34%, and kelp from 9% to 28%. Existing config files retain their explicit values; these are new-install defaults.

## QA

Run QA/R14_11/run_marine_habitat_probe.sh to validate deterministic habitat selection, representative coverage for all five habitats, retained barren space, patch adjacency, depth/shore gates, and maximum feature heights.
