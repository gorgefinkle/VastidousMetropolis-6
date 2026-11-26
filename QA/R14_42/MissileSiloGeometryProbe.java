package ch.k42.metropolis.grid.urbanGrid.farm;

import java.util.HashMap;
import java.util.Map;
import java.util.Objects;

/** Pure geometry audit: determinism, bounds, levels, access, variants and chunk clipping. */
public final class MissileSiloGeometryProbe {
    private static final int H = 96;
    private static final int MIN_Y = -64;
    private static final long SEED = 0x5A17C0DEL;

    private static final class Capture implements FarmMissileSiloGeometry.Sink {
        final Map<String, String> blocks = new HashMap<>();
        final Map<String, String> chests = new HashMap<>();
        final Integer chunkX;
        final Integer chunkZ;

        Capture(Integer chunkX, Integer chunkZ) {
            this.chunkX = chunkX;
            this.chunkZ = chunkZ;
        }

        @Override public void set(int x, int y, int z, int id, int data) {
            if (chunkX != null && (Math.floorDiv(x, 16) != chunkX || Math.floorDiv(z, 16) != chunkZ)) return;
            blocks.put(key(x, y, z), id + ":" + data);
        }

        @Override public void chest(int x, int y, int z, int facing, int salt, int tier) {
            if (chunkX != null && (Math.floorDiv(x, 16) != chunkX || Math.floorDiv(z, 16) != chunkZ)) return;
            chests.put(key(x, y, z), facing + ":" + salt + ":" + tier);
        }
    }

    private static String key(int x, int y, int z) { return x + "," + y + "," + z; }
    private static void check(boolean ok, String message) {
        if (!ok) throw new AssertionError(message);
    }
    private static String state(Capture c, int x, int y, int z) {
        return c.blocks.get(key(x, y, z));
    }
    private static int countAtY(Capture c, int y, int id) {
        int n = 0;
        String prefix = null;
        for (Map.Entry<String,String> e : c.blocks.entrySet()) {
            String[] p = e.getKey().split(",");
            if (Integer.parseInt(p[1]) == y && e.getValue().startsWith(id + ":")) n++;
        }
        return n;
    }
    private static Capture render(FarmMissileSiloGeometry.Variant v, Integer chunkX, Integer chunkZ) {
        Capture c = new Capture(chunkX, chunkZ);
        FarmMissileSiloGeometry.paint(c, 48, 48, H, MIN_Y, SEED, v);
        return c;
    }

    private static void auditVariant(FarmMissileSiloGeometry.Variant v) {
        Capture c = render(v, null, null);
        Capture repeat = render(v, null, null);
        check(c.blocks.equals(repeat.blocks), v + ": block geometry is not deterministic");
        check(c.chests.equals(repeat.chests), v + ": chest operations are not deterministic");

        for (Map.Entry<String,String> e : c.blocks.entrySet()) {
            String[] p = e.getKey().split(",");
            int x = Integer.parseInt(p[0]), y = Integer.parseInt(p[1]), z = Integer.parseInt(p[2]);
            check(Math.abs(x - 48) <= 46 && Math.abs(z - 48) <= 46,
                    v + ": placement escaped the reserved 6x6 footprint at " + e.getKey());
            check(!e.getValue().startsWith("167:"), v + ": iron trapdoor found at " + e.getKey());
        }

        check(countAtY(c, H + 1, 101) > 100, v + ": top iron-bar grating is missing");
        check("71:0".equals(state(c, 92, H + 1, 57)), v + ": locked surface door lower half missing");
        check("71:8".equals(state(c, 92, H + 2, 57)), v + ": locked surface door upper half missing");

        int bottom = H - FarmMissileSiloGeometry.SHAFT_DEPTH;
        for (int i = 0; i < FarmMissileSiloGeometry.GALLERY_COUNT; i++) {
            int y = bottom + 8 + i * 8;
            check(countAtY(c, y, 44) > 100, v + ": gallery " + i + " floor is missing");
        }

        int[] controlFloors = {bottom + 18, bottom + 28, bottom + 38, bottom + 48};
        for (int i = 0; i < controlFloors.length; i++) {
            int floor = controlFloors[i];
            check(countAtY(c, floor, v == FarmMissileSiloGeometry.Variant.TEST_FACILITY ? 155 : 98) > 500,
                    v + ": control-centre floor " + i + " is incomplete");
            for (int y = bottom + 19 + i * 10; y <= H; y++) {
                if (y > floor + 7 && i < 3) break;
                check("65:4".equals(state(c, 90, y, 57)),
                        v + ": continuous access ladder missing at y=" + y);
            }
        }

        int lockY = bottom + 49;
        check("71:0".equals(state(c, 65, lockY, 48)), v + ": first blast-lock door missing");
        check("71:0".equals(state(c, 70, lockY, 48)), v + ": second blast-lock door missing");

        // The rocket clearance cylinder must remain free of gallery deck blocks.
        for (int i = 0; i < 8; i++) {
            int y = bottom + 8 + i * 8;
            for (Map.Entry<String,String> e : c.blocks.entrySet()) {
                String[] p = e.getKey().split(",");
                int x = Integer.parseInt(p[0]), yy = Integer.parseInt(p[1]), z = Integer.parseInt(p[2]);
                if (yy == y && Math.hypot(x - 48, z - 48) < 4.3)
                    check(!e.getValue().startsWith("44:"), v + ": gallery deck intrudes into rocket clearance");
            }
        }

        // Every chunk's independently clipped result must equal its slice of a full render.
        for (int cx = 0; cx < 6; cx++) for (int cz = 0; cz < 6; cz++) {
            Capture slice = render(v, cx, cz);
            Map<String,String> expected = new HashMap<>();
            Map<String,String> expectedChests = new HashMap<>();
            for (Map.Entry<String,String> e : c.blocks.entrySet()) {
                String[] p = e.getKey().split(",");
                if (Math.floorDiv(Integer.parseInt(p[0]),16) == cx &&
                    Math.floorDiv(Integer.parseInt(p[2]),16) == cz) expected.put(e.getKey(), e.getValue());
            }
            for (Map.Entry<String,String> e : c.chests.entrySet()) {
                String[] p = e.getKey().split(",");
                if (Math.floorDiv(Integer.parseInt(p[0]),16) == cx &&
                    Math.floorDiv(Integer.parseInt(p[2]),16) == cz) expectedChests.put(e.getKey(), e.getValue());
            }
            check(expected.equals(slice.blocks), v + ": chunk clipping mismatch at " + cx + "," + cz);
            check(expectedChests.equals(slice.chests), v + ": chest clipping mismatch at " + cx + "," + cz);
        }
        System.out.println(v + ": PASS blocks=" + c.blocks.size() + " chests=" + c.chests.size());
    }

    public static void main(String[] args) {
        FarmMissileSiloGeometry.Variant[] variants = FarmMissileSiloGeometry.Variant.values();
        Capture[] renders = new Capture[variants.length];
        for (int i = 0; i < variants.length; i++) {
            auditVariant(variants[i]);
            renders[i] = render(variants[i], null, null);
        }
        check(!renders[0].blocks.equals(renders[1].blocks), "military and test facility layouts are identical");
        check(!renders[0].blocks.equals(renders[2].blocks), "military and vault layouts are identical");
        check(!renders[1].blocks.equals(renders[2].blocks), "test facility and vault layouts are identical");
        System.out.println("All missile-silo geometry audits passed.");
    }
}
