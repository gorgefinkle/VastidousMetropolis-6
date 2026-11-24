# R14.24 — Bloomlands Grounding and Generation Stability

## Bloomlands negative-height vegetation

- Replace the old hard-coded Y=2 vegetation cutoff with a shared world-height policy. Bloomlands trunks, mushroom stalks, fungal caps, ordinary woodland vegetation and Mother Willow geometry can now occupy legal negative-Y terrain.
- Root each Bloomlands understory basal trunk segment against the actual local ground height beneath its footprint, rather than relying only on the tree's centre-column anchor.
- Build each mushroom stalk column from its own terrain surface to the intended cap underside. The cap-bottom clipping rule remains in place.
- Keep solid terrain protected: the placement gate still only replaces air and approved natural ground cover.
- Extend the ordinary ForestTrees block writer to respect the world's minimum height instead of silently discarding every log, leaf or vine below Y=1.

## Mother Willow

- Change the highest reiterated leader's initial take-off so it blends the bole tangent with an outward azimuth. The leader now emerges from the side of the trunk instead of travelling invisibly up its centreline.
- Add an overlapping wood collar at the top leader junction. The approved lateral root system is unchanged.
- Update the junction probe to require both bole continuity and visible outward emergence.

## Deferred population and chunk loading

- Remove the synchronous `World.getChunkAt(x,z)` from the disk-deferred population drain.
- Stage previously generated deferred chunks with Paper's `getChunkAtAsync(x,z,false,false)`, then return to the server thread to run the population callback.
- Keep the durable queue's single active job until asynchronous staging and population complete. Failed or unavailable disk loads are retried without acknowledging the durable marker.
- Add a static regression check that rejects reintroduction of the synchronous deferred-load call.

The supplied watchdog excerpt shows a player tick waiting for a chunk stuck at `initialize_light`. This change removes a confirmed synchronous load from Metropolis's deferred drain, which was a dangerous re-entrant path; it does not by itself prove that this exact call caused the supplied watchdog. The live server test must confirm queue stability and chunk completion.

## FARM underground additions

- Add occasional rural farmhouses. A separate deterministic roll gives some houses an earth-sheltered, hobbit-style root cellar with storage shelves and a concealed pantry hatch.
- Add three structurally distinct hidden survival bunker layouts: a compact refuge, a two-room shelter, and a deeper reinforced multi-room shelter. Their entrances are concealed beneath hay and surface soil.
- Add a rare, sealed missile silo with a roughly 13-block internal shaft, about 34 blocks deep, a missile still inside, a heavy closed hatch and capped service vents. It is not an open pit.
- Underground farm features can now paint down to the world's minimum build height.
- The new underground sites reserve their own farm-grid footprints before field plots are planned, so fields do not paint over their structures.

## QA and validation

The iteration adds/updates:
- `QA/R14_21/run_bloom_understory_probe.sh`
- `QA/R14_20/run_mushroom_ground_contact_probe.sh`
- `QA/R14_21/run_mother_willow_junction_probe.sh`
- `QA/R14_24/run_deferred_population_async_probe.sh`
- Existing R14.23 farm access and inherited world-generation probes.

CI compilation and probes are required before a test JAR is considered ready. A Paper 26.2 live-world run and visual inspection are still required; CI cannot validate in-game appearance or real watchdog behavior.
