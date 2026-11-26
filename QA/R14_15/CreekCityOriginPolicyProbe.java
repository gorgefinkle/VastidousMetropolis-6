package ch.k42.metropolis.grid.urbanGrid.river;

/** Standalone regression tests for deterministic per-city creek-origin arbitration. */
public final class CreekCityOriginPolicyProbe {
    public static void main(String[] args) {
        long cityA=CreekCityOriginPolicy.cityKey(-2,7);
        long cityB=CreekCityOriginPolicy.cityKey(3,-4);
        long[] cities={cityA,cityA,cityB,cityA,cityB};
        long[] ranks={-1L,4L,2L,0x7fffffffffffffffL,1L};
        long[] tiles={
                CreekCityOriginPolicy.tileKey(-1,0),
                CreekCityOriginPolicy.tileKey(0,0),
                CreekCityOriginPolicy.tileKey(2,1),
                CreekCityOriginPolicy.tileKey(1,0),
                CreekCityOriginPolicy.tileKey(3,1)};
        long winnerA=CreekCityOriginPolicy.winnerTileKey(cityA,cities,ranks,tiles);
        long winnerB=CreekCityOriginPolicy.winnerTileKey(cityB,cities,ranks,tiles);
        if(winnerA!=tiles[1])throw new AssertionError("City A chose the wrong unsigned rank");
        if(winnerB!=tiles[4])throw new AssertionError("City B chose the wrong unsigned rank");
        long[] reversedCities=new long[cities.length],reversedRanks=new long[ranks.length],reversedTiles=new long[tiles.length];
        for(int i=0;i<cities.length;i++){
            int j=cities.length-1-i;
            reversedCities[i]=cities[j];reversedRanks[i]=ranks[j];reversedTiles[i]=tiles[j];
        }
        if(CreekCityOriginPolicy.winnerTileKey(cityA,reversedCities,reversedRanks,reversedTiles)!=winnerA)
            throw new AssertionError("Winner changed with candidate enumeration order");
        if(CreekCityOriginPolicy.winnerTileKey(CreekCityOriginPolicy.cityKey(99,99),cities,ranks,tiles)
                !=CreekCityOriginPolicy.NO_WINNER)
            throw new AssertionError("Missing city should have no winner");
        boolean rejected=false;
        try { CreekCityOriginPolicy.winnerTileKey(cityA,new long[]{cityA},new long[0],new long[]{1}); }
        catch(IllegalArgumentException expected) { rejected=true; }
        if(!rejected)throw new AssertionError("Unequal arrays were accepted");
        System.out.println("CreekCityOriginPolicyProbe: PASS (one winner per city, unsigned stable ranking, order independent)");
    }
}
