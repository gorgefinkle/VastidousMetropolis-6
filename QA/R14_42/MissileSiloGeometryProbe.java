package ch.k42.metropolis.grid.urbanGrid.farm;

import java.util.HashMap;
import java.util.Map;

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
        for (Map.Entry<String,String> e : c.blocks.entrySet()) {
            String[] p = e.getKey().split(",");
            if (Integer.parseInt(p[1]) == y && e.getValue().startsWith(id + ":")) n++;
        }
        return n;
    }
    private static int countId(Capture c, int id) {
        int n = 0;
        for (String state : c.blocks.values()) if (state.startsWith(id + ":")) n++;
        return n;
    }
    private static Capture render(FarmMissileSiloGeometry.Variant v, Integer chunkX, Integer chunkZ) {
        Capture c = new Capture(chunkX, chunkZ);
        FarmMissileSiloGeometry.paint(c, 48, 48, H, MIN_Y, SEED, v);
        return c;
    }

    private static void auditVariant(FarmMissileSiloGeometry.Variant v) {
        Capture c = render(v, null, null);
        check(countId(c, 89) == 0, v + ": glowstone is forbidden in and around the silo complex");
        check(countId(c, 124) > 0, v + ": powered industrial lamp fixtures are missing");
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

        check(countAtY(c, H, 101) > 500, v + ": full-aperture iron-bar grating is missing");
        check("42:0".equals(state(c, 48, H, 48)), v + ": central structural grating beam is missing");
        check("0:0".equals(state(c, 49, H, 49)), v + ": launch duct aperture is sealed beneath the grating");
        check(state(c, 64, H + 1, 40) == null,
                v + ": ventilation riser intrudes into the launch-duct viewing aperture");
        check("98:0".equals(state(c, 75, H + 1, 62)),
                v + ": outer apron ventilation riser is missing");
        check("96:4".equals(state(c, 90, H, 57)),
                v + ": discreet flush surface access hatch is missing");
        check("98:0".equals(state(c, 91, H, 57)),
                v + ": flush hatch support tile is missing");
        check(countId(c, 96) == 1, v + ": surface access must be a single discreet hatch");
        for (int dx = 33; dx <= 44; dx++) {
            for (int dz = 3; dz <= 15; dz++) {
                for (int y = H + 1; y <= H + 6; y++) {
                    String aboveGrade = state(c, 48 + dx, y, 48 + dz);
                    check(aboveGrade == null || "0:0".equals(aboveGrade),
                            v + ": above-ground guard structure remains at " + dx + "," + y + "," + dz);
                }
            }
        }
        check(state(c, 81, H + 1, 57) == null,
                v + ": old guardhouse entry door remains");
        check(state(c, 81, H + 2, 57) == null,
                v + ": old guardhouse upper door remains");
        check("101:0".equals(state(c, 75, H + 1, 93)),
                v + ": closed vehicle gate is not aligned with the gravel spur");
        check("98:0".equals(state(c, 48, H + 1, 93)),
                v + ": south perimeter was accidentally opened away from the gate");
        check("44:5".equals(state(c, 75, H, 92)),
                v + ": gravel spur does not meet the inside face of the vehicle gate");
        check(state(c, 74, H + 1, 92) == null,
                v + ": stray curb blocks the road immediately inside the vehicle gate");
        check("101:0".equals(state(c, 90, H - FarmMissileSiloGeometry.SHAFT_DEPTH + 18, 57)),
                v + ": bottom access landing does not cap the ladder shaft");

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
            for (int y = bottom + 19 + i * 10; y <= H - 1; y++) {
                if (y > floor + 7 && i < 3) break;
                check("65:4".equals(state(c, 90, y, 57)),
                        v + ": continuous access ladder missing at y=" + y);
            }
        }

        int accessLockY = bottom + 19;
        for (int dz = 56; dz <= 58; dz++) {
            check("71:0".equals(state(c, 81, accessLockY, dz)),
                    v + ": access blast door 7 lower panel missing at z=" + dz);
            check("71:8".equals(state(c, 81, accessLockY + 1, dz)),
                    v + ": access blast door 7 upper panel missing at z=" + dz);
            check("71:0".equals(state(c, 86, accessLockY, dz)),
                    v + ": access blast door 6 lower panel missing at z=" + dz);
            check("71:8".equals(state(c, 86, accessLockY + 1, dz)),
                    v + ": access blast door 6 upper panel missing at z=" + dz);
        }
        check("0:0".equals(state(c, 87, accessLockY, 57)),
                v + ": access cage does not open onto the first blast lock");

        int lockY = bottom + 49;
        check("71:0".equals(state(c, 65, lockY, 48)), v + ": first blast-lock door missing");
        check("71:0".equals(state(c, 71, lockY, 48)), v + ": second blast-lock door missing");
        check("42:0".equals(state(c, 65, lockY, 46)), v + ": first blast-lock bulkhead frame is missing");
        check("42:0".equals(state(c, 71, lockY, 50)), v + ": second blast-lock bulkhead frame is missing");

        check(countId(c, 61) == 0, v + ": token furnaces remain in the silo interior");
        check(countId(c, 26) == 0, v + ": decorative bunks remain in the stripped interior");
        check(countId(c, 117) == 0, v + ": unused brewing stands remain in the stripped interior");
        check(c.chests.isEmpty(), v + ": token storage chests remain in the stripped interior");

        // Every control room must be a carved, traversable air volume.
        for (int floor : controlFloors)
            for (int y = floor + 1; y <= floor + 6; y++)
                check("0:0".equals(state(c, 78, y, 48)),
                        v + ": control-centre interior is filled at y=" + y);

        // The lift well stays empty from the lowest landing through the
        // blockhouse floor and into its overhead clearance.
        for (int y = bottom + 18; y <= H + 1; y++) {
            for (int dx = 40; dx <= 42; dx++) for (int dz = 8; dz <= 10; dz++) {
                String actual = state(c, 48 + dx, y, 48 + dz);
                if (dx == 42 && dz == 9 && y == bottom + 18)
                    check("101:0".equals(actual), v + ": bottom lift landing grate is missing");
                else if (dx == 42 && dz == 9 && y > bottom + 18 && y <= H - 1)
                    check("65:4".equals(actual), v + ": lift ladder is discontinuous at y=" + y);
                else if (dx == 42 && dz == 9 && y == H)
                    check("96:4".equals(actual), v + ": flush service hatch is missing at grade");
                else
                    check("0:0".equals(actual), v + ": lift well is blocked at " + dx + "," + y + "," + dz);
            }
        }

        // No iron bars may be a floating singleton: each has an orthogonal
        // bar neighbour or a real solid attachment.
        for (Map.Entry<String,String> e : c.blocks.entrySet()) {
            if (!e.getValue().startsWith("101:")) continue;
            String[] p = e.getKey().split(",");
            int bx = Integer.parseInt(p[0]), by = Integer.parseInt(p[1]), bz = Integer.parseInt(p[2]);
            boolean joined = false;
            int[][] dirs = {{1,0,0},{-1,0,0},{0,1,0},{0,-1,0},{0,0,1},{0,0,-1}};
            for (int[] d : dirs) {
                String neighbour = state(c, bx + d[0], by + d[1], bz + d[2]);
                if (neighbour == null) continue;
                if (neighbour.startsWith("101:") || neighbour.startsWith("42:")
                        || neighbour.startsWith("98:") || neighbour.startsWith("1:")
                        || neighbour.startsWith("4:") || neighbour.startsWith("44:")) {
                    joined = true;
                    break;
                }
            }
            check(joined, v + ": disconnected iron bar at " + e.getKey());
        }

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
