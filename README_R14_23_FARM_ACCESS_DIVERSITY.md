# R14.23 — FARM access and diversity

## Purpose

Rebalance FARM districts away from repeated warehouse / round-barn silhouettes and make every placed farm site reachable from a real paved street by a dirt access road.

This release retains the existing building families and their assets. It changes selection, site composition, and road ownership rather than adding a post-generation repair pass.

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

The road-line value is now a candidate-availability setting, not a road-generation probability. A boundary is only painted where a placed site has recorded an access gate and the line reaches an actual paved street.

Only exact historical shipped defaults are migrated. Other configured values remain intact.

## Road model

- Farmsteads and modern compounds are not placed unless one of their road-facing gates reaches a paved street through a valid farm boundary spine.
- Standard greenhouse plots reserve a four-wide site entry lane before buildings are placed.
- Commercial glasshouses and polytunnel banks extend their existing service/head lane to the paved-road-connected farm spine.
- Each site records a deterministic road-access gate on the boundary line.
- FarmRoads gathers gates from both cells touching a boundary and paints only the interval between each gate and its nearest paved-street endpoint.
- Multiple sites can share an interval; unused line segments, dead ends, and empty grid corridors remain unpaved.
- Field plots remain separate and their crop interiors are not used as road corridors.

The road geometry remains chunk-local and deterministic. The FarmRoads route cache is cleared with the FarmLayout cache when settings are reloaded.

## Validation

`QA/R14_23/run_farm_road_route_policy_probe.sh` verifies nearest-street selection, negative-coordinate handling, bounded site-to-street intervals, shared trunk intervals, and the absence of unused road tails.

The R14.23 workflow also runs the full inherited Maven/Java 25 regression suite and packages exactly one current JAR plus the complete source archive.

## Important boundary condition

A site is omitted when no candidate boundary route reaches an actual paved street within the existing 66-chunk search limit. This is intentional: an inaccessible farm building is not generated and then patched later. It is not treated as a connected site.
