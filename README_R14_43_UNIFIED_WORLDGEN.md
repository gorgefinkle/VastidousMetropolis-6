# Metropolis R14.43 — Unified Worldgen and Mother Willow

This is the consolidated R14.43 test line. Mother Willow, named lakes and outlet streams,
named massifs, destination searches, mountain profiles, creek-origin spacing, Bloomlands,
FARM work and the missile-silo complex are carried together in one source tree and one JAR.

## Mother Willow — structural and mathematical reassessment

The reference target is a mature weeping willow with a broad rounded crown, visible woody
scaffold and long fine branchlets that hang in curtains. It must not read as a flat umbrella,
a solid leaf ball or a set of limbs that simply taper into spikes.

The geometry retains the approved fused old-growth bole, four staggered leaders, broad lateral
buttress/root plate and distributed deep sinkers. There is no central taproot. Four leaders
attach at staggered heights along the bole; the main scaffold travels outward before gravity
increasingly turns later branch orders downward.

Branch diameter still follows the pipe-model approximation used by the generator:

r(t) = ((1-t) r0^2.35 + t r1^2.35)^(1/2.35)

This blends conducted cross-sectional area rather than tapering the radius linearly. The
hierarchy is now explicit:

- Primary limbs preserve their lateral daughters and also split at their distal ends into
  two or three subordinate limbs.
- Secondary limbs likewise retain a side daughter and bifurcate at their own ends.
- Third-order outer scaffold tips open into three- or four-way fans of finer hanging branchlets.
- Fine terminal shoots remain woody through their tips. Persistent foliage is placed as separated
  clusters along pendulous twigs and at their ends, rather than replacing the twigs with leaves.
- The branch orders have progressively stronger gravity-driven sag. The parent scaffold remains
  visible through the crown and leaves are checked across twelve azimuth sectors.

The old fence-trail path has been removed. The production block-state classifier remains part of
the regression: poplar logs, bark-sided poplar wood and yellow-poplar leaves are tested as distinct
output types. Automated checks verify wood volume, outer/inner leaf distribution, primary and
secondary forks, terminal fork counts and crown-direction coverage.

The tree proportions and silhouette are based on botanical descriptions rather than a generic
drooping tree. Oregon State describes a broad rounded crown with thin pendulous branches; UF/IFAS
gives a typical 30–50 ft height and equal-or-greater spread; Purdue describes branchlets hanging
like a curtain; Virginia Tech describes long slender rope-like drooping twigs; and World
Agroforestry records an open crown with branchlets hanging vertically toward the ground.

References:
- Oregon State Landscape Plants: https://landscapeplants.oregonstate.edu/plants/salix-babylonica
- UF/IFAS Extension: https://edis.ifas.ufl.edu/publication/ST576
- Purdue Arboretum Explorer: https://www.arboretum.purdue.edu/explorer/plants/22456/
- Virginia Tech Dendrology: https://dendro.cnre.vt.edu/dendrology/syllabus/factsheet.cfm?ID=131
- World Agroforestry Species Profile: https://apps.worldagroforestry.org/treedb2/speciesprofile.php?Spid=18057

### Actual Minecraft block preview

The included Mother Willow schematic is exported from the exact block states emitted by
MotherWillowGeometry, not from an AI render or a manually drawn proxy. Import
QA/R14_43/previews/MotherWillow_R14_43_Preview.schem into a Minecraft test world with WorldEdit.
The release workflow validates its Sponge schematic NBT and the source geometry, but that is
not a claim that the tree has already been visually inspected on a live Paper server or approved.

## Lakes and streams — R14.41 visibility failure corrected

The old planner selected lakes in only a small fraction of very large feature cells and then
discarded an otherwise valid lake if it could not route an outlet to receiving water. That meant
a player could explore a substantial area and see neither a lake nor a stream.

The new planner increases the density and number of candidate placements, searches a wider
candidate area, and no longer makes a valid basin conditional on outlet success. A lake is kept
as a real LakeParcel even when it is a closed basin. When a legal path to an existing river or
ocean is found, a continuous polyline outlet is attached and painted by the shared water plan.
Streams remain independent from city-creek sites, may cross a highway only perpendicularly, and
do not generate storm drains.

- /lake reports the nearest planned basin with no fixed search-radius cutoff.
- /lake tp selects a checked shoreline landing.
- A basin with no legal outlet is reported honestly as a closed lake.
- The new release regression follows actual planned cells through LakeParcel ownership and the
  shared WaterField, and separately verifies continuous stream-carrier ownership.

## Named massifs and mountain forms

Named massifs retain an 80–89 block summit above the local terrain baseline with clearance below
the world build height. The summit is now an attained rounded crown, not just an unused height cap.
One side holds a high shoulder before breaking into a steeper irregular escarpment with gullies;
the opposite side begins a longer but still uneven descent. The regression samples both flanks,
the rim irregularity, deterministic naming, snowline and treeline behaviour.

Volcanic cones have been removed as a mountain kind. The old volcano mountain-share key is ignored
by the loader, so a saved config cannot reactivate the cone profile. Karstlands remains the separate
fractured-limestone habitat; it is not a volcano.

## Silo and retained work

The missile silo retains its buried access ladder, control rooms, blast-lock doors and lift. The
visible guard station is gone; only one flush concealed access hatch remains at the surface. No
glowstone, raised surface cage or guard-room façade is reintroduced.

Bloomlands remains on the ordinary wilderness biome grid. The one-creek-origin-per-city spacing
policy and current creek routing are retained. The build adds no post-generation terrain repair.

## Unified R14.43 verification

The single active release workflow runs the Mother Willow production geometry/painter checks and
schematic export, named-lake and outlet-stream planning/ownership coverage on regression seeds,
the capped asymmetric massif checks, no-volcano proof, creek-origin spacing audit, all three
silo-variant geometry probes, legacy block-mapping checks, Java 25 compilation and full-source
packaging. Delivery remains one current JAR, one version-specific full-source ZIP and checksums;
the real schematic preview is included within the source ZIP under QA/R14_43/previews.
