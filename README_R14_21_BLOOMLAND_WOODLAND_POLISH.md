# R14.21 — Bloomlands Woodland Polish

## Mushroom stalk ground contact

The R14.20 offline regression had called Bukkit's registry-backed
`Material.isAir()` without a running server. The whitelist now compares the
three air enum constants explicitly, so the test can run without a server.
Tall grass, flowers, dead bush, and natural cover still yield to stalks; solid
terrain remains protected.

## Mother Willow highest leader junction

Only the highest of the four reiterated leaders receives a smoothed take-off.
Its initial cubic-curve tangent follows the main bole at the attachment point,
then eases toward the existing mid point. The mid-to-top segment, tip, overall
height, and crown remain unchanged.

## Bloomlands understory

Bloomlands gains a denser layer of short (9–16 block) oak, spruce, and birch
trees with narrow curved trunks, irregular branch whorls, and small leaf tufts.
A loose ring of additional trees grows around each accepted giant mushroom,
leaving the immediate stem area open. All voxels respect the existing
Bloomlands and forest ownership; no post-generation repair or external
resources are introduced.

## Validation

Java 25 Maven verification, the full inherited worldgen suite, mushroom
ground-contact regression, Mother Willow junction tangent probe, and compact
understory geometry probe run in CI.
