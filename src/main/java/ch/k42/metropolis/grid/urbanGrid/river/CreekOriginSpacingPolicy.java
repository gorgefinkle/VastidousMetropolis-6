package ch.k42.metropolis.grid.urbanGrid.river;

/**
 * Deterministic global spacing arbitration for creek origins. City uniqueness
 * remains a separate rule; this policy prevents nearby cities from placing
 * their sole origin parks beside one another.
 */
public final class CreekOriginSpacingPolicy {
    private CreekOriginSpacingPolicy() {}

    /** True when two origin points violate the requested Euclidean chunk berth. */
    public static boolean tooClose(int ax, int az, int bx, int bz, int minimumChunks) {
        if (minimumChunks <= 0) return false;
        long dx = (long) ax - bx;
        long dz = (long) az - bz;
        long minimumSquared = (long) minimumChunks * minimumChunks;
        return dx * dx + dz * dz < minimumSquared;
    }

    /**
     * Resolve a conflict independently of evaluation order. Unsigned site rank
     * chooses the winner; host-tile key is a stable tie-breaker.
     */
    public static boolean outranks(long rankA, long tileKeyA, long rankB, long tileKeyB) {
        int rankOrder = Long.compareUnsigned(rankA, rankB);
        return rankOrder < 0 || (rankOrder == 0
                && Long.compareUnsigned(tileKeyA, tileKeyB) < 0);
    }

    /** Candidate A remains eligible against B when they do not conflict or A wins the arbitration. */
    public static boolean winsAgainst(int ax, int az, long rankA, long tileKeyA,
                                      int bx, int bz, long rankB, long tileKeyB,
                                      int minimumChunks) {
        return !tooClose(ax, az, bx, bz, minimumChunks)
                || outranks(rankA, tileKeyA, rankB, tileKeyB);
    }
}
