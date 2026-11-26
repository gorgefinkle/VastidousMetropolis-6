package ch.k42.metropolis.grid.urbanGrid.farm;

import com.sk89q.worldedit.world.block.BlockState;
import com.sk89q.worldedit.world.registry.LegacyMapper;

import java.util.Map;
import java.util.TreeMap;

/** Verifies every non-air (legacy id, data) pair actually emitted by all silo variants. */
public final class SiloLegacyBlockMappingProbe {
    private static final int CENTER_X = 48;
    private static final int CENTER_Z = 48;
    private static final int GROUND_Y = 96;
    private static final int MIN_Y = -64;
    private static final long SEED = 0x5A17C0DEL;

    private static final Map<String, String> MAPPED = new TreeMap<>();
    private static long operations;

    private static void verify(int id, int data) {
        if (id == 0) return; // Explicit aperture and shaft clearing is intentional.
        String key = id + ":" + (data & 15);
        if (MAPPED.containsKey(key)) return;

        BlockState state = LegacyMapper.getInstance().getBlockFromLegacy(id, data & 15);
        if (state == null) {
            throw new AssertionError("WorldEdit LegacyMapper has no block for " + key);
        }
        String modernId = state.getBlockType().getId();
        if ("minecraft:air".equals(modernId)) {
            throw new AssertionError("LegacyMapper turns non-air silo block " + key + " into air");
        }
        MAPPED.put(key, modernId);
    }

    private static final class Sink implements FarmMissileSiloGeometry.Sink {
        @Override
        public void set(int x, int y, int z, int id, int data) {
            operations++;
            verify(id, data);
        }

        @Override
        public void chest(int x, int y, int z, int facing, int salt, int tier) {
            operations++;
            verify(54, facing);
        }
    }

    public static void main(String[] args) {
        for (FarmMissileSiloGeometry.Variant variant : FarmMissileSiloGeometry.Variant.values()) {
            FarmMissileSiloGeometry.paint(new Sink(), CENTER_X, CENTER_Z,
                    GROUND_Y, MIN_Y, SEED, variant);
        }
        if (MAPPED.isEmpty()) throw new AssertionError("No non-air legacy block mappings were tested");
        System.out.println("Silo legacy block mapping: PASS operations=" + operations
                + " uniqueIdData=" + MAPPED.size());
        for (Map.Entry<String, String> entry : MAPPED.entrySet())
            System.out.println(entry.getKey() + " -> " + entry.getValue());
    }
}
