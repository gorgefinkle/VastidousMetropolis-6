package ch.k42.metropolis.grid.urbanGrid.districts;

import ch.k42.metropolis.grid.urbanGrid.UrbanGrid;
import ch.k42.metropolis.grid.urbanGrid.clipboard.Clipboard;
import ch.k42.metropolis.grid.urbanGrid.clipboard.ClipboardProvider;
import ch.k42.metropolis.grid.urbanGrid.context.ContextProvider;
import ch.k42.metropolis.grid.urbanGrid.context.LandmarkZoneRule;
import ch.k42.metropolis.grid.urbanGrid.enums.ContextType;
import ch.k42.metropolis.grid.urbanGrid.enums.Direction;
import ch.k42.metropolis.grid.urbanGrid.enums.SchematicType;
import ch.k42.metropolis.grid.urbanGrid.layout.WorldLayout;
import ch.k42.metropolis.grid.urbanGrid.parcel.ClipboardParcel;
import ch.k42.metropolis.minions.Cartesian2D;
import ch.k42.metropolis.minions.GridRandom;
import ch.k42.metropolis.minions.Minions;
import ch.k42.metropolis.plugin.PluginConfig;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Locale;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Which landmark stands where, decided from the seed alone.
 *
 * A landmark is a schematic bigger than a city block (see generator.enableLandmarkDistricts). It used to be carved out of a
 * region while a tile was being cut, with a budget per tile and a placement log to keep the same one from standing near itself:
 * all of it depending on which tile was generated first. In the plan every block is decided once, from the plan, so a landmark
 * is too. The world is cut into cells of WorldLayout.LandmarkPlanner.CELL chunks (the size of a tile); each cell holds
 * generator.landmarkMaxPerGrid sites. A site draws its attempt (generator.landmarkAttemptChancePercent), its anchor and its
 * schematic from hashes of (seed, cell, site) and the zones around the anchor, so it is the same whichever tile asks. The layout
 * reserves the footprint a block of its own near the anchor (WorldLayout.reserveLandmarks), and BlockPlans places the schematic.
 *
 * The same rules as before pick the schematic: the explicit landmark pool first, then whatever of the zone's own is large enough;
 * never one that needs a creek or a river; with generator.landmarkZoneMatch only one whose own zones fit the footprint; and
 * never one that stands in a site of a lower rank within generator.landmarkCloneRadius cells (every two sites in range are ordered
 * one way or the other, as BlockPlans orders blocks, so the avoidance covers every pair whatever the order).
 */
public final class LandmarkPlans implements WorldLayout.LandmarkPlanner {
   /** The widest a landmark may be: the layout looks for room within a limited reach of the anchor. */
   private static final int MAX_SPAN = 48;
   /**
    * How many anchors a site tries before it gives up. The anchor is not fixed up front any more: it is searched for, so the
    * landmark stands where its own zones are (a ring band it names), clear of the landmarks that rank below it.
    */
   private static final int ANCHOR_TRIES = 16;
   private static final Object NONE = new Object();

   /** What stands on a landmark site. */
   public static final class Choice {
      public final Clipboard clip;
      public final Direction direction;
      public final ContextType context;

      Choice(Clipboard clip, Direction direction, ContextType context) {
         this.clip = clip;
         this.direction = direction;
         this.context = context;
      }
   }

   private final long seed;
   private final ClipboardProvider clips;
   private final ContextProvider contexts;
   private final int blockSize;
   private final Map<Long, Object> memo = new HashMap<Long, Object>();
   private final Map<String, List<PriorityClip>> priorityCache = new HashMap<>();
   private final Set<String> missingPriorityWarned = new HashSet<>();

   /** Preserve the reference's unrotated size per getAll(direction) entry. */
   private static final class PriorityClip {
      final Clipboard clip;
      final Direction direction;
      final Cartesian2D size;
      PriorityClip(Clipboard clip, Direction direction) {
         this.clip = clip;
         this.direction = direction;
         this.size = new Cartesian2D(Math.max(1, clip.getSize().X >> 4),
               Math.max(1, clip.getSize().Z >> 4));
      }
   }

   private static String normalizeGroup(String group) {
      return group == null ? "" : group.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]", "");
   }

   private List<PriorityClip> priorityClips(String group) {
      List<PriorityClip> old = priorityCache.get(group);
      if (old != null) return old;
      List<PriorityClip> list = new ArrayList<>();
      Set<String> seen = new HashSet<>();
      String wanted = normalizeGroup(group);
      for (ContextType context : ContextType.values()) {
         for (Direction direction : Direction.getDirections()) {
            for (Clipboard clip : clips.getAll(context, SchematicType.BUILD, direction)) {
               String buildName = normalizeGroup(clip.getConfig() == null ? null : clip.getConfig().getBuildName());
               if (!buildName.equals(wanted) && !buildName.contains(wanted)) continue;
               PriorityClip candidate = new PriorityClip(clip, direction);
               if (candidate.size.X > MAX_SPAN || candidate.size.Y > MAX_SPAN) continue;
               if (candidate.size.X < blockSize && candidate.size.Y < blockSize) continue;
               String key = System.identityHashCode(clip) + direction.name() + candidate.size.X + candidate.size.Y;
               if (seen.add(key)) list.add(candidate);
            }
         }
      }
      priorityCache.put(group, list);
      if (list.isEmpty() && missingPriorityWarned.add(group)) {
         Minions.w("PRIORITY LANDMARK '%s' has no matching installed large BUILD schematic; cannot reserve its road-wrapped footprint until the schematic is imported", group);
      }
      return list;
   }

   private List<String> priorityNames() {
      List<String> configured = PluginConfig.getPriorityLandmarkGroups();
      if (configured.size() == 2 && "CentralPark".equalsIgnoreCase(configured.get(0))
              && "MegaMall".equalsIgnoreCase(configured.get(1))) {
         return Arrays.asList("CentralPark", "MegaMall", "ikea12x15noscaf");
      }
      return configured;
   }

   /** The original planner tests priority schematics and anchors in a deterministic nested order. */
   private List<WorldLayout.Landmark> prioritySites(int cx, int cz, int k, String name, int limit) {
      List<WorldLayout.Landmark> out = new ArrayList<>();
      List<PriorityClip> candidates = priorityClips(name);
      if (candidates.isEmpty()) return out;
      int spacing = Math.max(0, PluginConfig.getLandmarkMinSpacing());
      boolean zoneMatch = PluginConfig.isLandmarkZoneMatchEnabled();
      Set<String> used = groupsNearby(cx, cz, k);
      List<WorldLayout.Landmark> lower = lowerSites(cx, cz, k);
      for (int t = 0; t < ANCHOR_TRIES; t++) {
         int saltX = t == 0 ? 2 : 100 + 2*t;
         int saltZ = t == 0 ? 3 : 101 + 2*t;
         int ax = cx * WorldLayout.LandmarkPlanner.CELL + 8 +
               (int)(unit(hash(cx,cz,k,saltX)) * (WorldLayout.LandmarkPlanner.CELL - 16));
         int az = cz * WorldLayout.LandmarkPlanner.CELL + 8 +
               (int)(unit(hash(cx,cz,k,saltZ)) * (WorldLayout.LandmarkPlanner.CELL - 16));
         for (PriorityClip p : candidates) {
            Cartesian2D size = p.size;
            Cartesian2D base = new Cartesian2D(ax-size.X/2,az-size.Y/2);
            if (tooClose(base.X, base.Y, size.X, size.Y, lower, spacing)) continue;
            if (used.contains(p.clip.getGroupId())) continue;
            if (ClipboardParcel.hasCreekOrigin(p.clip)) continue;
            if (p.clip.getConfig() != null && p.clip.getConfig().isRiverFacing()) continue;
            LandmarkZoneRule.Footprint footprint = UrbanGrid.zonesAround(contexts, base, size);
            ContextType centre = footprint.centre;
            if (PluginConfig.isMorphomosEnabled() && centre != null && centre.isMorphomos()) continue;
            if (zoneMatch && !LandmarkZoneRule.allowsFootprint(p.clip, footprint)) continue;
            ContextType pin = zoneMatch ? LandmarkZoneRule.pinFor(p.clip, centre) : null;
            ContextType context = pin != null ? pin : (centre != null && centre != ContextType.UNDEFINED ? centre : ContextType.LANDMARK);
            Minions.dLandmark("PRIORITY LANDMARK SITE cell=(%d,%d) group='%s' anchor=(%d,%d) size=%dx%d direction=%s; full road-ring required by WorldLayout.landmarkFits",
                  cx,cz,p.clip.getGroupId(),ax,az,size.X,size.Y,p.direction);
            out.add(new WorldLayout.Landmark(ax,az,size.X,size.Y,context,new Choice(p.clip,p.direction,context)));
            if (out.size() >= limit) return out;
         }
      }
      if (out.isEmpty()) Minions.dLandmark("PRIORITY LANDMARK '%s' could not find an urban candidate cell=(%d,%d); no unsafe fallback to unwrapped building", name,cx,cz);
      return out;
   }

   private WorldLayout.Landmark prioritySite(int cx, int cz, int site, String name) {
      List<WorldLayout.Landmark> sites = prioritySites(cx, cz, site, name, 1);
      return sites.isEmpty() ? null : sites.get(0);
   }

   @Override
   public synchronized List<WorldLayout.Landmark> alternativesOf(int cx, int cz, int site) {
      WorldLayout.Landmark original = siteOf(cx, cz, site);
      if (original == null) return Collections.emptyList();
      List<String> names = priorityNames();
      if (site != 0 || names.isEmpty()) return Collections.singletonList(original);
      String group = names.get(Math.floorMod(cx + cz, names.size()));
      if (priorityClips(group).isEmpty()) return Collections.singletonList(original);
      List<WorldLayout.Landmark> options = prioritySites(cx, cz, site, group, 8);
      if (options.isEmpty()) return Collections.singletonList(original);
      options.set(0, original);
      return options;
   }

   public LandmarkPlans(long seed, ClipboardProvider clips, ContextProvider contexts, int blockSize) {
      this.seed = seed;
      this.clips = clips;
      this.contexts = contexts;
      this.blockSize = blockSize;
   }

   /** The placement of the landmark reserved this block, as BlockPlans places any building. */
   static BlockPlans.Placement placementFor(WorldLayout.Landmark lm, WorldLayout.Rect block) {
      Choice c = (Choice) lm.choice;
      return new BlockPlans.Placement(block.minX, block.minZ, block.maxX - block.minX + 1, block.maxZ - block.minZ + 1,
              c.clip, c.context, SchematicType.BUILD, c.direction);
   }

   @Override
   public int sitesPerCell() {
      return PluginConfig.isLandmarkDistrictsEnabled() ? Math.max(0, PluginConfig.getLandmarkMaxPerGrid()) : 0;
   }

   @Override
   public synchronized WorldLayout.Landmark siteOf(int cellX, int cellZ, int site) {
      long key = ((long) (cellX & 0xFFFFFF) << 32) | ((long) (cellZ & 0xFFFFFF) << 8) | (site & 0xFF);
      Object known = this.memo.get(key);
      if (known != null) {
         return known == NONE ? null : (WorldLayout.Landmark) known;
      }
      WorldLayout.Landmark made = this.make(cellX, cellZ, site);
      this.memo.put(key, made == null ? NONE : made);
      return made;
   }

   // ------------------------------------------------------------------ the draw

   private long hash(int cellX, int cellZ, int site, int salt) {
      return GridRandom.generateLocalSeed(GridRandom.generateLocalSeed(this.seed ^ 0x4C414E444D41524BL, cellX, cellZ), site, salt);
   }

   private static double unit(long h) {
      return (h >>> 11) * (1.0 / (1L << 53));
   }

   private WorldLayout.Landmark make(int cx, int cz, int k) {
      List<String> priority = priorityNames();
      if (k == 0 && !priority.isEmpty()) {
         String name = priority.get(Math.floorMod(cx + cz, priority.size()));
         if (!priorityClips(name).isEmpty()) {
            return prioritySite(cx,cz,k,name);
         }
      }
      if (unit(this.hash(cx, cz, k, 1)) * 100.0 >= PluginConfig.getLandmarkAttemptChancePercent()) {
         return null;
      }
      final int cell = WorldLayout.LandmarkPlanner.CELL;
      final Cartesian2D maxFit = new Cartesian2D(MAX_SPAN, MAX_SPAN);
      // The explicit landmark pool does not depend on where the landmark stands, so it is asked once. Only when it is empty
      // does the pool depend on the zone at the anchor (whatever the zone has that is large enough), asked per anchor below.
      final List<Cartesian2D> explicitSizes = this.clips.getLandmarkSizes(this.blockSize, ContextType.LANDMARK, SchematicType.BUILD, maxFit);
      final boolean explicit = !explicitSizes.isEmpty();
      final Set<String> used = this.groupsNearby(cx, cz, k);
      final List<WorldLayout.Landmark> lower = this.lowerSites(cx, cz, k);
      final int spacing = Math.max(0, PluginConfig.getLandmarkMinSpacing());
      final boolean zoneMatch = PluginConfig.isLandmarkZoneMatchEnabled();
      final Direction[] directions = Direction.getDirections();

      // why a try failed, counted over every anchor and size, for the log when the site finds nothing
      int noSizes = 0, countryside = 0, tooClose = 0, noCandidate = 0;

      for (int t = 0; t < ANCHOR_TRIES; t++) {
         // try 0 draws the anchor exactly as before; the rest are further draws from the same cell
         int saltX = t == 0 ? 2 : 100 + 2 * t, saltZ = t == 0 ? 3 : 101 + 2 * t;
         int ax = cx * cell + 8 + (int) (unit(this.hash(cx, cz, k, saltX)) * (cell - 16));
         int az = cz * cell + 8 + (int) (unit(this.hash(cx, cz, k, saltZ)) * (cell - 16));

         List<Cartesian2D> sizes = explicitSizes;
         if (!explicit) {
            sizes = this.clips.getLandmarkSizes(this.blockSize, this.contexts.getContext(new Cartesian2D(ax, az)), SchematicType.BUILD, maxFit);
         }
         if (sizes.isEmpty()) {
            noSizes++;
            continue;
         }
         int sizeStart = (int) (unit(this.hash(cx, cz, k, 4)) * sizes.size());
         int dirStart = (int) (unit(this.hash(cx, cz, k, 5)) * directions.length);

         for (int s = 0; s < sizes.size(); s++) {
            Cartesian2D size = sizes.get((sizeStart + s) % sizes.size());
            if (size.X > MAX_SPAN || size.Y > MAX_SPAN) {
               continue;
            }
            Cartesian2D base = new Cartesian2D(ax - size.X / 2, az - size.Y / 2);
            if (this.tooClose(base.X, base.Y, size.X, size.Y, lower, spacing)) {
               tooClose++;
               continue;                                     // a smaller size may still clear its neighbour
            }
            final LandmarkZoneRule.Footprint footprint = UrbanGrid.zonesAround(this.contexts, base, size);
            ContextType centre = footprint.centre;
            if (PluginConfig.isMorphomosEnabled() && centre != null && centre.isMorphomos()) {
               countryside++;
               break;                                        // a landmark does not stand in the countryside: another anchor
            }
            ContextType fitContext = explicit ? ContextType.LANDMARK : (centre != null ? centre : this.contexts.getContext(base));
            for (int d = 0; d < directions.length; d++) {
               Direction direction = directions[(dirStart + d) % directions.length];
               List<Clipboard> candidates = new ArrayList<Clipboard>(this.clips.getFit(size, fitContext, SchematicType.BUILD, direction));
               candidates.removeIf(ClipboardParcel::hasCreekOrigin);                   // only where a creek provably leaves it
               candidates.removeIf(c -> c.getConfig() != null && c.getConfig().isRiverFacing());   // no river to face in the plan
               if (zoneMatch) {
                  candidates.removeIf(c -> !LandmarkZoneRule.allowsFootprint(c, footprint));
               }
               candidates.removeIf(c -> used.contains(c.getGroupId()));
               if (candidates.isEmpty()) {
                  noCandidate++;
                  continue;
               }
               Clipboard chosen = this.weighted(candidates, this.hash(cx, cz, k, 6));
               ContextType pin = zoneMatch ? LandmarkZoneRule.pinFor(chosen, centre) : null;
               ContextType context = pin != null ? pin
                       : (centre != null && centre != ContextType.UNDEFINED ? centre : ContextType.LANDMARK);
               Minions.dLandmark("LANDMARK SITE cell=(%d,%d) k=%d anchor=(%d,%d) size=%dx%d group='%s' zone=%s direction=%s anchorTry=%d/%d",
                       cx, cz, k, ax, az, size.X, size.Y, chosen.getGroupId(), context, direction, t + 1, ANCHOR_TRIES);
               return new WorldLayout.Landmark(ax, az, size.X, size.Y, context, new Choice(chosen, direction, context));
            }
         }
      }
      Minions.dLandmark("LANDMARK SITE DROPPED cell=(%d,%d) k=%d after %d anchors: no size in pool=%d, countryside=%d, too close to a lower landmark=%d, no schematic fits the zones/clone rule=%d",
              cx, cz, k, ANCHOR_TRIES, noSizes, countryside, tooClose, noCandidate);
      return null;
   }

   /** WorldLayout found no block for this site in its domain: say why, so a missing landmark is never silent. */
   @Override
   public void unplaced(WorldLayout.Landmark landmark, String reason) {
      Minions.dLandmark("LANDMARK NOT RESERVED anchor=(%d,%d) size=%dx%d group='%s': %s",
              landmark.anchorX, landmark.anchorZ, landmark.width, landmark.depth,
              landmark.choice instanceof Choice ? ((Choice) landmark.choice).clip.getGroupId() : "?", reason);
   }

   /**
    * Would a footprint at (minX, minZ) of w x d chunks stand closer than `spacing` chunks to any of these landmarks? A site only
    * ever yields to the sites that rank LOWER than it (see lower()), so for any two in range exactly one of them looks at the
    * other, whichever tile asks first, and the result is the same in every order.
    */
   private boolean tooClose(int minX, int minZ, int w, int d, List<WorldLayout.Landmark> others, int spacing) {
      for (WorldLayout.Landmark o : others) {
         int oMinX = o.anchorX - o.width / 2, oMinZ = o.anchorZ - o.depth / 2;
         if (minX < oMinX + o.width + spacing && oMinX < minX + w + spacing
                 && minZ < oMinZ + o.depth + spacing && oMinZ < minZ + d + spacing) {
            return true;
         }
      }
      return false;
   }

   /**
    * The landmarks of every LOWER site near enough to matter for spacing. Anchors lie 8..55 chunks into a cell and a footprint is
    * at most MAX_SPAN wide, so two sites whose cells are further apart than this reach cannot be within `spacing` of each other.
    */
   private List<WorldLayout.Landmark> lowerSites(int cx, int cz, int k) {
      List<WorldLayout.Landmark> out = new ArrayList<WorldLayout.Landmark>();
      int reach = Math.max(1, (94 + Math.max(0, PluginConfig.getLandmarkMinSpacing())) / WorldLayout.LandmarkPlanner.CELL);
      for (int dx = -reach; dx <= reach; dx++) {
         for (int dz = -reach; dz <= reach; dz++) {
            for (int kk = 0; kk < this.sitesPerCell(); kk++) {
               if ((dx == 0 && dz == 0 && kk == k) || !this.lower(cx + dx, cz + dz, kk, cx, cz, k)) {
                  continue;
               }
               WorldLayout.Landmark other = this.siteOf(cx + dx, cz + dz, kk);
               if (other != null) {
                  out.add(other);
               }
            }
         }
      }
      return out;
   }

   /** One of the candidates, by its odds of appearance, from a hash. */
   private Clipboard weighted(List<Clipboard> candidates, long h) {
      long total = 0;
      for (Clipboard c : candidates) {
         total += Math.max(1, c.getConfig() != null ? c.getConfig().getOddsOfAppearance() : 1);
      }
      long roll = (long) (unit(h) * total);
      for (Clipboard c : candidates) {
         roll -= Math.max(1, c.getConfig() != null ? c.getConfig().getOddsOfAppearance() : 1);
         if (roll < 0) {
            return c;
         }
      }
      return candidates.get(candidates.size() - 1);
   }

   // ------------------------------------------------------------------ keeping them apart

   /** Is site (bx, bz, bk) lower than site (cx, cz, k) in the order of sites: a strict total order. */
   private boolean lower(int bx, int bz, int bk, int cx, int cz, int k) {
      long a = this.hash(bx, bz, bk, 7);
      long b = this.hash(cx, cz, k, 7);
      if (a != b) {
         return a < b;
      }
      if (bx != cx) {
         return bx < cx;
      }
      if (bz != cz) {
         return bz < cz;
      }
      return bk < k;
   }

   /**
    * The groups of the landmarks of every LOWER site within generator.landmarkCloneRadius cells: this site keeps clear of them
    * (and is kept clear of by every higher one), so no two landmarks in range stand alike whichever is worked out first.
    */
   private Set<String> groupsNearby(int cx, int cz, int k) {
      Set<String> used = new HashSet<String>();
      int radius = Math.max(0, PluginConfig.getLandmarkCloneRadius());
      for (int dx = -radius; dx <= radius; dx++) {
         for (int dz = -radius; dz <= radius; dz++) {
            for (int kk = 0; kk < this.sitesPerCell(); kk++) {
               if ((dx == 0 && dz == 0 && kk == k) || !this.lower(cx + dx, cz + dz, kk, cx, cz, k)) {
                  continue;
               }
               WorldLayout.Landmark other = this.siteOf(cx + dx, cz + dz, kk);
               if (other != null) {
                  used.add(((Choice) other.choice).clip.getGroupId());
               }
            }
         }
      }
      return used;
   }
}