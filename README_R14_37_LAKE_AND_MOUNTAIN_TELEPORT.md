# Metropolis R14.37 — Lake and Mountain Teleport

## Baseline and scope

Built on R14.36 Mother Willow Structure. This revision adds a lake teleport
command and makes the named old-growth mountain massifs valid targets for the
existing mountain biome teleport. No terrain generation, biome selection,
lake placement, or mountain placement rules are changed.

## Lake command

- `/lake` reports the nearest planned named lake and its coordinates.
- `/lake tp` teleports to a verified safe shoreline landing.
- Lookup uses the deterministic WildernessFeatures plan and does not scan
  arbitrary generated chunks to discover lakes.
- The landing is selected on the lake's natural shelf outside its water edge.

## Mountain biome teleport

- `/metrobiome tp mountains` continues to find ordinary mountain-biome regions.
- It now also recognizes the separately planned named mountain massifs.
- Candidate ownership and generated-column safe-landing checks still apply;
  the command does not teleport to an unverified mountain coordinate.

## Validation

The Java 25 workflow runs the biome-target regression, compiles the full
plugin, checks the embedded version, and packages the current JAR, complete
source ZIP, and checksums.
