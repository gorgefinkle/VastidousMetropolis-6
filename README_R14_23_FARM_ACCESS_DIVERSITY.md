# R14.23 — FARM diversity and demand-driven access roads

## Purpose

Rebalance FARM regions away from repeated warehouse / round-barn silhouettes and make every placed farm site reachable from a genuine paved street by a dirt access road.

The changes operate inside the existing planner and painter. There is no post-generation cleanup, patching or road-repair pass.

## FARM balance defaults

| Setting | Previous shipped default | R14.23 |
|---|---:|---:|
| Farmstead share | 80% | 65% |
| Pasture share | 20% | 28% |
| Modern compound share | 22% | 15% |
| Greenhouse/nursery share | 30% | 38% |
| Orchard share | 16% | 24% |
| Round barn share | 21% | 8% |
| Outbuilding chance | 78% | 75% |
| Candidate road-line share | 70% | 100% |

Only exact historical shipped defaults are migrated in an existing config. Other server-tuned values are retained.

The road-line setting now controls whether a boundary is available as a candidate access spine. It does not create a road by itself. The shipped value is 100% so a site can use any boundary with a real street connection; actual dirt blocks still require a site request.

## Fields, farm sites and road ownership

- Fields are planned as separate rectangular plots and retain their own boundaries and fences.
- A farmstead or modern compound is only placed after one of its road-facing gates is confirmed to connect through a farm boundary to an ordinary paved street.
- A nursery uses the same rule. Its driveway is reserved before the greenhouse and optional service shed are placed.
- Standard gabled greenhouse access meets the actual open gable entrance. East- and south-facing sites use mirrored geometry.
- Commercial glasshouses and polytunnel fields extend their service/head lane to a connected paved-road spine.
- Each site records one access gate on its chosen farm-cell boundary.
- The two cells touching a shared boundary contribute their site requests to one dirt-road spine.
- FarmRoads paints only the minimal route interval from each recorded gate to its nearest paved street. Overlapping routes naturally share a trunk.
- Empty boundary sections, parallel grid strips, unused tails, loops and disconnected stubs remain meadow.
- Road corridors follow field edges and site aprons; they do not cut across crop interiors.
- If a site cannot find a genuine street connection within the existing bounded search, it is not placed. It is never spawned disconnected and patched afterward.

The road-route cache is deterministic, bounded and cleared together with the FARM layout cache when configuration is reloaded.

## Industrial operation variants

Modern compounds select one primary operation family rather than automatically stacking the same combination of silos, bunkers, sheds and elevator/depot structures.

The existing elevator and equipment depot remain, with reduced selection weight. The same industrial facility family now also supports:

- Produce grading, packing and cold storage
- Grain drying and cleaning
- Irrigation pumping and filtration
- Poultry production
- Livestock sorting, weighing and loading
- Compost and anaerobic biogas recovery

The main barn remains the compound's primary livestock structure. Bunkers, silos, tool sheds and vehicles are selected according to the chosen operation, with lower ancillary clutter.

## Greenhouse variants

Existing GABLED, BAYS and HOOP families remain the underlying categories. Their new internal purposes are variants, not new independent categories:

- Standard growing house
- Propagation house with raised seedling benches
- Research house with crop-trial beds and an enclosed rear laboratory
- Expanded four- or five-bay commercial glasshouse
- Collapsed commercial wing, confined to one bay/section while the other bays remain standing
- Existing polytunnel fields and row greenhouses remain part of the mix

## Survival material budget

- The new industrial facilities use common stone, stone brick, terracotta, gravel, ordinary timber, glass and fences.
- No iron blocks are used by the new industrial facility styles.
- The modern barn's continuous iron-block ridge cap has been replaced with ordinary stone brick.
- Greenhouse support frames use common white terracotta instead of quartz; polytunnel hoops use common fence framing with sparse iron-bar reinforcement.
- Existing iron bars remain only as small machine railings, ventilation hoops and livestock barriers.
- The only remaining iron-block accents are the two small gearbox/hub details in the optional outbuilding windmill.

## Validation

`QA/R14_23/run_farm_road_route_policy_probe.sh` checks nearest-street selection, negative coordinates, bounded gate-to-street intervals, shared trunk behavior, unrequested boundaries, sites outside the connected farm run and the absence of unused road tails.

The R14.23 GitHub Actions workflow runs Java 25 Maven verification and the inherited world-generation regression suite, then packages exactly one current JAR and the complete source archive.

The route policy probe validates route interval rules. A fresh generated FARM region still needs visual confirmation before merging or treating the changes as field-certified.
