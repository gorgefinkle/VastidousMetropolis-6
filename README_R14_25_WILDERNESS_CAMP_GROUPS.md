# R14.25 — Wilderness camp groups

Wilderness survivor camps now generate as small, recognizable clusters rather than isolated camp compounds.

- Each group contains two or three separate camp compounds.
- Each compound retains its existing one-to-three A-frame tents, fire ring, flag, supplies, and loot behavior.
- Compounds within a group are arranged in a compact, rotated layout with roughly 30–36 blocks between their centres.
- Group centres have a deterministic minimum separation of 384 blocks at the default setting. Higher configured camp spacing increases that gap, up to the configured policy cap.
- The camp spacing floor is now 400%. Existing configs that still say 100% or 250% inherit the wider floor; larger user values remain respected.
- Camps use their own deterministic cross-cell planner, separate from mines and radio masts. The same group plan is painted consistently no matter which chunk generates first.

The R14.25 validation probe checks the legacy-config floor, minimum group gap, configured-spacing scaling, compact two- and three-site layouts, and all four rotations.

Automated checks do not replace a fresh Paper 26.2 world test. In particular, inspect camp distribution across several wilderness biomes and confirm that terrain exclusions leave groups intact rather than producing single orphan compounds.
