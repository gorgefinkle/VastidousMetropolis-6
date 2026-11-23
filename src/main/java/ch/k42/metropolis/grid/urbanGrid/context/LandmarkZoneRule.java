package ch.k42.metropolis.grid.urbanGrid.context;

import ch.k42.metropolis.grid.urbanGrid.clipboard.Clipboard;
import ch.k42.metropolis.grid.urbanGrid.config.SchematicConfig;
import ch.k42.metropolis.grid.urbanGrid.enums.ContextType;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Which zone a landmark (or creek-site building) may stand in, when
 * generator.landmarkZoneMatch is on (PluginConfig.isLandmarkZoneMatchEnabled()).
 *
 * The explicit LANDMARK pool is queried regardless of a district's own zone
 * (see ContextType.LANDMARK), so a schematic whose CONFIG.json also lists
 * ordinary zones -- e.g. ["HIGHRISE", "LANDMARK"] -- could otherwise be pasted
 * into any district at all. With the toggle on, those listed zones are the
 * zones it is allowed in:
 *
 *   - the zones a schematic names are its CONFIG.json context array minus the
 *     bookkeeping tags (LANDMARK, UNDEFINED, DENSERESIDENTIAL_PRIMARY);
 *   - a schematic that names no zone at all (["LANDMARK"] only) is
 *     unrestricted, since there is no "correct" district to compare against;
 *   - a landmark is bigger than most bands are wide, so it is not asked to fit
 *     inside one. Instead its own centre zone must be one it names, and at
 *     least MIN_SIDES of its four sides must be bordered by zones it names
 *     (allowsFootprint()): a side counts when most of the neighbour zones
 *     sampled one road beyond it are zones it names. List every zone a
 *     landmark may touch -- e.g. a store meant for lowrise bands that may
 *     back onto midrise lists both -- or the side facing the other band will
 *     not count;
 *   - the neighbouring-sides requirement is relaxed by default ("districtTrueLax", also spelled "districttruelax", which a
 *     CONFIG.json may set to false to opt out): a landmark needs LAX_SIDES (two) of its four sides bordered by zones it names,
 *     and a landmark that opted out needs MIN_SIDES (three). Its own centre zone must be one it names either way (see
 *     isNeighbourLax());
 *   - DENSERESIDENTIAL is the suburban variant of RESIDENTIAL on the same
 *     ladder rung, so a district zoned DENSERESIDENTIAL also accepts a
 *     schematic that names RESIDENTIAL;
 *   - the declared zones are read from the schematic's own config, not from
 *     the pools it was stored in: a schematic with a directional cutout is
 *     stored under LANDMARK only (see ClipboardDAO.storeClipboard()), but its
 *     declared zones are still in its config.
 */
public final class LandmarkZoneRule {

   private LandmarkZoneRule() {
   }

   /** The zones this schematic's own config names. Empty when it names none (or has no config). */
   public static List<ContextType> declaredZones(Clipboard clip) {
      List<ContextType> zones = new ArrayList<ContextType>();
      if (clip == null) {
         return zones;
      }
      SchematicConfig config = clip.getConfig();
      if (config == null) {
         return zones;
      }
      List<ContextType> declared;
      try {
         declared = config.getContext();
      } catch (NullPointerException noContextArray) {
         return zones;
      }
      if (declared == null) {
         return zones;
      }
      for (ContextType type : declared) {
         // Gson turns an unknown context string into null; skip those too.
         if (type == null || type == ContextType.LANDMARK || type == ContextType.UNDEFINED
                 || type == ContextType.DENSERESIDENTIAL_PRIMARY) {
            continue;
         }
         if (!zones.contains(type)) {
            zones.add(type);
         }
      }
      return zones;
   }

   /** True when the schematic names at least one zone, i.e. there is a "correct district" to enforce. */
   public static boolean restricts(Clipboard clip) {
      return !declaredZones(clip).isEmpty();
   }

   /**
    * The zone a district hosting this schematic must be given, or null when
    * the schematic is unrestricted or cannot stand in {@code expected}. Used
    * both as the yes/no check (see allows()) and as the context to pin the
    * district to so DistrictContextAssigner agrees with it.
    */
   public static ContextType pinFor(Clipboard clip, ContextType expected) {
      if (expected == null || expected == ContextType.UNDEFINED) {
         return null;
      }
      List<ContextType> declared = declaredZones(clip);
      if (declared.contains(expected)) {
         return expected;
      }
      if (expected == ContextType.DENSERESIDENTIAL && declared.contains(ContextType.RESIDENTIAL)) {
         return ContextType.RESIDENTIAL;
      }
      return null;
   }

   /** A landmark that opted out of districtTrueLax must be bordered by zones it names on at least this many of its four sides. */
   public static final int MIN_SIDES = 3;
   /** The same for every other landmark, i.e. by default: districtTrueLax is on unless its CONFIG.json sets it to false. */
   public static final int LAX_SIDES = 2;
   /** Chunks from a footprint edge to the neighbour sampled beyond it: a 1-chunk road, then the district. */
   public static final int NEIGHBOUR_OFFSET = 2;
   /** Chunks between neighbour samples along a side. */
   public static final int SAMPLE_STEP = 3;

   /**
    * The zones around one landmark footprint: the zone at its centre, and for each
    * of its four sides the zones sampled just beyond it (where the neighbouring
    * districts sit). Sides are ordered low-Z, high-X, high-Z, low-X.
    */
   public static final class Footprint {
      public final ContextType centre;
      public final List<List<ContextType>> sides;

      public Footprint(ContextType centre, List<List<ContextType>> sides) {
         this.centre = centre;
         this.sides = sides;
      }

      @Override
      public String toString() {
         String[] names = {"lowZ", "highX", "highZ", "lowX"};
         StringBuilder sb = new StringBuilder("centre=").append(this.centre);
         for (int i = 0; i < this.sides.size(); i++) {
            Map<ContextType, Integer> counts = new LinkedHashMap<ContextType, Integer>();
            for (ContextType zone : this.sides.get(i)) {
               Integer n = counts.get(zone);
               counts.put(zone, n == null ? 1 : n + 1);
            }
            sb.append(' ').append(names[i]).append('=').append(counts);
         }
         return sb.toString();
      }
   }

   /**
    * How many of the four sides are bordered by zones this schematic names. A side
    * counts when MORE than half of its neighbour samples are such zones, so a side
    * running along a band edge counts when most of it is still against its own band.
    */
   public static int sidesMatching(Clipboard clip, Footprint footprint) {
      int matching = 0;
      for (List<ContextType> side : footprint.sides) {
         if (side.isEmpty()) {
            continue;
         }
         int ok = 0;
         for (ContextType zone : side) {
            if (allows(clip, zone)) {
               ok++;
            }
         }
         if (ok * 2 > side.size()) {
            matching++;
         }
      }
      return matching;
   }

   /** True unless this schematic's config sets districtTrueLax false: it needs LAX_SIDES sides next to its zones, not MIN_SIDES. */
   public static boolean isNeighbourLax(Clipboard clip) {
      if (clip == null) {
         return false;
      }
      SchematicConfig config = clip.getConfig();
      return config != null && config.isDistrictTrueLax();
   }

   /** How many of its four sides must border zones this schematic names: LAX_SIDES for a districtTrueLax one, otherwise MIN_SIDES. */
   public static int sidesRequired(Clipboard clip) {
      return isNeighbourLax(clip) ? LAX_SIDES : MIN_SIDES;
   }

   /**
    * May this schematic stand at this footprint? Unrestricted schematics always
    * may. Otherwise its centre zone must be one it names (the district is pinned
    * to it) and at least sidesRequired() sides -- MIN_SIDES, or LAX_SIDES if it is
    * districtTrueLax -- must be bordered by zones it names. The footprint itself
    * is free to reach across band edges.
    */
   public static boolean allowsFootprint(Clipboard clip, Footprint footprint) {
      if (footprint == null || !restricts(clip)) {
         return true;
      }
      if (footprint.centre != null && footprint.centre != ContextType.UNDEFINED
              && pinFor(clip, footprint.centre) == null) {
         return false;
      }
      return sidesMatching(clip, footprint) >= sidesRequired(clip);
   }

   /**
    * May this schematic stand in a district of the {@code expected} zone?
    * Unrestricted schematics always may; so does anything when the zone
    * isn't known (null / UNDEFINED), because there is nothing to compare.
    */
   public static boolean allows(Clipboard clip, ContextType expected) {
      if (expected == null || expected == ContextType.UNDEFINED) {
         return true;
      }
      if (!restricts(clip)) {
         return true;
      }
      return pinFor(clip, expected) != null;
   }
}
