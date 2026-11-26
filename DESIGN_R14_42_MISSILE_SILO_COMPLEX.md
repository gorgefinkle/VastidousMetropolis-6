# R14.42 — Missile Silo Complex: Design Specification

Status: design baseline for implementation and audit. This is a procedural FARM feature; no external resource pack or client-side asset is required.

## User requirements carried into the design

- Replace the plain guard-tower treatment with a secure perimeter; a barbed-wire-style fence is preferred over a guard tower.
- Include a real access ladder, secured behind a locked iron door. The ladder is a deliberate access feature, not an ornamental ladder that terminates underneath a solid cap.
- The highest visible silo opening uses iron-bar grating, **not iron trapdoors**.
- Dress the interiors: rooms, equipment, bunks, storage, controls, utility infrastructure, and abandoned details.
- Randomly select between a conventional missile installation, an abandoned test facility with laboratories, and a retro-futurist underground vault homage. The player should not know which type they have found until entering.
- No guard tower is added to any variant.

## Reference-derived architecture

The conventional layout uses the real-world Titan II Launch Complex as an architectural reference, not as a block-for-block recreation. The reference complex was not merely a shaft: it had a separate access portal, blast-lock rooms, a three-level launch-control center, connecting cableways, and an eight-level equipment area around the launch duct. Its control center included crew quarters, a launch-control room, communications, power distribution, and emergency supplies. The missile silo had a separate equipment annulus around the launch duct, with ladders/platforms at multiple levels.

Sources:
- Titan Missile Museum, interactive map of Launch Complex 571-7: https://titanmissilemuseum.org/museum/map/
- National Park Service, Titan II ICBM Missile Site registration form: https://npgallery.nps.gov/GetAsset/7a798b34-5bc3-4c2b-883f-922df56fe021/
- Titan Missile Museum, underground tour / layout: https://titanmissilemuseum.org/tours/

## Site footprint and ownership

- Expand the reserved parcel from 3×3 to 6×6 farm chunks (96×96 blocks). The larger reservation is necessary for the silo, separate command/access module, connecting service corridors, and a complete security perimeter.
- Keep the entire installation inside that reserved rectangle. The planner must reserve the full footprint before field rectangles are assigned.
- Preserve the existing 4% per-farm-cell occurrence roll and the independent `FarmLayout.extra` random stream. The new feature receives its variant from that existing stream.
- Require one shared `groundY` across all 36 reserved chunks, as the planner already does for other FarmFeatures. Do not flatten, reclaim, or patch neighbouring terrain.
- The geometry paints only the current chunk through `FarmPainter`; no neighbour chunk loads, delayed repair pass, or post-generation decoration step is introduced.

## Shared physical architecture

### Surface
- Circular reinforced launch apron and central steel grating over the shaft.
- Low-profile reinforced access blockhouse over the separate control-center portal; no elevated lookout, balcony, watch platform, or guard tower.
- Square perimeter security fence with iron-bar mesh, reinforced posts, barbed-wire-style upper detail, a closed vehicle gate, and a gravel access spur.
- Four compact floodlight/sensor masts are allowed; each is a light pole only, not a habitable tower.
- Surface vents, cable junctions, antenna/sensor equipment, and a clear service yard.

### Missile silo
- Retain the existing 41-block-diameter reinforced shaft, 82-block depth, central missile, launch table, eight-level equipment gallery, and distributed supporting piers.
- Add eight distinct annular service catwalk levels around the launch duct, each with a consistent floor, rail edge, ladder access, equipment cluster, and lighting.
- Preserve a continuous central launch duct and visible missile. Platforms and equipment must never fill the rocket clearance envelope.
- Keep the original launch table and missile dimensions unless an audit identifies a collision.

### Access and control center
- Add a separate underground four-level control/crew module beside the launch shaft.
- Connect the module to the silo through short, reinforced cable/service corridors at planned levels.
- Include a two-door blast-lock sequence on the main connecting route.
- The surface portal has a closed iron entry door with no exposed button/lever/power source. The vertical ladder is caged and continuous to the underground module; it must not terminate below a sealed ceiling.
- Each underground floor has an independently readable purpose and a dressed interior.

## Variant identities

### 1. Conventional military silo
- Weathered gray reinforced concrete, steel, iron grating, subdued warning-yellow accents, cable trays and pipework.
- Crew quarters and mess on the lowest control-center floor; launch consoles and monitoring equipment on the command floor; communications, batteries, generator and emergency stores on the utility floor.
- Maintenance tools, pumps, storage, gauges, and access platforms around the launch duct.

### 2. Abandoned test facility
- White/quartz and pale concrete surfaces with yellow hazard bands, extensive glass observation panels, instrument benches, sample cabinets, tanks, and laboratory equipment.
- At least one unmistakable laboratory room with multiple workstations; another instrumentation/control room; a decontamination or medical room.
- External sensor poles, test cable runs, additional vents, and a restrained test-pad treatment.
- The test layout must read as a research installation, not merely the military rooms recoloured white.

### 3. Retro-futurist vault-style installation
- Distinct blue/yellow visual language, thick blast-door framing, rounded/segmented interior cues, and warmer residential lighting.
- Crew/resident bunks, mess hall, clinic, food/water storage, utility/power room, and a command room.
- A large, unmistakable vault-like inner blast door and a lived-in but abandoned domestic interior.
- Use generic retro-futurist design cues only; do not copy proprietary logos, named factions, vault numbers, or exact branded signage.

## Material and interaction rules

- Use the existing 1.12 legacy block ID/data adapter through `FarmPainter` so the structures remain compatible with the plugin's current palette.
- Use iron bars for the silo's top grating. No iron trapdoor block ID 167 is permitted anywhere in the silo feature.
- The surface access iron door is closed and unpowered. Do not place an external button, lever, pressure plate, or powered circuit that makes the locked entrance immediately openable.
- Keep doors, ladders, furniture, chests, and machinery within the site bounds and build-height limits.
- Avoid fake support: every room floor is backed by walls or support piers; every catwalk has radial supports; the portal and tunnel have continuous floor/roof/walls.
- Keep loot placements optional through the existing `FarmPainter.chest` rules. No custom resource pack is introduced.

## Required audit gates

1. **Planner ownership:** the full 6×6 footprint is tested and marked used before fields; no field/farmstead overlap; no cross-height or out-of-region placements.
2. **Determinism:** same seed/cell/variant produces byte-identical block operations regardless of chunk paint order.
3. **Chunk clipping:** painting every chunk separately produces the same world-coordinate block map as one full geometry render; no block escapes the reserved footprint.
4. **Top grating:** iron bars present over the central shaft; zero iron trapdoors in every variant.
5. **Locked access:** continuous ladder; closed iron door at surface; no accessible redstone/button/lever to unlock it; cage and landings remain clear.
6. **Blast-lock sequence:** two separated door frames on the access/cable route; corridor floor and headroom remain clear.
7. **Eight silo levels:** every gallery level has floor area, rails, lighting/equipment and ladder adjacency; no platform collides with the rocket envelope.
8. **Control-center floors:** all four levels have complete floors/ceilings, supporting walls/columns, clear circulation and distinct furnishings.
9. **Variant identity:** all three variants have distinct room layouts, material palettes, and characteristic furniture; each is deterministic and can occur.
10. **Security perimeter:** continuous mesh except for the designed gate; barbed upper detail and support posts; no guard tower or habitable lookout structure.
11. **Block validity:** every legacy ID/data pair resolves to a non-air block in the supported mapping; no unknown IDs or trapdoor regression.
12. **Build and packaging:** Java 25 full Maven build, focused geometry/painter QA, embedded version check, complete source ZIP, one JAR, and checksums.

## Explicit non-goals

- No post-generation cleanup, repair, or deferred structure decoration.
- No client-installed resource pack.
- No plain guard tower.
- No iron-trapdoor cap.
- No single hollow cylinder with empty floors presented as a finished interior.

## Release status

R14.42 remains a development candidate until the procedural geometry and all static audits pass. Automated geometric correctness is not the same as an in-game visual approval.