package ch.k42.metropolis.grid.urbanGrid.districts;

import ch.k42.metropolis.grid.urbanGrid.UrbanGrid;
import ch.k42.metropolis.grid.urbanGrid.context.ContextProvider;
import ch.k42.metropolis.grid.urbanGrid.context.LandmarkZoneRule;
import ch.k42.metropolis.grid.urbanGrid.enums.ContextType;
import ch.k42.metropolis.grid.urbanGrid.layout.WorldLayout;
import ch.k42.metropolis.grid.urbanGrid.river.BoundedRiverSites;
import ch.k42.metropolis.minions.Cartesian2D;
import ch.k42.metropolis.minions.Minions;
import ch.k42.metropolis.plugin.PluginConfig;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;

/**
 * R30ea-recovered fixed creek-origin landmarks.
 * The memo is indexed by stable site identity; origin lookup deliberately uses object identity.
 * Reconstructed from the actual R30ea instructions, not a new planning algorithm.
 */
public final class BoundedRiverLots implements WorldLayout.FixedPlanner {
    private final BoundedRiverSites sites;
    private final ContextProvider contexts;
    private final Map<String, WorldLayout.Landmark> memo = new HashMap<>();
    private final Map<WorldLayout.Landmark, BoundedRiverSites.Site> origins = new IdentityHashMap<>();

    public BoundedRiverLots(BoundedRiverSites sites, ContextProvider contexts) {
        this.sites = sites;
        this.contexts = contexts;
    }

    @Override
    public synchronized List<WorldLayout.Landmark> fixedIn(int minX, int minZ, int maxX, int maxZ) {
        List<WorldLayout.Landmark> found = new ArrayList<>();
        for (BoundedRiverSites.Site site : sites.parksIn(minX, minZ, maxX, maxZ)) {
            found.add(landmarkOf(site));
        }
        return found;
    }

    private WorldLayout.Landmark landmarkOf(BoundedRiverSites.Site site) {
        String id = site.id();
        WorldLayout.Landmark previous = memo.get(id);
        if (previous != null) return previous;
        Cartesian2D base = new Cartesian2D(site.lotBaseX, site.lotBaseZ);
        Cartesian2D size = new Cartesian2D(site.lotSizeX, site.lotSizeZ);
        ContextType centre = UrbanGrid.zonesAround(contexts, base, size).centre;
        ContextType pinned = PluginConfig.isLandmarkZoneMatchEnabled()
                ? LandmarkZoneRule.pinFor(site.clipboard, centre) : null;
        ContextType zone = pinned != null ? pinned
                : centre != null && centre != ContextType.UNDEFINED && !centre.isMorphomos()
                  ? centre : ContextType.LANDMARK;
        WorldLayout.Landmark result = new WorldLayout.Landmark(
                site.lotBaseX + site.lotSizeX / 2,
                site.lotBaseZ + site.lotSizeZ / 2,
                site.lotSizeX, site.lotSizeZ, zone,
                new LandmarkPlans.Choice(site.clipboard, site.direction, zone));
        memo.put(id, result);
        origins.put(result, site);
        Minions.dRiver("CREEK LOT %s -- fixed block %dx%d chunks at (%d,%d), zoned %s",
                id, site.lotSizeX, site.lotSizeZ, site.lotBaseX, site.lotBaseZ, zone);
        return result;
    }

    @Override
    public boolean outletRoadAt(WorldLayout.Landmark landmark, int x, int z) {
        BoundedRiverSites.Site site = origins.get(landmark);
        if (site == null) return false;
        int minX = site.lotBaseX, minZ = site.lotBaseZ;
        int maxX = minX + site.lotSizeX - 1, maxZ = minZ + site.lotSizeZ - 1;
        switch (site.origin.outward) {
            case NORTH: return z == minZ - 1 && x >= minX && x <= maxX;
            case SOUTH: return z == maxZ + 1 && x >= minX && x <= maxX;
            case WEST: return x == minX - 1 && z >= minZ && z <= maxZ;
            case EAST: return x == maxX + 1 && z >= minZ && z <= maxZ;
            default: return false;
        }
    }

    @Override
    public void unplaced(WorldLayout.Landmark landmark, String reason) {
        BoundedRiverSites.Site site = origins.get(landmark);
        if (PluginConfig.isCreekPaintLifecycleDebugEnabled()) {
            Minions.w("[R14-CREEK-LIFECYCLE] ISSUE=ORIGIN_PARK_REJECTED_AFTER_WATER site=%s reason=%s",
                    site == null ? "unknown" : site.id(), reason);
        }
        Minions.w("CREEK LOT NOT RESERVED at (" +
                (landmark.anchorX - landmark.width / 2) + "," +
                (landmark.anchorZ - landmark.depth / 2) + ") " +
                landmark.width + "x" + landmark.depth + ": " + reason);
    }
}
