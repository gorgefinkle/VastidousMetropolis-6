package ch.k42.metropolis.grid.urbanGrid.enums;

public enum ContextType {
   STREET("street"),
   HIGHRISE("highrise"),
   MIDRISE("midrise"),
   LOWRISE("lowrise"),
   RESIDENTIAL("residential"),
   // Suburban-sprawl variant of RESIDENTIAL -- see
   // PluginConfig.isDenseResidentialEnabled() for the toggle that decides
   // whether this context can ever be selected at all, and District's
   // CLONERADIUS resolution for why it's exempt from the ordinary
   // cloneRadius/cloneRadiusSoftLimit that govern every other context.
   DENSERESIDENTIAL("denseresidential"),
   // Internal-only bookkeeping context -- never assigned to a district,
   // never listed in contexts.json's zone tree, and getByString() can
   // still resolve it if a CONFIG.json literally types this string, but
   // it's not meant to be authored directly: ClipboardDAO.storeClipboard()
   // is what actually populates it, mirroring any schematic whose OWN
   // context array explicitly names DENSERESIDENTIAL. See
   // District.placeRandomBuild()'s primary-tier lookup for how it's
   // consumed: tried before, and exempt from every exclusion rule that
   // governs, the much larger auto-derived DENSERESIDENTIAL pool.
   DENSERESIDENTIAL_PRIMARY("denseresidentialprimary"),
   INDUSTRIAL("industrial"),
   FARM("farm"),
   // Countryside district, the far end of the neighbor ladder next to FARM --
   // see DistrictContextAssigner. Assigned like any other zoning context (its
   // schematic pool is filled later; an empty pool falls back to filler). It
   // is only ever produced while generator.districtNeighborRules is on;
   // ContextProviderSimplex strips FOREST and FARM from the plain noise
   // lookup otherwise.
   FOREST("forest"),
   PARK("park"),
   HIGHWAY("highway"),
   // Explicit, hand-authored landmark pool -- same "deliberate opt-in"
   // precedent as DENSERESIDENTIAL_PRIMARY just above, but simpler: no
   // auto-mirroring step is needed here at all. A schematic lands under
   // this context purely because its own CONFIG.json's "context" array
   // literally lists "landmark", going through ClipboardDAO.
   // storeClipboard()'s ordinary per-context loop like any other tag --
   // there's nothing else to opt into. UrbanGrid.tryPlaceLandmark() tries
   // this pool FIRST, regardless of the district's own zoning context (an
   // explicit landmark is meant to be eligible for any landmark district,
   // not only one that happens to share its author's other context tags),
   // before falling back to its pre-existing behaviour: whatever's tagged
   // with the district's own zoning context and simply happens to be
   // large enough to qualify, with no explicit opt-in at all.
   //
   // Never assigned to a district -- like DENSERESIDENTIAL_PRIMARY, never
   // produced by ContextProviderSimplex's noise-based zoning and never
   // listed in contexts.json's zone tree -- which is what keeps a
   // landmark-only schematic (one whose context array names LANDMARK and
   // nothing else) out of ordinary District.placeRandomBuild() calls with
   // no separate exclusion check needed anywhere: that method only ever
   // queries by a district's own assigned zoning context, and this value
   // is never one. A schematic can still ALSO carry an ordinary context
   // (e.g. ["HIGHRISE", "LANDMARK"]) to remain eligible for both --
   // that's a deliberate authoring choice, not something guarded against.
   LANDMARK("landmark"),
   // Water itself -- a RiverParcel's own context, distinct from the
   // zoning contexts above (which describe LAND use). Never produced by
   // ContextProviderSimplex's noise-based zoning; only ever assigned
   // directly by UrbanGrid when it places a RiverParcel. Included in the
   // enum (rather than leaving river parcels' context null) so existing
   // code that switches/compares on ContextType -- and any future
   // context-dependent riverbank dressing -- has a real value to key off.
   RIVER("river"),
   // Same role as RIVER above, for the ocean feature -- see OceanParcel.
   // Kept as its own value (not reusing RIVER) so future context-dependent
   // dressing can tell the two apart, and so nothing that switches on
   // ContextType.RIVER specifically (assuming a river's cross-section)
   // silently also matches ocean cells.
   OCEAN("ocean"),
   UNDEFINED("undefined");

   public String string;

   ContextType(String str) {
      this.string = str;
   }

   /**
    * The "morphomos" tag. Districts of a morphomos context hold no schematics
    * and no roads between each other: UrbanGrid.dissolveMorphomosRoads() maps
    * each connected area as one MorphomosRegion once every district in the
    * tile has been zoned. Highways are never touched. Currently FOREST and
    * FARM.
    *
    * Morphomos is per-context: a region only ever joins districts of the
    * SAME context. FARM merges with other FARM and FOREST with other FOREST,
    * but a farm/forest border keeps its road and the two never share a
    * region (see UrbanGrid.dissolveMorphomosRoads()). FOREST additionally
    * reclaims the roads that run between it and ocean, rivers and creeks.
    */
   public boolean isMorphomos() {
      return this == FOREST || this == FARM;
   }

   /**
    * Natural districts share Morphomos boundary behavior without sharing a
    * terrain painter or a MorphomosRegion. Water parcels remain owned by their
    * river/ocean painters; creek and unzoned wilderness cells are classified
    * by WorldLayout as ZONE/NATURAL in PlanRegions.
    */
   public boolean isNaturalBoundaryFamily() {
      return this == FOREST || this == RIVER || this == OCEAN;
   }

   public static ContextType getByString(String string) {
      for (ContextType d : values()) {
         if (d.string.equals(string)) {
            return d;
         }
      }

      return null;
   }
}