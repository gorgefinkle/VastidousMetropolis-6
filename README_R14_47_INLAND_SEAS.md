# R14.47 — Inland Seas

The former named lakes were compact radial bowls with a hard water cutoff. R14.47 replaces that geometry with a deterministic signed shoreline field: low-frequency domain warp, broad bays and headlands, restrained coastline detail, and a conservative outer envelope.

The underwater basin is deliberately **shelf-free**. Its floor deepens continuously from the shallow shoreline to a broad central basin; there are no separate shelf bands, depth plateaus, or abrupt ledges. Low-frequency bed variation is intentionally mild.

Each lake has a 24–31 chunk major-axis radius and an elongated 0.70–0.90 axis ratio. The natural beach has a finite 64-block footprint, grades smoothly into the existing terrain, and does not repaint terrestrial biomes. Water uses the freshwater RIVER biome; no ocean biome is assigned to lakes.

Outlet routing starts from a point verified to be inside the irregular open-water contour. The lake teleport command also samples the actual signed-distance shore rather than assuming the old ellipse edge.

Validation checks deep central water, increasing shelf-free bathymetry, irregular coastline variation, finite beach ownership, deterministic repeatability, and freshwater biome painting.
