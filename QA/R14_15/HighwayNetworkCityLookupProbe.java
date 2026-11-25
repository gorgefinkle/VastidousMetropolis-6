package ch.k42.metropolis.grid.urbanGrid.highway;

/** Confirms city lookup resolves the same owner from every part of a generated city box. */
public final class HighwayNetworkCityLookupProbe {
    public static void main(String[] args) {
        long[] seeds={0L,-1482741523194359869L,1750414970189483479L};
        int checked=0;
        for(long seed:seeds) {
            HighwayNetwork network=new HighwayNetwork(seed,160.0,44.0,0.18,1.0,0.0);
            for(int a=-8;a<=8;a++)for(int b=-8;b<=8;b++) {
                HighwayNetwork.City city=network.city(a,b);
                if(!city.present)continue;
                int[][] points={
                    {city.cx,city.cz},{city.x0,city.z0},{city.x0,city.z1},
                    {city.x1,city.z0},{city.x1,city.z1}
                };
                for(int[] p:points) {
                    HighwayNetwork.City found=network.cityContaining(p[0],p[1]);
                    if(found==null||found.a!=city.a||found.b!=city.b)
                        throw new AssertionError("City lookup disagrees for city ("+a+","+b+") at "+p[0]+","+p[1]);
                }
                checked++;
            }
        }
        if(checked<100)throw new AssertionError("Too few city boxes checked: "+checked);
        System.out.println("HighwayNetworkCityLookupProbe: PASS (city centres and all box corners; positive and negative coordinates; cities="+checked+")");
    }
}
