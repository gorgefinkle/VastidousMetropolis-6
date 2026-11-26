package ch.k42.metropolis.grid.urbanGrid.wild;

/** Verifies the hard inland buffer and smooth coastal fade for karst. */
public final class KarstCoastalExclusionProbe {
    private static void check(boolean ok, String message) {
        if (!ok) throw new AssertionError(message);
    }
    public static void main(String[] args) {
        check(KarstCoastalExclusion.factor(Double.NaN) == 1.0,
                "disabled/unavailable ocean must not suppress karst");
        check(KarstCoastalExclusion.factor(1.0) == 0.0,
                "open ocean must never host karst");
        check(KarstCoastalExclusion.factor(0.0) == 0.0,
                "shoreline must never host karst");
        check(KarstCoastalExclusion.factor(-1.0) == 0.0,
                "immediate coastline must never host karst");
        check(KarstCoastalExclusion.factor(-256.0) == 0.0,
                "karst must remain excluded for first 512 inland blocks");
        check(KarstCoastalExclusion.factor(-512.0) == 0.0,
                "hard exclusion limit must be inclusive");
        double quarter = KarstCoastalExclusion.factor(-640.0);
        double midpoint = KarstCoastalExclusion.factor(-768.0);
        double threeQuarter = KarstCoastalExclusion.factor(-896.0);
        check(quarter > 0.0 && quarter < midpoint,
                "coastal fade must rise smoothly after hard exclusion");
        check(Math.abs(midpoint - 0.5) < 1e-9,
                "midpoint of fade must be half strength");
        check(threeQuarter > midpoint && threeQuarter < 1.0,
                "coastal fade must remain monotonic");
        check(KarstCoastalExclusion.factor(-1024.0) == 1.0,
                "karst must recover full suitability 1024 blocks inland");
        double previous = 0.0;
        for (int inland = 513; inland <= 1024; inland++) {
            double value = KarstCoastalExclusion.factor(-inland);
            check(value >= previous, "coastal fade is not monotonic at " + inland);
            previous = value;
        }
        System.out.println("KarstCoastalExclusionProbe: PASS (zero karst within 512 blocks of ocean; smooth fade to full suitability at 1024 blocks)");
    }
}
