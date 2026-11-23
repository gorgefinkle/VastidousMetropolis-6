package ch.k42.metropolis.grid.urbanGrid.layout;
import ch.k42.metropolis.grid.urbanGrid.enums.ContextType;

/** Regression for corner-before-frontage ordering found on seed 13. */
public final class CornerFrontageOrderProbe {
    private static void put(WorldLayout.Domain d, int x, int z, int type) {
        d.kind[x - d.minX][z - d.minZ] = (byte) type;
    }
    private static void check(boolean ok, String message) {
        if (!ok) throw new AssertionError(message);
    }
    public static void main(String[] ignored) {
        WorldLayout.Domain d = new WorldLayout.Domain(1, 0, 0, 25, 25, 64);
        WorldLayout.Rect forest = new WorldLayout.Rect(5, 5, 9, 8);
        d.blocks.add(forest);
        d.contexts.put(forest, ContextType.FOREST);
        for (int x = 5; x <= 9; x++) for (int z = 5; z <= 8; z++) put(d, x, z, WorldLayout.BLOCK);
        for (int z = 4; z <= 8; z++) put(d, 10, z, WorldLayout.STREET);
        put(d, 9, 9, WorldLayout.STREET);
        put(d, 10, 9, WorldLayout.ZONE); // unfinished corner at end of second arm
        // The neighboring domain owns a fixed vertical road on x=11.
        DoubleFrontageRoads.FixedBoundary fixed = (x, z) -> x == 11 && z >= 4 && z <= 9;
        check(DoubleFrontageRoads.prevent(d, 17, fixed) == 0,
                "Pre-corner optimization unexpectedly moved required face");
        // R14.4 did this after frontage optimization; R14.5 does it first.
        put(d, 10, 9, WorldLayout.STREET);
        check(DoubleFrontageRoads.prevent(d, 17, fixed) == 4,
                "Corner reservation must unlock absorption of exactly four redundant road cells");
        check(d.blocks.size() == 1 && d.blocks.get(0).maxX == 10,
                "Expanded district must remain one rectangle");
        for (int z = 5; z <= 8; z++) {
            check(d.kindAt(10, z) == WorldLayout.BLOCK,
                    "Redundant inner road survived at 10," + z);
            check(fixed.at(11, z), "Fixed neighboring road lost at 11," + z);
        }
        check(d.kindAt(10,4) == WorldLayout.STREET && d.kindAt(10,9) == WorldLayout.STREET,
                "End connections lost");
        check(DoubleFrontageRoads.prevent(d,17,fixed)==0,"Optimization not idempotent");
        System.out.println("PASS corner-before-frontage order: no double-width road; both ends preserved");
    }
}
