package ch.k42.metropolis.grid.urbanGrid.layout;

import ch.k42.metropolis.grid.urbanGrid.highway.HighwayNetwork;

import java.io.PrintWriter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Random;

/**
 * Pure-Java checker for WorldLayout (no Bukkit). Builds the real HighwayNetwork and WorldLayout for a seed, dumps
 * a window of the plan, and checks the invariants the plan is meant to have:
 *
 *   1. every chunk is exactly one kind (no UNCLAIMED), and every BLOCK chunk is in exactly one block rectangle
 *   2. every block is within [minBlock, maxBlock] on both sides
 *   3. no parallel double: no 2x2 window of road (STREET/LANE) that holds a STREET cell
 *   4. no street running alongside a lane (two or more street chunks in a row beside a lane)
 *   5. no dead end: a street chunk with at most one road neighbour that is not touching water
 *   6. no failed domain (a domain the exact search could not carve)
 *   7. (det=1) the same plan comes out when it is asked for in a different order
 *
 * Usage (all arguments optional, key=value):
 *   seed=1 cx=0 cz=0 size=400 min=7 max=14 water=none|river out=plan.txt det=0 scale=1
 *
 * water=river is a STAND-IN: a lattice-snapped meandering band, since the real river needs Bukkit's noise. It
 * exercises the zone path of the layout; it is not the real river.
 */
public final class PlanValidator {

    // the shipped config.yml values
    static final int SPACING = 230, RADIUS = 68, VARIATION = 50, BOX_SCALE = 100, EXTRA = 0;

    static long mix(long z) {
        z += 0x9E3779B97F4A7C15L;
        z = (z ^ (z >>> 30)) * 0xBF58476D1CE4E5B9L;
        z = (z ^ (z >>> 27)) * 0x94D049BB133111EBL;
        return z ^ (z >>> 31);
    }

    /** A stand-in for WaterField: a meandering band, snapped to the lattice exactly like WaterField.at() does. */
    static final class StandInWater implements WorldLayout.Mask {
        final int lattice;
        final double slowWave, slowAmp, fastWave, fastAmp, halfWidth, base;

        StandInWater(long seed, int lattice) {
            this.lattice = lattice;
            this.slowWave = 300;
            this.slowAmp = 24;
            this.fastWave = 100;
            this.fastAmp = 2.5;
            this.halfWidth = 4;
            this.base = (mix(seed ^ 0x51DE) % 120) - 60;
        }

        boolean water(int x, int z) {
            double c = base + slowAmp * Math.sin(2 * Math.PI * x / slowWave) + fastAmp * Math.sin(2 * Math.PI * x / fastWave + 1.3);
            return Math.abs(z - c) <= halfWidth;
        }

        @Override
        public boolean at(int x, int z) {
            int cx = Math.floorDiv(x, lattice), cz = Math.floorDiv(z, lattice);
            for (int a = 0; a < lattice; a++) {
                for (int b = 0; b < lattice; b++) {
                    if (water(cx * lattice + a, cz * lattice + b)) {
                        return true;
                    }
                }
            }
            return false;
        }
    }

    /** Flexible blocks (see WorldLayout.setBlockSlack); set with slack=N. */
    static int SLACK = 0;

    /** landmarks=N: a stand-in landmark planner with N sites per 64-chunk cell (test only: the real one needs the schematics). */
    static int LANDMARKS = 0;

    static final class StandInLandmarks implements WorldLayout.LandmarkPlanner {
        private final long seed;
        private final int sites;

        StandInLandmarks(long seed, int sites) {
            this.seed = seed;
            this.sites = sites;
        }

        public int sitesPerCell() {
            return sites;
        }

        public WorldLayout.Landmark siteOf(int cx, int cz, int k) {
            long h = mix(seed ^ (cx * 73856093L) ^ (cz * 19349663L) ^ (k * 83492791L));
            int[][] sizes = {{20, 20}, {26, 18}, {18, 30}, {34, 34}};
            int[] size = sizes[(int) Math.floorMod(h >>> 8, (long) sizes.length)];
            int ax = cx * 64 + 8 + (int) Math.floorMod(h >>> 20, 48L);
            int az = cz * 64 + 8 + (int) Math.floorMod(h >>> 40, 48L);
            return new WorldLayout.Landmark(ax, az, size[0], size[1], null, null);
        }
    }

    static WorldLayout build(long seed, int minBlock, int maxBlock, boolean river) {
        int lattice = minBlock + 2;
        HighwayNetwork net = new HighwayNetwork(seed, SPACING, RADIUS, VARIATION / 100.0, BOX_SCALE / 100.0, EXTRA / 100.0, 20,
                null, lattice);
        int pitch = WorldLayoutsPitch.PITCH;
        WorldLayout built = new WorldLayout(net, seed, minBlock, maxBlock, pitch,
                (int) Math.floorMod(mix(seed ^ 0x57A11E5L), (long) pitch), (int) Math.floorMod(mix(seed ^ 0x2B1D0F3L), (long) pitch),
                river ? new StandInWater(seed, lattice) : null);
        if (LANDMARKS > 0) {
            built.setLandmarkPlanner(new StandInLandmarks(seed, LANDMARKS));
        }
        built.setBlockSlack(SLACK);
        return built;
    }

    /** The pitch WorldLayouts uses, repeated here so this tool does not pull in PluginConfig (which needs Bukkit). */
    static final class WorldLayoutsPitch {
        static final int PITCH = 97;
    }

    static boolean road(int k) {
        return k == WorldLayout.STREET || k == WorldLayout.LANE;
    }

    static char glyph(int k) {
        switch (k) {
            case WorldLayout.LANE: return '=';
            case WorldLayout.STREET: return '+';
            case WorldLayout.BLOCK: return ' ';
            case WorldLayout.ZONE: return '~';
            default: return '?';
        }
    }

    public static void main(String[] args) throws Exception {
        long seed = 1;
        int cx = 0, cz = 0, size = 400, min = 7, max = 14, det = 0;
        String water = "none", out = null;
        for (String a : args) {
            String[] kv = a.split("=", 2);
            if (kv.length != 2) {
                continue;
            }
            String k = kv[0], v = kv[1];
            if (k.equals("seed")) seed = Long.parseLong(v);
            else if (k.equals("cx")) cx = Integer.parseInt(v);
            else if (k.equals("cz")) cz = Integer.parseInt(v);
            else if (k.equals("size")) size = Integer.parseInt(v);
            else if (k.equals("min")) min = Integer.parseInt(v);
            else if (k.equals("max")) max = Integer.parseInt(v);
            else if (k.equals("water")) water = v;
            else if (k.equals("out")) out = v;
            else if (k.equals("det")) det = Integer.parseInt(v);
            else if (k.equals("slack")) SLACK = Integer.parseInt(v);
            else if (k.equals("landmarks")) LANDMARKS = Integer.parseInt(v);
        }
        boolean river = water.equals("river");
        int x0 = cx - size / 2, z0 = cz - size / 2, x1 = x0 + size - 1, z1 = z0 + size - 1;

        long t0 = System.nanoTime();
        WorldLayout layout = build(seed, min, max, river);
        int W = size, H = size;
        // pad by one so neighbour checks on the window edge read real cells
        int[][] kind = new int[W + 2][H + 2];
        for (int x = -1; x <= W; x++) {
            for (int z = -1; z <= H; z++) {
                kind[x + 1][z + 1] = layout.kindAt(x0 + x, z0 + z);
            }
        }
        double secs = (System.nanoTime() - t0) / 1e9;

        int[] count = new int[6];
        int unclaimed = 0;
        for (int x = 0; x < W; x++) {
            for (int z = 0; z < H; z++) {
                int k = kind[x + 1][z + 1];
                count[k]++;
                if (k == WorldLayout.UNCLAIMED) unclaimed++;
            }
        }

        // 1 + 2: blocks
        List<WorldLayout.Rect> blocks = layout.blocksIn(x0, z0, x1, z1);
        int[][] covered = new int[W][H];
        int badSize = 0, stretched = 0;
        int landmarkBlocks = 0;
        for (WorldLayout.Rect r : blocks) {
            if (layout.landmarkAt(r) != null) {
                landmarkBlocks++;                // a landmark's block is as big as its schematic: no size check
            } else {
                if (r.width() < min || r.width() > max || r.depth() < min || r.depth() > max) {
                    stretched++;                 // flexible blocks: outside the configured range, inside the relaxed one
                }
                if (r.width() < Math.max(2, min - SLACK) || r.width() > max + SLACK || r.depth() < Math.max(2, min - SLACK) || r.depth() > max + SLACK) {
                    badSize++;
                }
            }
            for (int x = Math.max(r.minX, x0); x <= Math.min(r.maxX, x1); x++) {
                for (int z = Math.max(r.minZ, z0); z <= Math.min(r.maxZ, z1); z++) {
                    covered[x - x0][z - z0]++;
                }
            }
        }
        int blockUncovered = 0, blockOverlap = 0, rectNotBlock = 0;
        for (int x = 0; x < W; x++) {
            for (int z = 0; z < H; z++) {
                boolean isBlock = kind[x + 1][z + 1] == WorldLayout.BLOCK;
                if (isBlock && covered[x][z] == 0) blockUncovered++;
                if (covered[x][z] > 1) blockOverlap++;
                if (!isBlock && covered[x][z] > 0) rectNotBlock++;
            }
        }

        boolean[][] bad = new boolean[W][H];
        // 3: 2x2 windows of road holding a street cell
        int doubles = 0;
        for (int x = -1; x < W; x++) {
            for (int z = -1; z < H; z++) {
                int a = kind[x + 1][z + 1], b = kind[x + 2][z + 1], c = kind[x + 1][z + 2], d = kind[x + 2][z + 2];
                if (road(a) && road(b) && road(c) && road(d)
                        && (a == WorldLayout.STREET || b == WorldLayout.STREET || c == WorldLayout.STREET || d == WorldLayout.STREET)) {
                    doubles++;
                    mark(bad, x, z, W, H);
                    mark(bad, x + 1, z, W, H);
                    mark(bad, x, z + 1, W, H);
                    mark(bad, x + 1, z + 1, W, H);
                }
            }
        }

        // 4 + 5: per street cell
        int besideLane = 0, deadEnds = 0, deadEndsAtWater = 0, isolated = 0;
        int[][] dir = {{1, 0}, {-1, 0}, {0, 1}, {0, -1}};
        for (int x = 0; x < W; x++) {
            for (int z = 0; z < H; z++) {
                if (kind[x + 1][z + 1] != WorldLayout.STREET) {
                    continue;
                }
                int roadN = 0;
                boolean nearWater = false;
                boolean[] isRoad = new boolean[4];
                for (int i = 0; i < 4; i++) {
                    int k = kind[x + 1 + dir[i][0]][z + 1 + dir[i][1]];
                    isRoad[i] = road(k);
                    if (isRoad[i]) roadN++;
                    if (k == WorldLayout.ZONE) nearWater = true;
                }
                // street whose run is parallel to a lane beside it: a lane on one side, a street on the perpendicular axis
                boolean beside = false;
                for (int i = 0; i < 4; i++) {
                    if (kind[x + 1 + dir[i][0]][z + 1 + dir[i][1]] == WorldLayout.LANE) {
                        int p = i < 2 ? 2 : 0;                              // the axis the street would run along
                        // a run: the next street chunk along that axis also has a lane on the same side. A single chunk (a
                        // street turning a corner at the end of a lane) is not a street running alongside a highway.
                        for (int q = p; q <= p + 1; q++) {
                            int nx = x + dir[q][0], nz = z + dir[q][1];
                            if (nx >= 0 && nz >= 0 && nx < W && nz < H && kind[nx + 1][nz + 1] == WorldLayout.STREET
                                    && kind[nx + 1 + dir[i][0]][nz + 1 + dir[i][1]] == WorldLayout.LANE) {
                                beside = true;
                            }
                        }
                    }
                }
                if (beside) {
                    besideLane++;
                    bad[x][z] = true;
                }
                if (roadN == 0) {
                    isolated++;
                    bad[x][z] = true;
                } else if (roadN == 1) {
                    if (nearWater) {
                        deadEndsAtWater++;
                    } else {
                        deadEnds++;
                        bad[x][z] = true;
                    }
                }
            }
        }

        // 8: a block must not touch the zone (water or the ground beside it) with no street between: the shore road
        int blockAtWater = 0, blockAtGround = 0;
        boolean[][] shore = new boolean[W][H];
        for (int x = 0; x < W; x++) {
            for (int z = 0; z < H; z++) {
                if (kind[x + 1][z + 1] != WorldLayout.BLOCK) {
                    continue;
                }
                for (int i = 0; i < 4; i++) {
                    int nx = x + dir[i][0], nz = z + dir[i][1];
                    if (kind[nx + 1][nz + 1] == WorldLayout.ZONE) {
                        if (layout.isWater(x0 + nx, z0 + nz)) {
                            blockAtWater++;
                            shore[x][z] = true;
                        } else {
                            blockAtGround++;
                        }
                        break;
                    }
                }
            }
        }

        // 9: a street chunk beside the zone with at most one road neighbour is a shore road that stops dead
        int shoreDeadEnds = 0;
        for (int x = 0; x < W; x++) {
            for (int z = 0; z < H; z++) {
                if (kind[x + 1][z + 1] != WorldLayout.STREET) {
                    continue;
                }
                int roadN = 0;
                boolean zoneSide = false;
                for (int i = 0; i < 4; i++) {
                    int k = kind[x + 1 + dir[i][0]][z + 1 + dir[i][1]];
                    if (road(k)) roadN++;
                    if (k == WorldLayout.ZONE) zoneSide = true;
                }
                if (roadN <= 1 && zoneSide) {
                    shoreDeadEnds++;
                }
            }
        }

        int domainsFailed = layout.failedDomains;

        // 7: order independence
        String det7 = "skipped (det=1 to run)";
        if (det != 0) {
            WorldLayout other = build(seed, min, max, river);
            List<int[]> cells = new ArrayList<int[]>();
            for (int x = 0; x < W; x += 3) {
                for (int z = 0; z < H; z += 3) {
                    cells.add(new int[]{x, z});
                }
            }
            Collections.shuffle(cells, new Random(99));
            int diff = 0;
            for (int[] c : cells) {
                if (other.kindAt(x0 + c[0], z0 + c[1]) != kind[c[0] + 1][c[1] + 1]) diff++;
            }
            det7 = diff == 0 ? "ok (" + cells.size() + " cells, shuffled order)" : "FAIL: " + diff + " of " + cells.size() + " cells differ";
        }

        StringBuilder rep = new StringBuilder();
        rep.append("seed=").append(seed).append(" window=[").append(x0).append("..").append(x1).append(", ").append(z0).append("..").append(z1)
                .append("] block=").append(min).append("..").append(max).append(" water=").append(water).append('\n');
        rep.append(String.format("solve+query time: %.1fs   domains: %d (largest %d super-cells)   exact-search retries: %d%n",
                secs, layout.totalDomains, layout.largestDomain, layout.retries));
        rep.append("cells  lane=").append(count[1]).append(" street=").append(count[2]).append(" block=").append(count[3])
                .append(" zone=").append(count[WorldLayout.ZONE]).append(" unclaimed=").append(unclaimed).append('\n');
        rep.append("blocks in window: ").append(blocks.size()).append('\n');
        rep.append('\n');
        rep.append(row("1  unclaimed chunks", unclaimed));
        rep.append(row("1  block chunks in no rectangle", blockUncovered));
        rep.append(row("1  block chunks in 2+ rectangles", blockOverlap));
        rep.append(row("1  rectangle chunks not BLOCK", rectNotBlock));
        rep.append(row("2  blocks outside size range (relaxed by slack)", badSize));
        rep.append(info("   blocks stretched beyond the configured range", stretched));
        rep.append(info("   landmark blocks reserved", landmarkBlocks));
        rep.append(row("3  2x2 road windows with a street (doubles)", doubles));
        rep.append(row("4  street chunks in a run beside a lane", besideLane));
        rep.append(row("5  dead-end street chunks", deadEnds));
        rep.append(row("5  isolated street chunks", isolated));
        rep.append(info("   street chunks ending at water", deadEndsAtWater));
        rep.append(info("9  shore street chunks with <= 1 road neighbour", shoreDeadEnds));
        rep.append(row("8  block chunks touching water, no street between", blockAtWater));
        rep.append(info("   block chunks touching closed ground (ledge pockets)", blockAtGround));
        rep.append(row("6  failed domains (whole run, not only window)", domainsFailed));
        rep.append("7  order independence: ").append(det7).append('\n');
        for (WorldLayout.Domain d : layout.failures()) {
            rep.append("   failed domain ").append(d.id).append(": chunks x ").append(d.minX).append("..").append(d.maxX)
                    .append(" z ").append(d.minZ).append("..").append(d.maxZ).append(", ").append(d.superCells)
                    .append(" super-cells, ").append(d.attempts).append(" attempts: ").append(d.failNote).append('\n');
        }
        int total = blockAtWater + unclaimed + blockUncovered + blockOverlap + rectNotBlock + badSize + doubles + besideLane + deadEnds + isolated + domainsFailed;
        rep.append('\n').append(total == 0 ? "PASS" : "FAIL").append(" (").append(total).append(" violating chunks/blocks)\n");
        System.out.print(rep);

        if (out != null) {
            PrintWriter pw = new PrintWriter(out);
            pw.print(rep);
            pw.println();
            pw.println("legend: = lane   + street   (space) block   ~ water zone / ground   ? unclaimed   ! violation");
            pw.println("columns are x from " + x0 + ", rows are z from " + z0);
            for (int z = 0; z < H; z++) {
                StringBuilder sb = new StringBuilder(W);
                for (int x = 0; x < W; x++) {
                    sb.append(bad[x][z] ? '!' : shore[x][z] ? 'B' : glyph(kind[x + 1][z + 1]));
                }
                pw.println(sb);
            }
            pw.close();
            System.out.println("plan written to " + out);
        }
        System.exit(total == 0 ? 0 : 1);
    }

    static void mark(boolean[][] bad, int x, int z, int W, int H) {
        if (x >= 0 && z >= 0 && x < W && z < H) {
            bad[x][z] = true;
        }
    }

    static String row(String label, int n) {
        return String.format("%-52s %6d  %s%n", label, n, n == 0 ? "ok" : "VIOLATION");
    }

    static String info(String label, int n) {
        return String.format("%-52s %6d  info%n", label, n);
    }
}