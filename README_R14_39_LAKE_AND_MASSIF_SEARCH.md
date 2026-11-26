# Metropolis R14.39 — Lake and Massif Search

## Lake discovery and activation

- `/lake` and `/lake tp` search the deterministic feature-cell plan outward
  with no fixed search-radius cutoff. Search runs asynchronously so rare
  outlet planning does not freeze the server tick.
- Lake and stream providers are now attached to the shared WaterField at the
  moment that field is first created, before WorldLayout/road planning can
  query it. Previously the installation method existed but the normal
  WaterFields creation path did not call it; config defaults alone therefore
  did not guarantee that the planner's water masks contained lakes.
- Default prerequisites in config.yml are on: cityRings.enabled=true,
  forest.generate=true, and river.enabled=true. The command reports if any
  prerequisite is disabled.

## Separate named massif search

- `/massif` reports the nearest separately planned named massif.
- `/massif tp` teleports to a safe landing on its slope.
- This search uses the named massif feature planner directly and is not part
  of `/metrobiome tp mountains`.
- `/metrobiome tp mountains` remains the broad wilderness mountain biome only.

## Implementation notes

- Search inspects complete feature-cell rings and one ring beyond the first
  result to choose the nearest candidate.
- No terrain-generation or post-generation repair pass was added.
- Lake and massif searches run asynchronously; only safe destination checks
  and player teleportation run on the server thread.
- Built on R14.38; Mother Willow remains unchanged.
