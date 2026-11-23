package ch.k42.metropolis.grid.urbanGrid.layout;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Reclaims contiguous, creek-adjacent *dry* planning cells as ordinary
 * rectangular parcels, each surrounded by a connected street ring.
 *
 * Recovered from the R30ea algorithm. It performs all candidate trials against
 * a copy of the district, then commits only the winning trial; no trial may
 * mutate the live domain while it is evaluated.
 */
final class CreekReclaim {
    private static final int[] DX = {0, 1, 0, -1};
    private static final int[] DZ = {-1, 0, 1, 0};
    private static final int SEARCH_TRIALS = 8;

    private CreekReclaim() { }

    static Result apply(WorldLayout.Domain domain, WaterField water, int minSize, int maxSize) {
        return apply(domain, water, minSize, maxSize, 0L);
    }

    static Result apply(WorldLayout.Domain domain, WaterField water, int minSize, int maxSize, long seed) {
        Result result = new Result();
        if (water == null || !water.hasRiverStyleCreekPlanning()) return result;

        byte[][] source = domain.kind;
        int nx = source.length, nz = source[0].length;
        boolean[][] eligible = new boolean[nx][nz];
        int dryCount = 0;
        for (int x = 0; x < nx; x++) {
            for (int z = 0; z < nz; z++) {
                if (source[x][z] == 4 && water.isCreekPlanningOnlyDryAt(domain.minX + x, domain.minZ + z)) {
                    eligible[x][z] = true;
                    dryCount++;
                }
            }
        }
        if (dryCount == 0) return result;

        List<Candidate> candidates = new ArrayList<>();
        for (int z = 1; z < nz - minSize; z++) {
            for (int x = 1; x < nx - minSize; x++) {
                if (!eligible[x][z]) continue;
                int maxW = Math.min(maxSize, nx - x - 1);
                int maxH = Math.min(maxSize, nz - z - 1);
                for (int w = minSize; w <= maxW; w++) {
                    if (!eligible[x + w - 1][z]) break;
                    for (int h = minSize; h <= maxH; h++) {
                        if (!eligible[x][z + h - 1]) break;
                        if (footprintIsEligible(eligible, x, z, w, h))
                            candidates.add(new Candidate(x, z, w, h));
                    }
                }
            }
        }

        long locationSeed = mix64(seed ^ ((long)domain.minX * -7046029254386353131L)
                                       ^ ((long)domain.minZ * -4417276706812531889L));
        Trial best = null;
        for (int pass = 0; pass < SEARCH_TRIALS; pass++) {
            for (Candidate c : candidates) c.priority = candidateGrade(c, pass, locationSeed, minSize, maxSize);
            candidates.sort(Comparator.comparingDouble((Candidate c) -> -c.priority)
                    .thenComparingInt(c -> c.z).thenComparingInt(c -> c.x)
                    .thenComparingInt(c -> c.h).thenComparingInt(c -> c.w));
            Trial trial = simulate(source, eligible, candidates);
            scoreSizeDiversity(trial, minSize, maxSize);
            if (pass == 0) result.baselineLeftoverDryCells = trial.leftoverDryCells;
            if (best == null || better(trial, best)) best = trial;
        }

        for (int x = 0; x < nx; x++) System.arraycopy(best.kind[x], 0, source[x], 0, nz);
        for (Candidate c : best.blocks) {
            domain.blocks.add(new WorldLayout.Rect(domain.minX + c.x, domain.minZ + c.z,
                    domain.minX + c.x + c.w - 1, domain.minZ + c.z + c.h - 1));
        }
        result.blocks = best.blocks.size();
        result.buildingCells = best.buildingCells;
        result.streetCells = best.streetCells;
        result.leftoverDryCells = best.leftoverDryCells;
        result.trials = SEARCH_TRIALS;
        result.shapeKinds = best.shapeKinds;
        result.smallBlocks = best.smallBlocks;
        result.mediumBlocks = best.mediumBlocks;
        result.largeBlocks = best.largeBlocks;
        result.sizeDiversity = best.sizeDiversity;
        domain.creekReclaimBlocks = result.blocks;
        domain.creekReclaimBuildings = result.buildingCells;
        domain.creekReclaimRoads = result.streetCells;
        domain.creekReclaimUnassignedDry = result.leftoverDryCells;
        return result;
    }

    private static boolean better(Trial a, Trial b) {
        if (a.leftoverDryCells != b.leftoverDryCells) return a.leftoverDryCells < b.leftoverDryCells;
        if (a.sizeDiversity != b.sizeDiversity) return a.sizeDiversity > b.sizeDiversity;
        if (a.shapeKinds != b.shapeKinds) return a.shapeKinds > b.shapeKinds;
        return false;
    }

    private static double candidateGrade(Candidate c, int pass, long seed, int minSize, int maxSize) {
        int range = Math.max(1, maxSize - minSize + 1);
        int spacing = Math.max(10, maxSize + 4);
        int shiftX = pass * 7 % spacing, shiftZ = pass * 11 % spacing;
        int cellX = Math.floorDiv(c.x + c.w / 2 + shiftX, spacing);
        int cellZ = Math.floorDiv(c.z + c.h / 2 + shiftZ, spacing);
        long hashed = mix64(seed ^ (-3372029247567499371L * pass)
                ^ ((long)cellX * -7046029254386353131L)
                ^ ((long)cellZ * -4417276706812531889L));
        int group = Math.floorMod(hashed, 3);
        int lo = minSize + group * range / 3;
        // Groups divide the permitted dimensions into three inclusive bands.
        int hi = Math.min(maxSize, minSize + ((group + 1) * range + 2) / 3 - 1);
        int targetW = lo + Math.floorMod(hashed >>> 9, Math.max(1, hi - lo + 1));
        long hashedZ = mix64(hashed ^ -7723592293110705685L);
        int groupZ = Math.floorMod(hashedZ, 3);
        int loZ = minSize + groupZ * range / 3;
        int hiZ = Math.min(maxSize, minSize + ((groupZ + 1) * range + 2) / 3 - 1);
        int targetH = loZ + Math.floorMod(hashedZ >>> 9, Math.max(1, hiZ - loZ + 1));
        long localHash = mix64(seed ^ ((long)pass * 7146057691288625177L)
                ^ ((long)c.x * -7046029288634856825L)
                ^ ((long)c.z * -2643881736870682267L)
                ^ ((long)c.w << 32) ^ (long)c.h);
        double jitter = (double)(localHash >>> 11) * 0x1.0p-53 - 0.5;
        double fitW = 1.0 - (double)Math.abs(c.w - targetW) / Math.max(1, maxSize - minSize);
        double fitH = 1.0 - (double)Math.abs(c.h - targetH) / Math.max(1, maxSize - minSize);
        double sizeFit = 0.5 * (fitW + fitH);
        double area = (double)c.area / (maxSize * maxSize);
        double[] fitWeight = {2.25, 1.7, 1.15, 2.6};
        double[] areaWeight = {1.0, 1.65, 2.35, 0.65};
        return fitWeight[pass % 4] * sizeFit + areaWeight[pass % 4] * area + 0.28 * jitter;
    }

    private static void scoreSizeDiversity(Trial trial, int minSize, int maxSize) {
        Set<Long> shapes = new HashSet<>();
        double maxArea = Math.max(1.0, (double)maxSize * maxSize);
        double minArea = (double)minSize * minSize;
        for (Candidate c : trial.blocks) {
            shapes.add(((long)Math.min(c.w, c.h) << 32) | (long)Math.max(c.w, c.h));
            double normalized = (c.area - minArea) / Math.max(1.0, maxArea - minArea);
            if (normalized < 1.0 / 3.0) trial.smallBlocks++;
            else if (normalized < 2.0 / 3.0) trial.mediumBlocks++;
            else trial.largeBlocks++;
        }
        trial.shapeKinds = shapes.size();
        double n = trial.blocks.size();
        if (n > 0.0) {
            double a = trial.smallBlocks / n, b = trial.mediumBlocks / n, c = trial.largeBlocks / n;
            trial.sizeDiversity = 1.0 - (a*a + b*b + c*c);
        }
    }

    private static Trial simulate(byte[][] source, boolean[][] initialEligible, List<Candidate> candidates) {
        int nx = source.length, nz = source[0].length;
        byte[][] kind = new byte[nx][nz];
        boolean[][] available = new boolean[nx][nz];
        boolean[][] connected = new boolean[nx][nz];
        ArrayDeque<int[]> floodQueue = new ArrayDeque<>();
        for (int x = 0; x < nx; x++) {
            System.arraycopy(source[x], 0, kind[x], 0, nz);
            System.arraycopy(initialEligible[x], 0, available[x], 0, nz);
            for (int z = 0; z < nz; z++) {
                if (road(kind[x][z]) && (x == 0 || z == 0 || x == nx - 1 || z == nz - 1 || kind[x][z] == 1)) {
                    connected[x][z] = true;
                    floodQueue.add(new int[]{x, z});
                }
            }
        }
        flood(kind, connected, floodQueue);
        Trial trial = new Trial(kind);
        for (int pass = 0; pass < 3; pass++) {
            int additions = 0;
            for (Candidate c : candidates) {
                if (!footprintIsEligible(available, c.x, c.z, c.w, c.h)) continue;
                if (!validRing(kind, available, connected, c)) continue;
                for (int x = c.x; x < c.x + c.w; x++) {
                    for (int z = c.z; z < c.z + c.h; z++) {
                        kind[x][z] = 3;
                        available[x][z] = false;
                    }
                }
                for (int x = c.x - 1; x <= c.x + c.w; x++) {
                    for (int z = c.z - 1; z <= c.z + c.h; z++) {
                        if (x != c.x - 1 && x != c.x + c.w && z != c.z - 1 && z != c.z + c.h) continue;
                        if (available[x][z]) {
                            kind[x][z] = 2;
                            available[x][z] = false;
                            trial.streetCells++;
                        }
                        if (road(kind[x][z])) {
                            connected[x][z] = true;
                            floodQueue.add(new int[]{x, z});
                        }
                    }
                }
                flood(kind, connected, floodQueue);
                trial.blocks.add(c);
                trial.buildingCells += c.area;
                additions++;
            }
            if (additions == 0) break;
        }
        for (boolean[] row : available) for (boolean v : row) if (v) trial.leftoverDryCells++;
        return trial;
    }

    private static long mix64(long x) {
        x ^= x >>> 30;
        x *= -4658895280553007687L;
        x ^= x >>> 27;
        x *= -7723592293110705685L;
        x ^= x >>> 31;
        return x;
    }

    private static boolean footprintIsEligible(boolean[][] available, int x, int z, int w, int h) {
        for (int i = x; i < x + w; i++) for (int j = z; j < z + h; j++) if (!available[i][j]) return false;
        return true;
    }

    private static boolean road(byte kind) { return kind == 2 || kind == 1; }

    private static boolean validRing(byte[][] kind, boolean[][] available, boolean[][] connected, Candidate c) {
        int connectedEdges = 0;
        int nx = kind.length, nz = kind[0].length;
        for (int x = c.x - 1; x <= c.x + c.w; x++) {
            for (int z = c.z - 1; z <= c.z + c.h; z++) {
                if (x != c.x - 1 && x != c.x + c.w && z != c.z - 1 && z != c.z + c.h) continue;
                if (x < 0 || z < 0 || x >= nx || z >= nz) return false;
                if (road(kind[x][z])) {
                    if (connected[x][z]) connectedEdges++;
                    continue;
                }
                if (!available[x][z]) return false;
                for (int i = 0; i < 4; i++) {
                    int ax = x + DX[i], az = z + DZ[i];
                    if (ax < 0 || az < 0 || ax >= nx || az >= nz || !road(kind[ax][az])) continue;
                    if (ax >= c.x - 1 && ax <= c.x + c.w && az >= c.z - 1 && az <= c.z + c.h) continue;
                    int px = DZ[i], pz = DX[i];
                    if (ax + px >= 0 && az + pz >= 0 && ax + px < nx && az + pz < nz && road(kind[ax + px][az + pz])) return false;
                    if (ax - px >= 0 && az - pz >= 0 && ax - px < nx && az - pz < nz && road(kind[ax - px][az - pz])) return false;
                }
            }
        }
        return connectedEdges > 0;
    }

    private static void flood(byte[][] kind, boolean[][] connected, ArrayDeque<int[]> queue) {
        int nx = kind.length, nz = kind[0].length;
        while (!queue.isEmpty()) {
            int[] item = queue.poll();
            for (int i = 0; i < 4; i++) {
                int x = item[0] + DX[i], z = item[1] + DZ[i];
                if (x < 0 || z < 0 || x >= nx || z >= nz || connected[x][z] || !road(kind[x][z])) continue;
                connected[x][z] = true;
                queue.add(new int[]{x,z});
            }
        }
    }

    static final class Candidate {
        final int x, z, w, h, area;
        double priority;
        Candidate(int x, int z, int w, int h) {
            this.x = x; this.z = z; this.w = w; this.h = h; this.area = w * h;
        }
    }

    static final class Result {
        int blocks, buildingCells, streetCells, leftoverDryCells;
        int trials, baselineLeftoverDryCells, shapeKinds;
        int smallBlocks, mediumBlocks, largeBlocks;
        double sizeDiversity;

        @Override public String toString() {
            return "blocks=" + blocks + " building=" + buildingCells + " streets=" + streetCells
                    + " remainingDry=" + leftoverDryCells + " firstTrialRemainingDry=" + baselineLeftoverDryCells
                    + " trials=" + trials + " shapes=" + shapeKinds + " sizeBands=" + smallBlocks
                    + "/" + mediumBlocks + "/" + largeBlocks + " diversity=" + sizeDiversity;
        }
    }

    static final class Trial {
        final byte[][] kind;
        final List<Candidate> blocks = new ArrayList<>();
        int buildingCells, streetCells, leftoverDryCells, shapeKinds;
        int smallBlocks, mediumBlocks, largeBlocks;
        double sizeDiversity;
        Trial(byte[][] kind) { this.kind = kind; }
    }
}
