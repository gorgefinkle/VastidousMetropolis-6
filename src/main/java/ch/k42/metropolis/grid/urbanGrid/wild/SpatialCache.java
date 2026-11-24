package ch.k42.metropolis.grid.urbanGrid.wild;

import java.util.Iterator;
import java.util.concurrent.ConcurrentHashMap;

public final class SpatialCache {
    private SpatialCache() {}
    /** Bijective 64-bit coordinate mix avoids x XOR z hash collision bands. */
    public static long key(int x, int z) {
        long value = ((long) x << 32) | (z & 0xffffffffL);
        value ^= value >>> 30;
        value *= 0xbf58476d1ce4e5b9L;
        value ^= value >>> 27;
        value *= 0x94d049bb133111ebL;
        return value ^ (value >>> 31);
    }
    /** Prune to a lower watermark without discarding the entire accumulated cache. */
    public static <V> void prune(ConcurrentHashMap<Long, V> cache, int maximumEntries, int targetEntries) {
        if (cache.size() <= maximumEntries) return;
        synchronized (cache) {
            if (cache.size() <= maximumEntries) return;
            if (targetEntries < 0 || targetEntries >= maximumEntries)
                throw new IllegalArgumentException("targetEntries must be between 0 and maximumEntries");
            Iterator<Long> keys = cache.keySet().iterator();
            while (cache.size() > targetEntries && keys.hasNext()) cache.remove(keys.next());
        }
    }
}
