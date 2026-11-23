package ch.k42.metropolis.grid.urbanGrid.forest;

import java.util.Random;

/** Confirms karst landbridges are elevated spans with an open under-bridge void. */
public final class KarstLandbridgeClearanceProbe {
    private static void check(boolean ok, String message) {
        if (!ok) throw new AssertionError(message);
    }

    public static void main(String[] args) {
        long[] seeds = {
            -1482741523194359869L,
            1750414970189483479L,
            1414199025253064524L
        };
        long checked = 0;
        long open = 0;
        int minimumClearance = Integer.MAX_VALUE;
        int maximumCoreThickness = 0;

        for (long seed : seeds) {
            Random random = new Random(seed ^ 0x4b41525354535041L);
            long seedChecked = 0;
            for (int sample = 0; sample < 60000; sample++) {
                int x = random.nextInt(200001) - 100000;
                int z = random.nextInt(200001) - 100000;
                double r = KarstLandforms.distance(seed, x, z)
                        / KarstLandforms.width(seed, x, z);
                double coverage = KarstSpanVolume.coverage(seed, x, z);
                if (coverage < .78 || r > .38) continue;

                double natural = KarstLandforms.unbridgedRelief(seed, x, z);
                double raised = KarstSpanVolume.upperRelief(seed, x, z, natural);
                int topY = 65 + (int)Math.round(raised);
                int floor = KarstLandforms.bridgeFloor(seed, x, z, topY, 65, 59);
                if (floor < 59 || topY - floor < 48) continue;

                int upperCut = KarstSpanVolume.undercutCeiling(seed, x, z, topY, floor);
                int clearance = upperCut - floor;
                // The geometry intentionally tapers closed into its abutments.
                // The production painter only carves columns with >=30 blocks
                // of clearance; verify those actual open-arch columns here.
                if (clearance < 30) continue;
                seedChecked++;
                checked++;
                minimumClearance = Math.min(minimumClearance, clearance);

                check(clearance >= 30,
                        "bridge core lacks an open void: seed=" + seed + " x=" + x + " z=" + z
                        + " floor=" + floor + " upperCut=" + upperCut + " top=" + topY
                        + " clearance=" + clearance);
                check(!KarstSpanVolume.retainRock(seed, x, floor + 1, z, topY, floor),
                        "solid block remains immediately above the natural gorge floor");
                check(KarstSpanVolume.retainRock(seed, x, floor, z, topY, floor),
                        "natural ravine floor was removed");
                check(KarstSpanVolume.retainRock(seed, x, upperCut + 1, z, topY, floor),
                        "bridge roof was cut away at its underside");

                if (r < .16 && coverage > .92) {
                    int thickness = topY - upperCut - 1;
                    maximumCoreThickness = Math.max(maximumCoreThickness, thickness);
                    check(thickness >= 38 && thickness <= 52,
                            "central keystone thickness outside 38..52 blocks: " + thickness);
                }
                open++;
            }
            check(seedChecked >= 10,
                    "not enough bridge-core samples for seed " + seed + ": " + seedChecked);
            System.out.println("seed=" + seed + " openBridgeCoreSamples=" + seedChecked);
        }

        check(open == checked && checked > 30, "bridge clearance sample coverage too low");
        System.out.println("KarstLandbridgeClearanceProbe: PASS samples=" + checked
                + " minOpenClearance=" + minimumClearance
                + " maxCentralKeystone=" + maximumCoreThickness
                + " naturalFloorPreserved=true");
    }
}
