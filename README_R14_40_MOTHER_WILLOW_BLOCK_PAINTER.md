# Metropolis R14.40 — Mother Willow Block Painter Fix

## Root cause

MotherWillowGeometry emits `minecraft:poplar_log[axis=...]` and
`minecraft:poplar_wood` for its wooden trunk and branch scaffold, plus
`minecraft:yellow_poplar_leaves[persistent=true]` for foliage.

BiomeEcology's willow adapter still tested only for the obsolete
`minecraft:oak_log` prefix. Every poplar wood state therefore fell into the
else branch and was painted as OAK_LEAVES. This made the in-game tree appear
as an almost entirely solid leaf mass, with the intended wooden trunk and
branches missing.

## Fix

- Added a shared, explicit `MotherWillowBlockState` classifier for the actual
  geometry vocabulary.
- Directional `poplar_log` sections now paint as OAK_LOG with their axis.
- Directionless `poplar_wood` sections now paint as OAK_WOOD, retaining bark
  on all faces.
- Yellow poplar foliage states alone paint as OAK_LEAVES.
- Root handling and the approved distributed lateral root architecture are
  unchanged.
- R14.39 lake activation/search and independent `/massif` commands are retained.

## Regression gate

`QA/R14_40/MotherWillowBlockPainterProbe.java` runs the production geometry
through the exact same state classifier used by the live chunk painter. It
asserts that wood and leaves are not cross-classified, that both bark-covered
wood and directional logs are present, and that the wooden scaffold exceeds
the foliage voxel count. This specifically catches the adapter mismatch that
the prior geometry-only QA missed.

## Limitations

The probe and Java 25 Maven build are not an in-game visual inspection. The
release is a candidate for a fresh-world check; it is not a claim that the
tree has been visually approved.
