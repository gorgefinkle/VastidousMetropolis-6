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
            int up = 0, cut = 0;
            double minUpland = Double.POSITIVE_INFINITY;
            double maxUpland = Double.NEGATIVE_INFINITY;
            double minCut = Double.POSITIVE_INFINITY;
            double maxCut = Double.NEGATIVE_INFINITY;
            for (int i = 0; i < 180000 && (up < uplands.length || cut < cuts.length); i++) {
                int x = random.nextInt(400001) - 200000;
                int z = random.nextInt(400001) - 200000;
                double v = KarstLandforms.valley(seed, x, z);
                double relief = KarstLandforms.unbridgedRelief(seed, x, z);
                if (v < .08 && up < uplands.length) {
                    uplands[up++] = relief;
                    minUpland = Math.min(minUpland, relief);
                    maxUpland = Math.max(maxUpland, relief);
                } else if (v > .88 && cut < cuts.length) {
                    cuts[cut++] = relief;
                    minCut = Math.min(minCut, relief);
                    maxCut = Math.max(maxCut, relief);
                }
            }
            check(up == uplands.length && cut == cuts.length,
                    "insufficient upland/cut samples for seed " + seed + " up=" + up + " cuts=" + cut);
            double uplandMedian = median(uplands);
            double cutMedian = median(cuts);
            check(uplandMedian >= 48.0,
                    "karst uplands are not mountain-height: median relief=" + uplandMedian);
            check(cutMedian >= -8.0 && cutMedian <= 8.0,
                    "inner cut floor is not near build-height datum: median relief=" + cutMedian);
            check(uplandMedian - cutMedian >= 52.0,
                    "karst lacks deep mountain-to-valley relief: " + (uplandMedian-cutMedian));
            System.out.println("seed=" + seed + " uplandMedian=" + uplandMedian
                    + " innerCutMedian=" + cutMedian + " reliefSpan=" + (uplandMedian-cutMedian)
                    + " uplandRange=" + minUpland + ".." + maxUpland
                    + " cutRange=" + minCut + ".." + maxCut);
        }
        System.out.println("KarstMountainProfileProbe: PASS (mountain uplands; inner cuts near build-height datum)");
    }
}
