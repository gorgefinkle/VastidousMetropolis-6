package ch.k42.metropolis.grid.urbanGrid.ocean;

import ch.k42.metropolis.grid.urbanGrid.UrbanGrid;
import ch.k42.metropolis.generator.MetropolisGenerator;
import ch.k42.metropolis.grid.urbanGrid.parcel.OceanParcel;
import ch.k42.metropolis.grid.urbanGrid.parcel.OceanPortParcel;
import ch.k42.metropolis.grid.urbanGrid.parcel.Parcel;
import ch.k42.metropolis.grid.urbanGrid.parcel.RoadParcel;
import ch.k42.metropolis.grid.urbanGrid.parcel.StreetParcel;
import ch.k42.metropolis.grid.urbanGrid.enums.ContextType;
import ch.k42.metropolis.grid.urbanGrid.enums.SchematicType;
import ch.k42.metropolis.grid.urbanGrid.districts.District;
import ch.k42.metropolis.minions.Cartesian2D;
import ch.k42.metropolis.minions.Minions;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

/**
 * Deterministic, sparse, city-connected port siting, run AFTER the street/district and boat plan.
 * Every site requires an actual city street and dry industrial/commercial building ground.
 * Residential landings keep one 16-block strip; larger trade/industrial ports reserve
 * 3 chunk-columns x 4 seaward chunk-rows. Only explicitly reserved shore is modified.
 */
public final class OceanPortPlanner {
    private static final int[] DX = {1, 0, -1, 0};
    private static final int[] DZ = {0, 1, 0, -1};
    private OceanPortPlanner() { }

    // Port plans are computed once per 64x64-chunk district. Cache the diagnostic
    // summaries so /port ... debug can explain already-planned tiles without
    // re-running expensive site selection or generating any terrain chunks.
    // This cache stores *diagnostics only*, not world generation state.
    private static final int MAX_REPORTS = 256;
    private static final Map<String, String[]> REPORTS = new LinkedHashMap<String, String[]>(64, 0.75f, true) {
        @Override protected boolean removeEldestEntry(Map.Entry<String, String[]> entry) {
            return size() > MAX_REPORTS;
        }
    };
    private static String reportKey(String world, int x, int z) {
        return world + ":" + x + ":" + z;
    }
    private static void remember(String world, int x, int z, String[] report) {
        synchronized (REPORTS) { REPORTS.put(reportKey(world, x, z), report); }
    }
    /** One-line plan-time result for /port [type] debug; null means no cached plan. */
    public static String getDiagnostic(String world, int x, int z, OceanPortParcel.Kind type) {
        synchronized (REPORTS) {
            String[] report = REPORTS.get(reportKey(world, x, z));
            return report == null ? null : report[type == null ? 0 : type.ordinal() + 1];
        }
    }
    private static void debug(String msg, Object... args) {
        if (OceanConfig.isOceanPortsDebug()) Minions.i("[Ports:DEBUG] " + msg, args);
    }


    private static long mix(long x) {
        x ^= x >>> 30;
        x *= 0xbf58476d1ce4e5b9L;
        x ^= x >>> 27;
        x *= 0x94d049bb133111ebL;
        return x ^ (x >>> 31);
    }

    private static final class PortSite {
        final int x, z, direction, distance;
        final long score;
        final OceanPortParcel.Kind kind;
        PortSite(int x, int z, int direction, int distance, long score, OceanPortParcel.Kind kind) {
            this.x=x; this.z=z; this.direction=direction; this.distance=distance;
            this.score=score; this.kind=kind;
        }
    }

    private static boolean separated(PortSite a, PortSite b) {
        // At least five chunks between shoreline anchor sites, regardless of
        // district tile. The existing parcel checks protect actual footprints.
        int dx = a.x-b.x, dz = a.z-b.z;
        return dx*dx+dz*dz >= 49;
    }

    public static int place(UrbanGrid grid, MetropolisGenerator generator, int tileX, int tileZ) {
        if (!OceanConfig.isOceanEnabled() || !OceanConfig.isOceanPortsEnabled()) return 0;
        OceanProvider ocean = grid.getOceanProvider();
        if (ocean == null || !ocean.tileReached(tileX, tileZ)) return 0;
        long seed = ocean.getWorldSeed();
        long tileKey = mix(seed ^ (tileX * 0x632be59bd9b4e019L) ^ (tileZ * 0x9e3779b97f4a7c15L) ^ 0x504f525453L);
        long roll = Math.floorMod(tileKey, 100L);
        int chance = OceanConfig.getOceanPortsChancePercent();
        debug("ROLL world=%s tile=(%d,%d) result=%d threshold=%d%% -> %s",
                generator.worldName, tileX, tileZ, roll, chance, roll < chance ? "PASS" : "FAIL/SKIP");
        if (roll >= chance) {
            String skipped = "ROLL " + roll + " >= " + chance + "% (site search skipped)";
            remember(generator.worldName, tileX, tileZ,
                    new String[]{skipped, skipped, skipped, skipped});
            Minions.i("[Ports] SCAN SKIPPED world=%s tile=(%d,%d): chance roll %d >= %d%%; "
                    + "use chancePercent: 100 for testing", generator.worldName, tileX, tileZ, roll, chance);
            return 0;
        }

        int oceanCells = 0;
        int streetLinks = 0;
        int[] rejected = new int[Reject.values().length];
        // Diagnostics reveal whether large-port candidates exist but lose to a
        // residential site or are excluded by geometry. Logged once per tile.
        int[] districtCandidates = new int[OceanPortParcel.Kind.values().length];
        int[] validCandidates = new int[OceanPortParcel.Kind.values().length];
        int[][] rejectedByKind = new int[OceanPortParcel.Kind.values().length][Reject.values().length];
        String[][] exampleByKind = new String[OceanPortParcel.Kind.values().length][Reject.values().length];
        String mixedExample = null;
        String noStreetExample = null;

        int chosenX = 0, chosenZ = 0, chosenDirection = -1, chosenDistance = Integer.MAX_VALUE;
        OceanPortParcel.Kind chosenKind = null;
        long best = Long.MIN_VALUE;
        List<PortSite> sites = new ArrayList<PortSite>();
        // Leave at least 3 chunks of margin so all cells and the road live in this tile.
        // Site checks never create neighbouring grids and are independent of load order.
        for (int z = tileZ + 4; z <= tileZ + 59; z++) {
            for (int x = tileX + 4; x <= tileX + 59; x++) {
                if (!(grid.getParcel(x, z) instanceof OceanParcel)) continue;
                oceanCells++;
                for (int d = 0; d < 4; d++) {
                    int distance = streetDistance(grid, x, z, DX[d], DZ[d]);
                    if (distance < 0) {
                        rejected[Reject.NO_STREET.ordinal()]++;
                        if (noStreetExample == null) noStreetExample = "shore=(" + x + "," + z + ") direction=(" + DX[d] + "," + DZ[d] + ")";
                        continue;
                    }
                    streetLinks++;
                    Frontage frontage = frontage(grid, x, z, DX[d], DZ[d], distance);
                    OceanPortParcel.Kind kind = frontage.kind;
                    if (kind == null) {
                        rejected[Reject.INCOMPATIBLE_DISTRICT.ordinal()]++;
                        if (mixedExample == null) mixedExample = "shore=(" + x + "," + z + ") roadDistance=" + distance + " " + frontage;
                        continue;
                    }
                    districtCandidates[kind.ordinal()]++;
                    // Residential and commercial landings must be adjacent to their existing
                    // urban frontage, not attached by an industrial annex through wilderness.
                    if (kind != OceanPortParcel.Kind.INDUSTRIAL_WHARF && distance > 4) {
                        rejected[Reject.DISTANT_NONINDUSTRIAL.ordinal()]++;
                        rejectedByKind[kind.ordinal()][Reject.DISTANT_NONINDUSTRIAL.ordinal()]++;
                        recordExample(exampleByKind, kind, Reject.DISTANT_NONINDUSTRIAL, x, z, d, distance);
                        continue;
                    }
                    Reject reason = suitableReason(grid, ocean, x, z, DX[d], DZ[d], distance, kind);
                    if (reason != Reject.OK) {
                        rejected[reason.ordinal()]++;
                        rejectedByKind[kind.ordinal()][reason.ordinal()]++;
                        recordExample(exampleByKind, kind, reason, x, z, d, distance);
                        continue;
                    }
                    // Sites more than four chunks from a street are acceptable
                    // only when they can reclaim a real INDUSTRIAL city block
                    // beside the approach. No lonely road through wilderness.
                    if (kind == OceanPortParcel.Kind.INDUSTRIAL_WHARF && distance > 4 && OceanPortReclaim.find(grid, ocean, tileX, tileZ,
                            x, z, DX[d], DZ[d], distance) == null) {
                        rejected[Reject.NO_RECLAIM.ordinal()]++;
                        rejectedByKind[kind.ordinal()][Reject.NO_RECLAIM.ordinal()]++;
                        recordExample(exampleByKind, kind, Reject.NO_RECLAIM, x, z, d, distance);
                        continue;
                    }
                    validCandidates[kind.ordinal()]++;
                    long score = mix(tileKey ^ (x * 0x9e3779b97f4a7c15L)
                            ^ (z * 0x632be59bd9b4e019L) ^ (d * 0xd1b54a32d192ed03L));
                    sites.add(new PortSite(x, z, d, distance, score, kind));
                    if (chosenDirection < 0 || Long.compareUnsigned(score, best) > 0) {
                        chosenX=x; chosenZ=z; chosenDirection=d;
                        chosenDistance=distance; chosenKind=kind; best=score;
                    }
                }
            }
        }
        // Capture WHY each port family did or did not qualify. The console gets
        // one summary plus a representative coordinate per rejection class;
        // this avoids one log entry for each of the thousands of ocean cells.
        String[] report = diagnosticReport(roll, chance, districtCandidates, validCandidates,
                rejectedByKind, rejected, chosenKind);
        remember(generator.worldName, tileX, tileZ, report);
        debug("CANDIDATES world=%s tile=(%d,%d) oceanCells=%d streetLinks=%d; zoning=%s; valid=%s; allFailures=%s; selected=%s",
                generator.worldName, tileX, tileZ, oceanCells, streetLinks,
                kindCounts(districtCandidates), kindCounts(validCandidates), rejectionReport(rejected),
                chosenKind == null ? "NONE" : chosenKind.name());
        debug("INDUSTRIAL world=%s tile=(%d,%d) %s", generator.worldName, tileX, tileZ,
                report[OceanPortParcel.Kind.INDUSTRIAL_WHARF.ordinal()+1]);
        debug("COMMERCIAL world=%s tile=(%d,%d) %s", generator.worldName, tileX, tileZ,
                report[OceanPortParcel.Kind.COMMERCIAL_QUAY.ordinal()+1]);
        if (OceanConfig.isOceanPortsDebug()) {
            if (mixedExample != null) debug("ZONING EXAMPLE rejected incompatible frontage: %s (industrial next to housing is vetoed)", mixedExample);
            if (noStreetExample != null) debug("STREET EXAMPLE %s; shoreline approach needs an uninterrupted OceanParcel path to a real road within 8 chunks", noStreetExample);
            for (OceanPortParcel.Kind kind : new OceanPortParcel.Kind[]{OceanPortParcel.Kind.COMMERCIAL_QUAY, OceanPortParcel.Kind.INDUSTRIAL_WHARF}) {
                for (Reject reason : Reject.values()) {
                    String example = exampleByKind[kind.ordinal()][reason.ordinal()];
                    if (example != null) debug("REJECT %s %s: %s; %s", kind.name(), reason, example, meaning(reason));
                }
            }
        }
        if (chosenDirection < 0) {
            String failures = rejectionReport(rejected);
            Minions.w("[Ports] NO VALID SITE world=%s tile=(%d,%d), oceanCells=%d, "
                    + "streetLinks=%d, rejected=%s", generator.worldName, tileX, tileZ,
                    oceanCells, streetLinks, failures + ", zones=" + kindCounts(districtCandidates) + ", valid=" + kindCounts(validCandidates)
                            + ", industrialReject={" + rejectionReport(rejectedByKind[OceanPortParcel.Kind.INDUSTRIAL_WHARF.ordinal()])
                            + "}, commercialReject={" + rejectionReport(rejectedByKind[OceanPortParcel.Kind.COMMERCIAL_QUAY.ordinal()]) + "}");
            OceanPortAnnouncements.noSite(generator, tileX, tileZ);
            return 0;
        }
        Minions.i("[Ports] SCAN PASSED world=%s tile=(%d,%d) oceanCells=%d streetLinks=%d, "
                + "rejected=%s", generator.worldName, tileX, tileZ,
                oceanCells, streetLinks, rejectionReport(rejected) + ", zones=" + kindCounts(districtCandidates) + ", valid=" + kindCounts(validCandidates)
                        + ", industrialReject={" + rejectionReport(rejectedByKind[OceanPortParcel.Kind.INDUSTRIAL_WHARF.ordinal()])
                        + "}, commercialReject={" + rejectionReport(rejectedByKind[OceanPortParcel.Kind.COMMERCIAL_QUAY.ordinal()]) + "}");
        // Choose independent sites using deterministic spatial separation,
        // not a single district-wide winner or industrial-first priority.
        Collections.sort(sites, new Comparator<PortSite>() {
            @Override public int compare(PortSite a, PortSite b) {
                return Long.compareUnsigned(b.score, a.score);
            }
        });
        List<PortSite> selected = new ArrayList<PortSite>();
        for (PortSite site : sites) {
            boolean close = false;
            for (PortSite previous : selected) {
                if (!separated(site, previous)) { close=true; break; }
            }
            if (close) continue;
            // Earlier reservations modify the grid. Recheck immediately
            // before committing so competing shore plans cannot overlap.
            if (streetDistance(grid, site.x, site.z, DX[site.direction], DZ[site.direction]) != site.distance) continue;
            if (suitableReason(grid, ocean, site.x, site.z, DX[site.direction], DZ[site.direction],
                    site.distance, site.kind) != Reject.OK) continue;
            OceanPortReclaim.Plan annex = site.kind == OceanPortParcel.Kind.INDUSTRIAL_WHARF && site.distance > 4
                    ? OceanPortReclaim.find(grid, ocean, tileX, tileZ, site.x, site.z,
                        DX[site.direction], DZ[site.direction], site.distance) : null;
            if (site.kind == OceanPortParcel.Kind.INDUSTRIAL_WHARF && site.distance > 4 && annex == null) continue;
            OceanPortParcel port = new OceanPortParcel(grid, new Cartesian2D(site.x, site.z),
                    DX[site.direction], DZ[site.direction], site.distance, site.score, site.kind);
            if (annex != null) port.setAnnexSide(annex.side);
            OceanPortReclaim.commit(grid, annex);
            selected.add(site);
            Minions.i("[Ports] %s planned shore chunk (%d,%d), facing (%d,%d), street %d chunk(s) inland",
                    site.kind, site.x, site.z, DX[site.direction], DZ[site.direction], site.distance);
            OceanPortAnnouncements.planned(generator, site.x, site.z, site.distance);
        }
        return selected.size();
    }

    private static void recordExample(String[][] examples, OceanPortParcel.Kind kind,
                                      Reject reason, int cx, int cz, int d, int distance) {
        if (examples[kind.ordinal()][reason.ordinal()] == null)
            examples[kind.ordinal()][reason.ordinal()] = "shoreChunk=(" + cx + "," + cz
                    + ") facing=(" + DX[d] + "," + DZ[d] + ") streetDistance=" + distance + " chunks";
    }

    private static String meaning(Reject reason) {
        switch (reason) {
            case NO_STREET: return "no connected real road within 8 dry ocean-shore chunks";
            case NOT_OPEN_OCEAN: return "required reserved 1x3 or 3x4-chunk dock footprint contains a non-ocean parcel or crosses the tile edge";
            case WET_FOUNDATION: return "at least one warehouse/yard sample is too close to water (distanceInside > -2)";
            case FLOODED_ACCESS: return "street-to-quay route has a wet/tidal sample (distanceInside > -3)";
            case SHALLOW_NEAR_BERTH: return "working berth water beside the pier is too shallow (not the filled masonry quay)";
            case SHALLOW_END_BERTH: return "outboard ship berth lacks deep water at the pier end";
            case STREET_HEIGHT: return "street surface and dock elevation differ by more than 7 blocks";
            case NO_RECLAIM: return "long industrial access requires a safe reclaimed district; check minDepth/minWidth/maxWidth in CHUNKS and space occupied by other parcels";
            case INCOMPATIBLE_DISTRICT: return "frontage is mixed, unzoned, or has housing beside a larger port";
            case DISTANT_NONINDUSTRIAL: return "residential/commercial landing requires street within 4 chunks";
            case FILLER_BESIDE_ACCESS: return "existing filler touches the proposed PRIVATE port approach; reject site rather than turning the access lane into public frontage";
            default: return "accepted";
        }
    }

    private static String[] diagnosticReport(long roll, int chance, int[] zones, int[] valid,
                                              int[][] rejectsByKind, int[] rejects, OceanPortParcel.Kind selected) {
        String[] results = new String[OceanPortParcel.Kind.values().length+1];
        results[0] = "roll=" + roll + "/" + chance + " PASS; zoning=" + kindCounts(zones)
                + "; valid=" + kindCounts(valid) + "; selected=" + (selected == null ? "none" : selected.name());
        for (OceanPortParcel.Kind kind : OceanPortParcel.Kind.values()) {
            int i = kind.ordinal();
            String failure;
            if (zones[i] == 0) {
                failure = "no compatible " + kind.name() + " waterfront frontage (see incompatible zoning counts="
                        + rejects[Reject.INCOMPATIBLE_DISTRICT.ordinal()] + ", no street="
                        + rejects[Reject.NO_STREET.ordinal()] + ")";
            } else if (valid[i] == 0) {
                failure = "all " + zones[i] + " zoned candidates blocked: " + rejectionReport(rejectsByKind[i]);
            } else if (selected != kind) {
                failure = valid[i] + " geometrically valid, but one-port-per-tile selection chose " + selected;
            } else {
                failure = "SELECTED (other valid=" + valid[i] + ")";
            }
            results[i+1] = kind.name() + " zones=" + zones[i] + " valid=" + valid[i] + "; " + failure;
        }
        return results;
    }

    private static int priority(OceanPortParcel.Kind kind) {
        if (kind == OceanPortParcel.Kind.INDUSTRIAL_WHARF) return 3;
        if (kind == OceanPortParcel.Kind.COMMERCIAL_QUAY) return 2;
        return 1;
    }

    private static String kindCounts(int[] counts) {
        return "residential:" + counts[OceanPortParcel.Kind.RESIDENTIAL_LANDING.ordinal()]
                + "/commercial:" + counts[OceanPortParcel.Kind.COMMERCIAL_QUAY.ordinal()]
                + "/industrial:" + counts[OceanPortParcel.Kind.INDUSTRIAL_WHARF.ordinal()];
    }

    private enum Reject {
        OK, NO_STREET, NOT_OPEN_OCEAN, WET_FOUNDATION, FLOODED_ACCESS,
        SHALLOW_NEAR_BERTH, SHALLOW_END_BERTH, STREET_HEIGHT, NO_RECLAIM,
        INCOMPATIBLE_DISTRICT, DISTANT_NONINDUSTRIAL, FILLER_BESIDE_ACCESS
    }

    /**
     * Read the EXISTING district directly across the access street, not a
     * several-chunk-deep rectangle of unrelated neighborhoods. The previous
     * survey looked as far as four chunks inland and vetoed a freight quay
     * because a residential block happened to stand BEHIND its industrial
     * waterfront block. That made residential landings overwhelmingly likely
     * even where there was a genuine working waterfront.
     *
     * The three-chunk-wide working ports require a compatible five-chunk
     * street frontage (their three occupied columns plus a one-chunk buffer
     * on either side). A house directly alongside still vetoes freight.
     * Each column contributes only its first built frontage parcel: another
     * district several chunks behind it must never decide this shoreline.
     */
    private static final class Frontage {
        final int industrial, commercial, housing;
        final OceanPortParcel.Kind kind;
        Frontage(int industrial, int commercial, int housing) {
            this.industrial = industrial;
            this.commercial = commercial;
            this.housing = housing;
            if (housing == 0 && industrial > 0 && industrial >= commercial)
                kind = OceanPortParcel.Kind.INDUSTRIAL_WHARF;
            else if (housing == 0 && commercial > 0 && industrial == 0)
                kind = OceanPortParcel.Kind.COMMERCIAL_QUAY;
            else if (housing > 0 && industrial == 0 && commercial == 0)
                kind = OceanPortParcel.Kind.RESIDENTIAL_LANDING;
            else kind = null;
        }
        @Override public String toString() {
            return "frontage(industrial=" + industrial + ", commercial=" + commercial
                    + ", residential=" + housing + ")";
        }
    }

    /** Kept for compatibility with existing layout tests. */
    private static OceanPortParcel.Kind districtType(UrbanGrid grid, int cx, int cz,
                                                      int dx, int dz, int distance) {
        return frontage(grid, cx, cz, dx, dz, distance).kind;
    }
    private static Frontage frontage(UrbanGrid grid, int cx, int cz,
                                     int dx, int dz, int distance) {
        int rootX = Math.floorDiv(cx, 64) * 64;
        int rootZ = Math.floorDiv(cz, 64) * 64;
        int industrial = 0, commercial = 0, housing = 0;
        for (int s = -2; s <= 2; s++) {
            for (int t = distance + 1; t <= distance + 4; t++) {
                int x = cx - t * dx - s * dz;
                int z = cz - t * dz + s * dx;
                if (x <= rootX + 1 || x >= rootX + 62
                        || z <= rootZ + 1 || z >= rootZ + 62) break;
                Parcel p = grid.getParcel(x, z);
                if (p == null || p instanceof RoadParcel || p instanceof OceanParcel)
                    continue;
                District district = p.getDistrict();
                ContextType context = district != null ? district.getContext() : p.getContextType();
                if (context == ContextType.INDUSTRIAL) industrial++;
                else if (context == ContextType.RESIDENTIAL || context == ContextType.DENSERESIDENTIAL)
                    housing++;
                else if (context == ContextType.LOWRISE || context == ContextType.MIDRISE
                        || context == ContextType.HIGHRISE) commercial++;
                break;
            }
        }
        return new Frontage(industrial, commercial, housing);
    }

    private static String rejectionReport(int[] rejected) {
        StringBuilder out = new StringBuilder();
        for (Reject r : Reject.values()) {
            if (r == Reject.OK || rejected[r.ordinal()] == 0) continue;
            if (out.length() > 0) out.append(", ");
            out.append(r).append('=').append(rejected[r.ordinal()]);
        }
        return out.length() == 0 ? "none" : out.toString();
    }

    /** Find a street along an ocean-owned, unobstructed inland strip, never through a building. */
    private static int streetDistance(UrbanGrid grid, int cx, int cz, int dx, int dz) {
        for (int d = 1; d <= 8; d++) {
            // getParcel() wraps within a 64x64 grid. Never inspect a different
            // tile by accident or put a long approach across the tile seam.
            int ix = cx - d*dx, iz = cz - d*dz;
            int rootX = Math.floorDiv(cx, 64) * 64;
            int rootZ = Math.floorDiv(cz, 64) * 64;
            if (ix <= rootX + 1 || ix >= rootX + 62 || iz <= rootZ + 1 || iz >= rootZ + 62) return -1;
            Parcel p = grid.getParcel(cx - d*dx, cz - d*dz);
            if (p instanceof RoadParcel) {
                return ((RoadParcel) p).resolveRoadType() == null ? -1 : d;
            }
            if (p == null || p.getClass() != OceanParcel.class) return -1;
        }
        return -1;
    }

    private static Reject suitableReason(UrbanGrid grid, OceanProvider ocean, int cx, int cz,
                                         int dx, int dz, int distance, OceanPortParcel.Kind kind) {
        // Ports are planned after the ordinary district's BUILD/FILLER sweep.
        // A private port approach must not retroactively become the apparent
        // frontage of an already-placed filler. Refuse that port SITE rather
        // than erasing the filler or weakening city-wide filler selection.
        // Only cells directly beside the access strip are checked; the public
        // street itself and unrelated inland neighborhoods are unaffected.
        for (int t = 1; t < distance; t++) {
            int roadX = cx - t * dx, roadZ = cz - t * dz;
            for (int side : new int[]{-1, 1}) {
                int neighborX = roadX + side * dz;
                int neighborZ = roadZ - side * dx;
                Parcel neighbor = grid.peekParcel(neighborX, neighborZ);
                if (neighbor != null && neighbor.getSchematicType() == SchematicType.FILLER)
                    return Reject.FILLER_BESIDE_ACCESS;
            }
        }
        final int sideCells = kind == OceanPortParcel.Kind.RESIDENTIAL_LANDING ? 0 : 1;
        final int seaCells = kind == OceanPortParcel.Kind.RESIDENTIAL_LANDING ? 2 : 3;
        int rootX = Math.floorDiv(cx,64)*64;
        int rootZ = Math.floorDiv(cz,64)*64;
        for (int t = 0; t <= seaCells; t++) for (int side=-sideCells; side<=sideCells; side++) {
            int px=cx + t*dx-side*dz, pz=cz+t*dz+side*dx;
            if (px<=rootX+1 || px>=rootX+62 || pz<=rootZ+1 || pz>=rootZ+62)
                return Reject.NOT_OPEN_OCEAN;
            // Reserve EVERY occupied chunk. No cargo structures may overwrite a
            // preexisting road, boat, neighboring city lot or wilderness claim.
            Parcel cell = grid.getParcel(px,pz);
            if (cell == null || cell.getClass()!=OceanParcel.class)
                return Reject.NOT_OPEN_OCEAN;
        }
        // Must use the identical oriented origin as OceanPortParcel.bx/bz.
        int x0 = (cx << 4) + (dx > 0 || dz < 0 ? 8 : 7);
        int z0 = (cz << 4) + (dz > 0 || dx > 0 ? 8 : 7);
        int sx = -dz, sz = dx;
        // The warehouse and roadward yard must be LAND, while the pier and berth have
        // uninterrupted real ocean water on BOTH sides, not just at a chunk midpoint.
        // The ENTIRE landward warehouse/yard footprint needs sound dry land.
        // Sampling only two corners allowed coves to flood the warehouse interior.
        int[] landOffsets = sideCells == 0 ? new int[]{-7,-4,-1,0,3,7}
                : new int[]{-21,-16,-11,-6,0,6,11,16,21};
        for (int s : landOffsets) {
            for (int t : new int[]{-7, -4, 0, 4, 7}) {
                OceanProvider.Sample q = ocean.sample(x0 + s*sx + t*dx, z0 + s*sz + t*dz);
                if (q.distanceInside > -2) return Reject.WET_FOUNDATION;
            }
        }
        // The narrow shore access road cannot span an unnoticed tidal inlet.
        for (int t = -8 - 16 * (distance - 1); t < -7; t += 5) {
            for (int s : new int[]{1, 3, 6}) {
                OceanProvider.Sample q = ocean.sample(x0 + s*sx + t*dx, z0 + s*sz + t*dz);
                if (q.distanceInside > -3) return Reject.FLOODED_ACCESS;
            }
        }
        // Water checks must sample the places where SHIPS actually berth.
        // The former shared check at t=22/26 was wrong for the large types:
        //   COMMERCIAL: t=22 is the apron/landward end of the timber fingers.
        //   INDUSTRIAL: t=22..36 is a deliberately SOLID stone freight quay.
        // Demanding deep ocean water there rejected virtually every correctly
        // placed trading/industrial waterfront (see [Ports:DEBUG] diagnostics).
        // Check the navigable water immediately BESIDE the real berths instead.
        if (kind == OceanPortParcel.Kind.RESIDENTIAL_LANDING) {
            // Preserve the already-approved residential landing's criteria.
            for (int s : new int[]{2, 7}) for (int t : new int[]{16, 22}) {
                OceanProvider.Sample q = ocean.sample(x0 + s*sx + t*dx, z0 + s*sz + t*dz);
                if (q.distanceInside < 3 || q.columnDepth < 2) return Reject.SHALLOW_NEAR_BERTH;
            }
            for (int s : new int[]{-5, 2, 7}) for (int t : new int[]{30, 37}) {
                OceanProvider.Sample q = ocean.sample(x0 + s*sx + t*dx, z0 + s*sz + t*dz);
                if (q.distanceInside < 6 || q.columnDepth < 3) return Reject.SHALLOW_END_BERTH;
            }
        } else if (kind == OceanPortParcel.Kind.COMMERCIAL_QUAY) {
            // Timber fingers occupy s=-20..-13 and 13..20, t=30..55,
            // and connect directly to the solid stone quay ending at t=29.
            // The open central channel s=-12..12 is the actual berthing water.
            for (int s : new int[]{-10, 10}) {
                OceanProvider.Sample near = ocean.sample(x0 + s*sx + 42*dx, z0 + s*sz + 42*dz);
                if (near.distanceInside < 2 || near.columnDepth < 2) return Reject.SHALLOW_NEAR_BERTH;
                OceanProvider.Sample far = ocean.sample(x0 + s*sx + 54*dx, z0 + s*sz + 54*dz);
                if (far.distanceInside < 6 || far.columnDepth < 3) return Reject.SHALLOW_END_BERTH;
            }
        } else {
            // Industrial stone infill covers s=-22..22 at t=8..36, and the
            // projecting freight pier covers s=2..22 at t=37..55. The berthing
            // water is to the LEFT of that projection (s=-22..1), not under
            // the masonry. Check along the ship-facing, unobstructed side.
            for (int s : new int[]{-14, -6}) {
                OceanProvider.Sample near = ocean.sample(x0 + s*sx + 43*dx, z0 + s*sz + 43*dz);
                if (near.distanceInside < 2 || near.columnDepth < 2) return Reject.SHALLOW_NEAR_BERTH;
                OceanProvider.Sample far = ocean.sample(x0 + s*sx + 53*dx, z0 + s*sz + 53*dz);
                if (far.distanceInside < 6 || far.columnDepth < 3) return Reject.SHALLOW_END_BERTH;
            }
        }
        int waterY = ocean.waterSurfaceY();
        return Math.abs(waterY + 2 - StreetParcel.ROAD_SURFACE_Y) <= 7
                ? Reject.OK : Reject.STREET_HEIGHT;  // reasonable graded street-to-deck transition
    }
}
