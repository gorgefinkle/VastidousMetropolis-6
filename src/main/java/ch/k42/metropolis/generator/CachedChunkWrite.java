package ch.k42.metropolis.generator;

import java.util.concurrent.ConcurrentHashMap;
import org.bukkit.Material;
import org.bukkit.block.data.BlockData;
import org.bukkit.generator.ChunkGenerator.ChunkData;

/**
 * Shared, finite cache of immutable default BlockData for material-only writes.
 *
 * Never cache mutated BlockData (stairs, doors, waterlogged blocks, etc.).
 * These helpers deliberately cover only material-default ChunkData writes.
 * World/Block and WorldEdit population operations have different semantics.
 */
public final class CachedChunkWrite {
    private static final ConcurrentHashMap<Material, BlockData> DEFAULTS = new ConcurrentHashMap<>();

    private CachedChunkWrite() {}

    private static BlockData defaultData(Material material) {
        return DEFAULTS.computeIfAbsent(material, Material::createBlockData);
    }

    /** Avoids Paper's per-block Material#createBlockData allocation. */
    public static void set(ChunkData data, int x, int y, int z, Material material) {
        data.setBlock(x, y, z, defaultData(material));
    }

    /** Preserve the bulk region API while reusing the same cached BlockData. */
    public static void setRegion(ChunkData data, int xMin, int yMin, int zMin,
                                 int xMax, int yMax, int zMax, Material material) {
        data.setRegion(xMin, yMin, zMin, xMax, yMax, zMax, defaultData(material));
    }
}
