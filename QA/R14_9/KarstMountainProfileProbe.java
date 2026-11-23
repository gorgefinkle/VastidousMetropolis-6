package ch.k42.metropolis.grid.urbanGrid.forest;

import java.util.Arrays;
import java.util.Random;

/** Checks that karst is an elevated massif with deep cuts near the build datum. */
public final class KarstMountainProfileProbe {
    private static double median(double[] values) {
        Arrays.sort(values);
        return values[values.length / 2];
    }
    private static void check(boolean ok, String message) {
        if (!ok) throw new AssertionError(message);
    }
    public static void main(String[] args) {
        long[] seeds = {
            -1482741523194359869L,
            1750414970189483479L,
            1414199025253064524L
        };
        for (long seed : seeds) {
            Random random = new Random(seed ^ 0x4b415253544d544eL);
            double[] uplands = new double[5000];
            double[] cuts = new double[5000];
            double[] shoulderDeltas = new double[5000];
            int up = 0, cut = 0, shoulder = 0;
            int buildableUplands = 0;
            double minUpland = Double.POSITIVE_INFINITY;
            double maxUpland = Double.NEGATIVE_INFINITY;
            double minCut = Double.POSITIVE_INFINITY;
            double maxCut = Double.NEGATIVE_INFINITY;
            for (int i = 0; i < 240000 && (up < uplands.length || cut < cuts.length || shoulder < shoulderDeltas.length); i++) {
                int x = random.nextInt(400001) - 200000;
                int z = random.nextInt(400001) - 200000;
                double v = KarstLandforms.valley(seed, x, z);
                double relief = KarstLandforms.unbridgedRelief(seed, x, z);
                if (v < .08 && up < uplands.length) {
                    uplands[up++] = relief;
                    minUpland = Math.min(minUpland, relief);
                    maxUpland = Math.max(maxUpland, relief);
                    double maxNeighbourDelta = Math.max(
                            Math.max(Math.abs(relief-KarstLandforms.unbridgedRelief(seed,x+16,z)),
                                     Math.abs(relief-KarstLandforms.unbridgedRelief(seed,x-16,z))),
                            Math.max(Math.abs(relief-KarstLandforms.unbridgedRelief(seed,x,z+16)),
                                     Math.abs(relief-KarstLandforms.unbridgedRelief(seed,x,z-16))));
                    if (maxNeighbourDelta <= 6.0) buildableUplands++;
                } else if (v > .88 && cut < cuts.length) {
                    cuts[cut++] = relief;
                    minCut = Math.min(minCut, relief);
                    maxCut = Math.max(maxCut, relief);
                }
                if (v > .18 && v < .82 && shoulder < shoulderDeltas.length) {
                    double delta = Math.max(
                            Math.max(Math.abs(relief-KarstLandforms.unbridgedRelief(seed,x+16,z)),
                                     Math.abs(relief-KarstLandforms.unbridgedRelief(seed,x-16,z))),
                            Math.max(Math.abs(relief-KarstLandforms.unbridgedRelief(seed,x,z+16)),
                                     Math.abs(relief-KarstLandforms.unbridgedRelief(seed,x,z-16))));
                    shoulderDeltas[shoulder++] = delta;
                }
            }
            check(up == uplands.length && cut == cuts.length,
                    "insufficient upland/cut samples for seed " + seed + " up=" + up + " cuts=" + cut);
            double uplandMedian = median(uplands);
            double cutMedian = median(cuts);
            check(uplandMedian >= 70.0,
                    "karst uplands are not mountain-height: median relief=" + uplandMedian);
            check(cutMedian >= -8.0 && cutMedian <= 8.0,
                    "inner cut floor is not near build-height datum: median relief=" + cutMedian);
            check(uplandMedian - cutMedian >= 75.0,
                    "karst lacks deep mountain-to-valley relief: " + (uplandMedian-cutMedian));
            double buildableShare = buildableUplands / (double) uplands.length;
            check(shoulder >= shoulderDeltas.length, "insufficient shoulder samples for seed " + seed + ": " + shoulder);
            Arrays.sort(shoulderDeltas);
            double shoulderP95 = shoulderDeltas[(int)(shoulderDeltas.length * 0.95)];
            check(shoulderP95 <= 38.0, "karst shoulder profile remains too cliff-like: p95 16-block height delta=" + shoulderP95);
            check(buildableShare >= 0.15,
                    "too little broad, low-gradient upland for construction: " + buildableShare);
            System.out.println("seed=" + seed + " uplandMedian=" + uplandMedian
                    + " innerCutMedian=" + cutMedian + " reliefSpan=" + (uplandMedian-cutMedian)
                    + " buildableUpland=" + String.format(java.util.Locale.ROOT,"%.1f%%",100.0*buildableShare)
                    + " shoulderP95_16blockDelta=" + shoulderP95
                    + " uplandRange=" + minUpland + ".." + maxUpland
                    + " cutRange=" + minCut + ".." + maxCut);
        }
        System.out.println("KarstMountainProfileProbe: PASS (mountain uplands; inner cuts near build-height datum)");
    }
}
