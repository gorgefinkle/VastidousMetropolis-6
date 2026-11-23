package ch.k42.metropolis.grid.urbanGrid.highway;

import ch.k42.metropolis.grid.urbanGrid.ocean.OceanProvider;
import java.util.concurrent.ConcurrentHashMap;

/** Ocean/shore buffer for highway selection, recovered from R30ea. */
public final class OceanHighwaySafety implements HighwayNetwork.Mask {
    private final OceanProvider ocean;
    private final ConcurrentHashMap<Long, Boolean> cache = new ConcurrentHashMap<>();
    private final double cx, cz, boundX, boundZ;
    private static final double BANK_CLEARANCE = 32.0;

    public OceanHighwaySafety(OceanProvider ocean) {
        this.ocean = ocean;
        this.cx = ocean.getCenterChunkX() + 0.5;
        this.cz = ocean.getCenterChunkZ() + 0.5;
        this.boundX = ocean.getEnvelopeRadiusChunksX() + 2.0;
        this.boundZ = ocean.getEnvelopeRadiusChunksZ() + 2.0;
    }

    @Override
    public boolean at(int x, int z) {
        if (!ocean.isEnabled() || Math.abs(x - cx) > boundX || Math.abs(z - cz) > boundZ) return false;
        long key = ((long)x << 32) ^ (z & 0xffffffffL);
        Boolean known = cache.get(key);
        if (known != null) return known;
        boolean wet = hitsCoast(x,z);
        cache.putIfAbsent(key, wet);
        return wet;
    }

    private boolean wetOrBank(double x, double z) {
        double signed = ocean.oceanDistanceInsideShoreBlock(x,z);
        if (!Double.isNaN(signed) && signed > -BANK_CLEARANCE) return true;
        return ocean.hasRivers() && ocean.hasSeaInfluence(x,z)
                && ocean.distanceInsideShoreBlock(x,z) > -BANK_CLEARANCE;
    }

    private boolean hitsCoast(int chunkX, int chunkZ) {
        double bx=chunkX*16.0,bz=chunkZ*16.0;
        if (wetOrBank(bx+8.0,bz+8.0)) return true;
        if (wetOrBank(bx,bz) || wetOrBank(bx+15.0,bz) || wetOrBank(bx,bz+15.0)
                || wetOrBank(bx+15.0,bz+15.0)) return true;
        for(int z=0;z<16;z++){
            for(int x=0;x<16;x++){
                if ((x==0||x==15) && (z==0||z==15)) continue;
                if(x==8 && z==8) continue;
                if(wetOrBank(bx+x,bz+z)) return true;
            }
        }
        return false;
    }
}
