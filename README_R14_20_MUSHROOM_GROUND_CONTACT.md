# R14.20 — Mushroom Ground Contact

## Root cause

Forest ground cover is painted before biome landmarks. The forest can place
Tall Grass, Dead Bush, Dandelions, and the nine flower types in the exact block
where a giant mushroom's lowest stalk voxel should be. The mushroom stamper's
replaceable-block whitelist omitted those materials, so it refused to place the
foot of the stalk. The mushroom geometry itself began at `groundAt(x,z)+1`;
the gap was created by the block-placement gate.

## Change

The shared vegetation replacement whitelist now includes the single-block
ground-cover materials emitted by the forest painter: Tall Grass, Dead Bush,
Dandelion, Poppy, Blue Orchid, Allium, Azure Bluet, the four Tulips, and Oxeye
Daisy. Solid ground blocks remain protected. This lets the first stem voxel
replace the vegetation occupying its exact location instead of leaving a gap.

No post-generation repair pass or detached cleanup is added. The fix is applied
at the original deterministic mushroom placement point.

## Validation

The new R14.20 probe runs all six mushroom profiles across 80 positions and
all 12 ground-cover materials, asserting that the first central stalk block
touches the ground. It also verifies that stone, dirt, grass blocks, podzol,
sand, and tuff remain non-replaceable. The full inherited Java 25 and worldgen
suite runs alongside this probe.
