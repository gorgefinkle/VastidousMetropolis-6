package ch.k42.metropolis.grid.urbanGrid.river;

/** Pure regression probe for global creek-origin berth arbitration. */
public final class CreekOriginSpacingPolicyProbe {
    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }

    public static void main(String[] args) {
        final int berth = 120;

        check(CreekOriginSpacingPolicy.tooClose(0, 0, 119, 0, berth),
                "119 chunks must violate a 120-chunk berth");
        check(!CreekOriginSpacingPolicy.tooClose(0, 0, 120, 0, berth),
                "exactly 120 chunks must be permitted");
        check(CreekOriginSpacingPolicy.tooClose(0, 0, 84, 84, berth),
                "diagonal origins inside the Euclidean radius must conflict");
        check(!CreekOriginSpacingPolicy.tooClose(0, 0, 85, 85, berth),
                "diagonal origins outside the Euclidean radius must not conflict");
        check(!CreekOriginSpacingPolicy.tooClose(-40, 12, 500, -600, 0),
                "zero must disable the spacing policy");

        long cityA = CreekCityOriginPolicy.cityKey(10, 20);
        long cityB = CreekCityOriginPolicy.cityKey(11, 20);
        check(cityA != cityB, "test setup must use two distinct adjacent city identities");

        long rankA = 0x100L, rankB = 0x200L;
        long tileA = CreekCityOriginPolicy.tileKey(0, 0);
        long tileB = CreekCityOriginPolicy.tileKey(1, 0);
        check(CreekOriginSpacingPolicy.winsAgainst(100, 100, rankA, tileA,
                        130, 100, rankB, tileB, berth),
                "lower-ranked origin should win a close cross-city conflict");
        check(!CreekOriginSpacingPolicy.winsAgainst(130, 100, rankB, tileB,
                        100, 100, rankA, tileA, berth),
                "higher-ranked origin should lose the same cross-city conflict");
        check(!CreekOriginSpacingPolicy.winsAgainst(100, 100, rankB, tileB,
                        100, 100, rankA, tileA, berth),
                "a losing same-city-independent spacing candidate must be rejected consistently");

        check(CreekOriginSpacingPolicy.outranks(7L, tileA, 7L, tileB)
                        != CreekOriginSpacingPolicy.outranks(7L, tileB, 7L, tileA),
                "tile key must break equal-rank ties deterministically");
        check(CreekOriginSpacingPolicy.winsAgainst(0, 0, rankB, tileB,
                        120, 0, rankA, tileA, berth),
                "separated candidates must not suppress one another");

        System.out.println("Creek-origin spacing policy: PASS (120-chunk Euclidean berth, cross-city rank arbitration)");
    }
}
