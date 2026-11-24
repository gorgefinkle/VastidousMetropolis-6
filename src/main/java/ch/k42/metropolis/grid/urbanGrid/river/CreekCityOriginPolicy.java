package ch.k42.metropolis.grid.urbanGrid.river;

/**
 * Deterministic one-creek-origin-per-city arbitration. Creek-origin parks are
 * fixed landmarks and must not bypass the ordinary landmark uniqueness rule.
 */
public final class CreekCityOriginPolicy {
    private CreekCityOriginPolicy() {}

    /** Winner host-tile key when a city has no eligible creek-origin candidates. */
    public static final long NO_WINNER = Long.MIN_VALUE;

    public static long cityKey(int cityA,int cityB) {
        return ((long)cityA<<32)^(cityB&0xffffffffL);
    }

    public static long tileKey(int tileX,int tileZ) {
        return ((long)tileX<<32)^(tileZ&0xffffffffL);
    }

    /**
     * Choose one candidate tile per city, independent of enumeration order.
     * Rank is compared unsigned, matching BoundedRiverSites' existing
     * deterministic overlap arbitration; tile key breaks the vanishingly
     * unlikely rank tie.
     */
    public static long winnerTileKey(long targetCityKey,long[] cityKeys,long[] ranks,long[] tileKeys) {
        if(cityKeys==null||ranks==null||tileKeys==null
                ||cityKeys.length!=ranks.length||ranks.length!=tileKeys.length)
            throw new IllegalArgumentException("City keys, ranks and tile keys must have equal lengths");
        boolean found=false;
        long bestRank=0,bestTile=NO_WINNER;
        for(int i=0;i<cityKeys.length;i++) {
            if(cityKeys[i]!=targetCityKey)continue;
            if(!found || Long.compareUnsigned(ranks[i],bestRank)<0
                    || (ranks[i]==bestRank&&Long.compareUnsigned(tileKeys[i],bestTile)<0)) {
                found=true;bestRank=ranks[i];bestTile=tileKeys[i];
            }
        }
        return found?bestTile:NO_WINNER;
    }
}
