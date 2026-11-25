import ch.k42.metropolis.grid.urbanGrid.wild.SpatialCache;
import java.util.HashSet;
import java.util.concurrent.ConcurrentHashMap;
public final class SpatialCacheProbe {
    public static void main(String[] args) {
        HashSet<Long> keys = new HashSet<>();
        for (int x = -512; x <= 512; x += 3)
            for (int z = -512; z <= 512; z += 5)
                if (!keys.add(SpatialCache.key(x, z)))
                    throw new AssertionError("coordinate key collision at " + x + "," + z);
        ConcurrentHashMap<Long, Integer> cache = new ConcurrentHashMap<>();
        for (int i = 0; i < 2000; i++) cache.put(SpatialCache.key(i, -i), i);
        SpatialCache.prune(cache, 256, 192);
        if (cache.size() > 192 || cache.isEmpty())
            throw new AssertionError("incremental cache prune failed: " + cache.size());
        System.out.println("SpatialCacheProbe PASS: " + keys.size()
                + " unique keys; cache pruned to " + cache.size());
    }
}
