package ch.k42.metropolis.grid.urbanGrid.farm;

/**
 * Pure block geometry for the rare missile-silo landmark.
 *
 * The feature is deliberately self-contained: every call is a deterministic
 * function of site coordinates, seed and variant, and the sink may clip the
 * result to one chunk. No world reads, chunk loads or follow-up repair pass.
 */
final class FarmMissileSiloGeometry {
    interface Sink {
        void set(int x, int y, int z, int id, int data);
        void chest(int x, int y, int z, int facing, int salt, int tier);
    }

    enum Variant { MILITARY, TEST_FACILITY, VAULT }

    static final int HALF_FOOTPRINT = 46;
    static final int SHAFT_RADIUS = 20;
    static final int SHAFT_DEPTH = 82;
    static final int ROCKET_BASE_OFFSET = 13;
    static final int ROCKET_BODY_HEIGHT = 43;
    static final int ROCKET_NOSE_HEIGHT = 9;
    static final int GALLERY_COUNT = 8;

    // 1.12 legacy IDs. FarmPainter/LegacyBlocks remains the single conversion
    // boundary to modern Paper block states.
    private static final int AIR = 0, STONE = 1, DIRT = 3, COBBLE = 4, PLANKS = 5;
    private static final int WATER = 9, GLASS = 20, BED = 26, COBWEB = 30, WOOL = 35;
    private static final int IRON_BLOCK = 42, STONE_SLAB = 44;
    private static final int IRON_BARS = 101, GLASS_PANE = 102, WOOD_TRAPDOOR = 96;
    private static final int LADDER = 65, IRON_DOOR = 71, FENCE = 85;
    private static final int TORCH = 50, CHEST = 54, FURNACE = 61, CAULDRON = 118;
    private static final int STONE_BRICKS = 98, REDSTONE_LAMP = 123, REDSTONE_LAMP_ON = 124, QUARTZ = 155;
    private static final int TERRACOTTA = 159, HAY = 170, REDSTONE_BLOCK = 152;
    private static final int BREWING_STAND = 117, HOPPER = 154, TRIPWIRE_HOOK = 131;
    private static final int COBBLE_STAIRS = 67, STONE_BUTTON = 77;
    private static final int WOOD_SLAB = 126, PUMPKIN = 86, WHEAT = 59;

    private final Sink out;
    private final int cx, cz, h, minY, bottom, baseY;
    private final long seed;
    private final Variant variant;
    private final int floorId, floorData, wallId, wallData, accentId, accentData;
    private final int warningData;

    private FarmMissileSiloGeometry(Sink out, int cx, int cz, int h, int minY,
                                    long seed, Variant variant) {
        this.out = out;
        this.cx = cx;
        this.cz = cz;
        this.h = h;
        this.minY = minY;
        this.bottom = Math.max(minY + 8, h - SHAFT_DEPTH);
        this.baseY = this.bottom + ROCKET_BASE_OFFSET;
        this.seed = seed;
        this.variant = variant;
        switch (variant) {
            case TEST_FACILITY -> {
                this.floorId = QUARTZ; this.floorData = 0;
                this.wallId = TERRACOTTA; this.wallData = 0;
                this.accentId = TERRACOTTA; this.accentData = 4;
                this.warningData = 4;
            }
            case VAULT -> {
                this.floorId = STONE_BRICKS; this.floorData = 0;
                // A vault uses a visibly heavier steel-lined control envelope,
                // rather than a unique set of arbitrary interior props.
                this.wallId = IRON_BLOCK; this.wallData = 0;
                this.accentId = WOOL; this.accentData = 11;
                this.warningData = 4;
            }
            default -> {
                this.floorId = STONE_BRICKS; this.floorData = 0;
                this.wallId = STONE_BRICKS; this.wallData = 0;
                this.accentId = IRON_BLOCK; this.accentData = 0;
                this.warningData = 4;
            }
        }
    }

    static void paint(Sink out, int cx, int cz, int h, int minY,
                      long seed, Variant variant) {
        FarmMissileSiloGeometry g =
                new FarmMissileSiloGeometry(out, cx, cz, h, minY, seed, variant);
        g.paint();
    }

    private void paint() {
        // Start by establishing the air volumes that make the rooms and access
        // shafts real spaces, not facades built around untouched terrain.
        clearReservedInteriorVolumes();
        paintShaftShell();
        paintLaunchTable();
        paintRocket();
        paintHoldDownGantry();
        paintEightGalleryLevels();
        paintShaftLadderAndEscapeCage();
        paintControlCentre();
        paintSiloCablewaysAndBlastLock();
        paintSurfaceApronAndGrating();
        paintPerimeterFence();
        // Keep the interior functional and intentionally uncluttered.
        reopenElevatorShaft();
        paintConcealedSurfaceAccess();
    }

    private int x(int dx) { return this.cx + dx; }
    private int z(int dz) { return this.cz + dz; }

    private void put(int dx, int y, int dz, int id, int data) {
        if (Math.abs(dx) > HALF_FOOTPRINT || Math.abs(dz) > HALF_FOOTPRINT) return;
        if (y < this.minY || y >= 320) return;
        this.out.set(x(dx), y, z(dz), id, data);
    }

    private void fill(int x0, int x1, int y, int z0, int z1, int id, int data) {
        for (int dx = Math.min(x0, x1); dx <= Math.max(x0, x1); dx++)
            for (int dz = Math.min(z0, z1); dz <= Math.max(z0, z1); dz++)
                put(dx, y, dz, id, data);
    }

    private void clearBox(int x0, int x1, int y0, int y1, int z0, int z1) {
        for (int dx = Math.min(x0, x1); dx <= Math.max(x0, x1); dx++)
            for (int y = Math.min(y0, y1); y <= Math.max(y0, y1); y++)
                for (int dz = Math.min(z0, z1); dz <= Math.max(z0, z1); dz++)
                    put(dx, y, dz, AIR, 0);
    }

    private void clearReservedInteriorVolumes() {
        // Carve every intended occupied interior before walls and floors are laid.
        for (int floor : lccLevels())
            clearBox(21, 44, floor + 1, floor + 6, -14, 14);
        clearBox(40, 42, this.bottom + 18, this.h + 4, 8, 10);
    }

    private void reopenElevatorShaft() {
        int low = this.bottom + 18;
        // The shaft is a protected void; later structure can never cap it.
        clearBox(40, 42, low, this.h + 4, 8, 10);
        for (int y = low + 1; y <= this.h - 1; y++) {
            put(43, y, 9, STONE_BRICKS, 0);
            put(42, y, 9, LADDER, 4);
        }
        // A single steel grate is the proper bottom landing; the shaft above
        // it remains open and the ladder continues to the upper levels.
        put(42, low, 9, IRON_BARS, 0);
        // Open the landing side to the room/corridor without removing the
        // continuous cage on its other three sides.
        clearBox(39, 39, this.h + 1, this.h + 3, 9, 9);
        clearBox(34, 38, this.h + 1, this.h + 3, 9, 9);
    }

    private void wallX(int dx, int z0, int z1, int y0, int y1, int id, int data) {
        for (int dz = z0; dz <= z1; dz++)
            for (int y = y0; y <= y1; y++) put(dx, y, dz, id, data);
    }

    private void wallZ(int dz, int x0, int x1, int y0, int y1, int id, int data) {
        for (int dx = x0; dx <= x1; dx++)
            for (int y = y0; y <= y1; y++) put(dx, y, dz, id, data);
    }

    private void door(int dx, int y, int dz, int facingData) {
        // An unpowered legacy iron door is closed. No switch or redstone source
        // is placed on the exterior of the main access portal.
        put(dx, y, dz, IRON_DOOR, facingData & 3);
        put(dx, y + 1, dz, IRON_DOOR, 8);
    }

    private void light(int dx, int y, int dz) {
        // One ceiling-mounted, powered industrial lamp. The lit legacy state
        // is self-illuminating; no glowstone or exposed redstone is needed.
        put(dx, y + 1, dz, REDSTONE_LAMP_ON, 0);
    }

    private void supportColumn(int dx, int dz, int y0, int y1, int id, int data) {
        for (int y = y0; y <= y1; y++) put(dx, y, dz, id, data);
    }

    private void paintShaftShell() {
        for (int dx = -SHAFT_RADIUS; dx <= SHAFT_RADIUS; dx++) {
            for (int dz = -SHAFT_RADIUS; dz <= SHAFT_RADIUS; dz++) {
                double d = Math.hypot(dx, dz);
                if (d > SHAFT_RADIUS) continue;
                for (int y = this.bottom + 1; y <= this.h - 1; y++) {
                    if (d >= 18.0) {
                        int band = (y - this.bottom) % 10;
                        int material = band == 0 ||
                                FarmRandom.unit(this.seed, this.cx + dx, this.cz + dz, y + 900) < .025
                                ? COBBLE : STONE_BRICKS;
                        put(dx, y, dz, material, 0);
                    } else if (d >= 16.2) {
                        put(dx, y, dz, COBBLE, 0);
                    } else {
                        put(dx, y, dz, AIR, 0);
                    }
                }
                if (d <= 17.8) put(dx, this.bottom, dz, STONE_BRICKS, 0);
            }
        }

        // Double-shell ribs at regular vertical intervals; each is tied into
        // the thick outside shell rather than floating as a decorative ring.
        for (int y = this.bottom + 10; y < this.h - 4; y += 10) {
            for (int dx = -19; dx <= 19; dx++) {
                for (int dz = -19; dz <= 19; dz++) {
                    double d = Math.hypot(dx, dz);
                    if (d >= 17.8 && d <= 19.9) put(dx, y, dz, IRON_BLOCK, 0);
                }
            }
        }
    }

    private void paintLaunchTable() {
        int[][] supports = {
                {-7,0},{7,0},{0,-7},{0,7},
                {-5,-5},{-5,5},{5,-5},{5,5}
        };
        for (int[] support : supports)
            supportColumn(support[0], support[1], this.bottom + 1, this.baseY - 1, STONE_BRICKS, 0);

        for (int dx = -9; dx <= 9; dx++) {
            for (int dz = -9; dz <= 9; dz++) {
                double d = Math.hypot(dx, dz);
                if (d <= 8.5)
                    put(dx, this.baseY, dz, d <= 4.2 ? STONE_BRICKS : IRON_BARS, 0);
            }
        }
        for (int y = this.bottom + 1; y < this.baseY; y++)
            for (int dx = -2; dx <= 2; dx++) put(dx, y, 0, STONE_BRICKS, 0);

        // Exhaust deflector and the broad reinforced trench are tied to the
        // bottom launch deck, never suspended below it.
        for (int dx = -4; dx <= 4; dx++) {
            put(dx, this.bottom + 1, -13, STONE_BRICKS, 0);
            put(dx, this.bottom + 1, -14, STONE_BRICKS, 0);
        }
        for (int y = this.bottom + 2; y <= this.bottom + 8; y++)
            for (int dx = -4; dx <= 4; dx++) put(dx, y, -14, COBBLE, 0);
    }

    private void rocketSection(int y, double radius, int colour) {
        int reach = (int)Math.ceil(radius);
        for (int dx = -reach; dx <= reach; dx++)
            for (int dz = -reach; dz <= reach; dz++)
                if (Math.hypot(dx, dz) <= radius + .12)
                    put(dx, y, dz, TERRACOTTA, colour);
    }

    private void paintRocket() {
        for (int y = this.baseY + 1; y <= this.baseY + 3; y++)
            rocketSection(y, y == this.baseY + 1 ? 3.9 : 3.5,
                    y == this.baseY + 1 ? 8 : 7);

        for (int y = this.baseY + 4; y <= this.baseY + ROCKET_BODY_HEIGHT; y++) {
            int offset = y - (this.baseY + 4);
            int colour = offset % 11 == 0 ? 8 : (offset % 7 == 0 ? 13 : 0);
            rocketSection(y, 3.35, colour);
            if (offset == 0 || offset == 21 || offset == 42) {
                for (int dx = -4; dx <= 4; dx++) {
                    put(dx, y, -4, IRON_BARS, 0);
                    put(dx, y, 4, IRON_BARS, 0);
                }
            }
        }

        // Four fins taper into the engine skirt.
        for (int y = this.baseY + 2; y <= this.baseY + 12; y++) {
            int reach = 4 + (y - (this.baseY + 2)) / 2;
            for (int r = 3; r <= reach; r++) {
                put(r, y, 0, TERRACOTTA, 13);
                put(-r, y, 0, TERRACOTTA, 13);
                put(0, y, r, TERRACOTTA, 13);
                put(0, y, -r, TERRACOTTA, 13);
            }
        }
        for (int i = 0; i < ROCKET_NOSE_HEIGHT; i++) {
            double radius = Math.max(0.0, 3.0 * (1.0 - (double)i / (ROCKET_NOSE_HEIGHT - 1)));
            rocketSection(this.baseY + ROCKET_BODY_HEIGHT + i + 1, radius, 14);
        }
    }

    private void paintHoldDownGantry() {
        int[][] towers = {{-9,0},{9,0},{0,-9},{0,9}};
        int[] clampLevels = {this.baseY + 10, this.baseY + 25, this.baseY + 39};
        for (int[] tower : towers) {
            int tx = tower[0], tz = tower[1];
            for (int y = this.bottom + 1; y <= this.baseY + 42; y++) {
                put(tx, y, tz, STONE_BRICKS, 0);
                put(tx != 0 ? tx : 1, y, tz != 0 ? tz : 1, IRON_BARS, 0);
            }
            for (int level : clampLevels) {
                if (tx != 0) {
                    int sign = Integer.signum(tx);
                    for (int step = 4; step <= 8; step++) put(sign * step, level, 0, IRON_BARS, 0);
                    put(sign * 4, level, 0, STONE_BRICKS, 0);
                    put(sign * 5, level - 1, 0, IRON_BARS, 0);
                } else {
                    int sign = Integer.signum(tz);
                    for (int step = 4; step <= 8; step++) put(0, level, sign * step, IRON_BARS, 0);
                    put(0, level, sign * 4, STONE_BRICKS, 0);
                    put(0, level - 1, sign * 5, IRON_BARS, 0);
                }
            }
        }
    }

    private int[] galleryLevels() {
        return new int[] {
                this.bottom + 8, this.bottom + 16, this.bottom + 24, this.bottom + 32,
                this.bottom + 40, this.bottom + 48, this.bottom + 56, this.bottom + 64
        };
    }

    private void paintEightGalleryLevels() {
        int[] levels = galleryLevels();
        for (int level : levels) {
            for (int dx = -16; dx <= 16; dx++) {
                for (int dz = -16; dz <= 16; dz++) {
                    double d = Math.hypot(dx, dz);
                    if (d >= 10.8 && d <= 16.1) put(dx, level, dz, STONE_SLAB, 5);
                }
            }
            // Four radial gangways connect the annular walkway to the gantry.
            for (int r = 8; r <= 12; r++) {
                put(r, level, 0, STONE_SLAB, 5);
                put(-r, level, 0, STONE_SLAB, 5);
                put(0, level, r, STONE_SLAB, 5);
                put(0, level, -r, STONE_SLAB, 5);
            }
            paintConnectedGalleryRail(level + 1);
        }
        // Piers live in the outer shell band, transferring gallery loads to
        // the foundation without standing in the walking path.
        for (int[] point : new int[][]{{-17,0},{17,0},{0,-17},{0,17}})
            supportColumn(point[0], point[1], this.bottom + 1, this.bottom + 64, STONE_BRICKS, 0);
    }

    private void paintConnectedGalleryRail(int y) {
        final double radius = 15.35;
        int previous = -1;
        // Rasterise each arc with short orthogonal joins. Diagonal-only
        // adjacency is not accepted for visible iron-bar railings.
        for (int dx = -15; dx <= 15; dx++) {
            int edge = (int)Math.round(Math.sqrt(radius * radius - dx * dx));
            if (previous >= 0) {
                for (int dz = Math.min(previous, edge); dz <= Math.max(previous, edge); dz++) {
                    put(dx, y, dz, IRON_BARS, 0);
                    put(dx, y, -dz, IRON_BARS, 0);
                }
            } else {
                put(dx, y, edge, IRON_BARS, 0);
                put(dx, y, -edge, IRON_BARS, 0);
            }
            previous = edge;
        }
        previous = -1;
        for (int dz = -15; dz <= 15; dz++) {
            int edge = (int)Math.round(Math.sqrt(radius * radius - dz * dz));
            if (previous >= 0) {
                for (int dx = Math.min(previous, edge); dx <= Math.max(previous, edge); dx++) {
                    put(dx, y, dz, IRON_BARS, 0);
                    put(-dx, y, dz, IRON_BARS, 0);
                }
            } else {
                put(edge, y, dz, IRON_BARS, 0);
                put(-edge, y, dz, IRON_BARS, 0);
            }
            previous = edge;
        }
    }

    private void paintShaftLadderAndEscapeCage() {
        // Continuous wall-mounted access ladders. The previous intermittent
        // iron-bar stubs were disconnected and served no structural purpose.
        for (int y = this.bottom + 2; y <= this.h - 2; y++) {
            put(-15, y, 0, LADDER, 5);
            put(-16, y, 0, STONE_BRICKS, 0);
            put(15, y, 0, LADDER, 4);
            put(16, y, 0, STONE_BRICKS, 0);
        }
    }

    private int[] lccLevels() {
        return new int[] {
                this.bottom + 18, this.bottom + 28, this.bottom + 38, this.bottom + 48
        };
    }

    private boolean inPortalShaft(int dx, int dz) {
        return dx >= 40 && dx <= 42 && dz >= 8 && dz <= 10;
    }

    private void paintControlCentre() {
        int[] levels = lccLevels();
        for (int i = 0; i < levels.length; i++) {
            int floor = levels[i];
            int roof = floor + 7;
            // This clearance is the room's actual interior, not an assumption
            // that the original landscape happens to be air.
            clearBox(21, 44, floor + 1, roof - 1, -14, 14);

            for (int dx = 20; dx <= 45; dx++) {
                for (int dz = -15; dz <= 15; dz++) {
                    if (!inPortalShaft(dx, dz)) {
                        put(dx, floor, dz, this.floorId, this.floorData);
                        put(dx, roof, dz, this.wallId, this.wallData);
                    }
                }
            }

            for (int y = floor + 1; y <= roof - 1; y++) {
                for (int dz = -15; dz <= 15; dz++) {
                    boolean openServiceDoor = dz >= -1 && dz <= 1 && y <= floor + 3;
                    if (!openServiceDoor) put(20, y, dz, this.wallId, this.wallData);
                    put(45, y, dz, this.wallId, this.wallData);
                }
                for (int dx = 20; dx <= 45; dx++) {
                    put(dx, y, -15, this.wallId, this.wallData);
                    put(dx, y, 15, this.wallId, this.wallData);
                }
            }

            for (int y = floor + 1; y <= floor + 3; y++)
                put(39, y, 9, AIR, 0);

            for (int dz : new int[]{-10,-5,5,10})
                put(45, floor + 3, dz, GLASS_PANE, 0);

            for (int[] p : new int[][]{{21,-14},{21,14},{44,-14},{44,14},
                                       {33,-14},{33,14},{21,0},{44,0}})
                supportColumn(p[0], p[1], floor, roof, STONE_BRICKS, 0);
        }
        // Empty levels are deliberate; their structure, access and clear
        // circulation are the design, not prop accumulation.
        paintPortalShaft();
    }

    private void paintPortalShaft() {
        int low = this.bottom + 18;
        // Five-by-five continuous cage perimeter surrounding a clear 3x3 well.
        for (int y = low + 1; y <= this.h - 1; y++) {
            for (int dz = 7; dz <= 11; dz++) {
                put(39, y, dz, IRON_BARS, 0);
                put(43, y, dz, IRON_BARS, 0);
            }
            for (int dx = 39; dx <= 43; dx++) {
                put(dx, y, 7, IRON_BARS, 0);
                put(dx, y, 11, IRON_BARS, 0);
            }
        }
        for (int y = low + 1; y <= this.h - 1; y++) {
            put(42, y, 9, LADDER, 4);
            put(43, y, 9, STONE_BRICKS, 0);
        }

        for (int floor : lccLevels()) {
            // The ring is the landing grate; the central 3x3 is never floored.
            for (int dx = 39; dx <= 43; dx++) {
                for (int dz = 7; dz <= 11; dz++) {
                    if (!inPortalShaft(dx, dz)) put(dx, floor, dz, IRON_BARS, 0);
                }
            }
            // Reopen the side gate on every level and retain side guard rails
            // on either side of the doorway.
            for (int y = floor + 1; y <= floor + 3; y++)
                put(39, y, 9, AIR, 0);
            for (int dz : new int[]{7,8,10,11})
                put(38, floor + 1, dz, IRON_BARS, 0);
        }
    }

    private void paintControlRoomFurniture(int[] levels) {
        for (int i = 0; i < levels.length; i++) {
            int y = levels[i];
            switch (this.variant) {
                case TEST_FACILITY -> paintTestRoom(i, y);
                case VAULT -> paintVaultRoom(i, y);
                default -> paintMilitaryRoom(i, y);
            }
        }
    }

    private void paintMilitaryRoom(int level, int y) {
        switch (level) {
            case 0 -> {
                // Crew quarters: four bunks, steel lockers, central table,
                // kitchen counter and a small wash station.
                bed(25, y + 1, -10, 0);
                bed(25, y + 1, -6, 0);
                bed(25, y + 1, 6, 0);
                bed(25, y + 1, 10, 0);
                for (int dz : new int[]{-11,-7,5,9}) {
                    put(27, y + 1, dz, IRON_BLOCK, 0);
                    put(27, y + 2, dz, IRON_BLOCK, 0);
                }
                table(34, y, -2);
                table(34, y, 2);
                put(41, y + 1, -12, FURNACE, 2);
                put(42, y + 1, -12, CAULDRON, 0);
                put(41, y + 1, -11, CHEST, 0);
                this.out.chest(x(41), y + 1, z(-11), 4, 720, 0);
                light(32, y + 5, 0);
            }
            case 1 -> {
                // Launch-control floor: a bank of four control consoles with
                // indicator lights and a forward-facing observation window.
                for (int dx : new int[]{25,29,33,37}) console(dx, y, -7, 0);
                for (int dx : new int[]{25,29,33,37}) console(dx, y, 7, 0);
                for (int dz = -10; dz <= 10; dz += 2) {
                    put(43, y + 2, dz, REDSTONE_LAMP, 0);
                    put(43, y + 3, dz, IRON_BARS, 0);
                }
                table(40, y, 0);
                light(32, y + 5, 0);
                this.out.chest(x(40), y + 1, z(-12), 4, 721, 1);
            }
            case 2 -> {
                // Communications and telemetry: server racks, relay cabinets,
                // cable conduits and a spare equipment locker.
                for (int dz = -10; dz <= 10; dz += 4) rack(24, y, dz);
                for (int dz = -10; dz <= 10; dz += 4) rack(42, y, dz);
                for (int dx = 28; dx <= 38; dx += 2) {
                    put(dx, y + 1, -12, IRON_BLOCK, 0);
                    put(dx, y + 2, -12, REDSTONE_LAMP, 0);
                }
                table(33, y, 0);
                this.out.chest(x(41), y + 1, z(12), 4, 722, 1);
                light(32, y + 5, 0);
            }
            default -> {
                // Power level: paired generator sets, battery banks and fuel
                // stores are separated from the control/crew rooms.
                generator(25, y, -7);
                generator(25, y, 7);
                for (int dz = -11; dz <= 11; dz += 3) {
                    put(40, y + 1, dz, IRON_BLOCK, 0);
                    put(41, y + 1, dz, REDSTONE_BLOCK, 0);
                    put(42, y + 1, dz, IRON_BLOCK, 0);
                }
                this.out.chest(x(42), y + 1, z(-12), 4, 723, 2);
                light(33, y + 5, 0);
            }
        }
    }

    private void paintTestRoom(int level, int y) {
        switch (level) {
            case 0 -> {
                // Decontamination / medical room with shower stations,
                // changing lockers and an isolated treatment bay.
                put(24, y + 1, -10, CAULDRON, 3);
                put(24, y + 1, -7, CAULDRON, 3);
                for (int dz = -11; dz <= -6; dz += 2) {
                    put(24, y + 4, dz, IRON_BARS, 0);
                    put(24, y + 3, dz, WATER, 0);
                }
                bed(30, y + 1, 7, 0);
                bed(30, y + 1, 10, 0);
                for (int dz = -10; dz <= 10; dz += 4) {
                    put(42, y + 1, dz, IRON_BLOCK, 0);
                    put(42, y + 2, dz, GLASS, 0);
                }
                table(33, y, -3);
                this.out.chest(x(41), y + 1, z(-12), 4, 730, 0);
                light(32, y + 5, 0);
            }
            case 1 -> {
                // Main laboratory: quartz worktops, chemical stands,
                // specimen cabinets, glass dividers and multiple stations.
                for (int dz = -10; dz <= 10; dz += 4) {
                    labBench(24, y, dz);
                    labBench(31, y, dz);
                }
                for (int dz = -12; dz <= 12; dz += 2)
                    put(43, y + 3, dz, GLASS_PANE, 0);
                put(41, y + 1, -11, BREWING_STAND, 0);
                put(41, y + 1, -8, BREWING_STAND, 0);
                put(41, y + 1, 8, CAULDRON, 0);
                put(41, y + 1, 11, CAULDRON, 0);
                this.out.chest(x(42), y + 1, z(-12), 4, 731, 1);
                light(34, y + 5, 0);
            }
            case 2 -> {
                // Instrumentation and observation floor: sensor arrays,
                // oscillograph-like panels and a glazed viewport to the duct.
                for (int dx = 23; dx <= 40; dx += 3) console(dx, y, -8, 4);
                for (int dx = 23; dx <= 40; dx += 3) console(dx, y, 8, 4);
                for (int dz = -12; dz <= 12; dz++)
                    put(43, y + 3, dz, GLASS, 0);
                for (int dz = -10; dz <= 10; dz += 4) rack(24, y, dz);
                this.out.chest(x(41), y + 1, z(12), 4, 732, 1);
                light(34, y + 5, 0);
            }
            default -> {
                // Test apparatus and abandoned power instrumentation.
                for (int dz = -10; dz <= 10; dz += 4) generator(24, y, dz);
                for (int dx = 29; dx <= 41; dx += 3) {
                    put(dx, y + 1, -12, QUARTZ, 0);
                    put(dx, y + 2, -12, GLASS, 0);
                    put(dx, y + 3, -12, IRON_BARS, 0);
                }
                table(34, y, 0);
                this.out.chest(x(41), y + 1, z(12), 4, 733, 2);
                light(34, y + 5, 0);
            }
        }
    }

    private void paintVaultRoom(int level, int y) {
        switch (level) {
            case 0 -> {
                // Resident quarters and mess: bunks around the perimeter,
                // communal table, cooking range and pantry.
                bed(24, y + 1, -10, 0);
                bed(24, y + 1, -6, 0);
                bed(24, y + 1, 6, 0);
                bed(24, y + 1, 10, 0);
                table(33, y, -3);
                table(33, y, 3);
                for (int dz = -11; dz <= 11; dz += 4) {
                    put(42, y + 1, dz, PLANKS, 5);
                    put(42, y + 2, dz, WOOL, 11);
                }
                put(40, y + 1, -12, FURNACE, 2);
                put(41, y + 1, -12, CAULDRON, 3);
                this.out.chest(x(42), y + 1, z(-12), 4, 740, 0);
                light(32, y + 5, 0);
            }
            case 1 -> {
                // Clinic and administration: two treatment beds, cabinets,
                // a wash unit and a coloured command desk.
                bed(25, y + 1, -8, 0);
                bed(25, y + 1, 8, 0);
                for (int dz = -10; dz <= 10; dz += 5) {
                    put(29, y + 1, dz, CAULDRON, 0);
                    put(29, y + 2, dz, GLASS, 0);
                    put(42, y + 1, dz, PLANKS, 5);
                    put(42, y + 2, dz, WOOL, 4);
                }
                console(35, y, -4, 11);
                console(35, y, 4, 11);
                this.out.chest(x(42), y + 1, z(12), 4, 741, 1);
                light(33, y + 5, 0);
            }
            case 2 -> {
                // Hydroponics and food storage: raised growing trays,
                // compact water tanks, stacked crop crates and lighting.
                for (int dx : new int[]{24,29,34}) {
                    for (int dz : new int[]{-9,-3,3,9}) {
                        put(dx, y + 1, dz, DIRT, 0);
                        put(dx, y + 2, dz, WHEAT, 7);
                        put(dx, y + 4, dz, REDSTONE_LAMP_ON, 0);
                    }
                }
                for (int dz = -10; dz <= 10; dz += 5) {
                    put(42, y + 1, dz, HAY, 0);
                    put(42, y + 2, dz, HAY, 0);
                }
                this.out.chest(x(42), y + 1, z(12), 4, 742, 1);
                light(33, y + 5, 0);
            }
            default -> {
                // Water treatment and power distribution; blue/yellow palette
                // bands separate machinery from habitable spaces.
                generator(24, y, -8);
                generator(24, y, 8);
                for (int dz = -10; dz <= 10; dz += 4) {
                    put(40, y + 1, dz, CAULDRON, 3);
                    put(41, y + 1, dz, HOPPER, 0);
                    put(42, y + 1, dz, IRON_BLOCK, 0);
                }
                for (int dx = 28; dx <= 38; dx += 2)
                    put(dx, y + 1, -12, WOOL, 11);
                this.out.chest(x(42), y + 1, z(-12), 4, 743, 2);
                light(33, y + 5, 0);
            }
        }
    }

    private void bed(int dx, int y, int dz, int facing) {
        put(dx, y, dz, BED, facing & 3);
        put(dx, y, dz + 1, BED, (facing & 3) | 8);
    }

    private void table(int dx, int floor, int dz) {
        put(dx, floor + 1, dz, FENCE, 0);
        put(dx, floor + 2, dz, WOOD_SLAB, 2);
        put(dx - 1, floor + 1, dz, COBBLE_STAIRS, 0);
        put(dx + 1, floor + 1, dz, COBBLE_STAIRS, 1);
    }

    private void console(int dx, int floor, int dz, int accent) {
        put(dx, floor + 1, dz, IRON_BLOCK, 0);
        put(dx, floor + 2, dz, accent == 11 ? WOOL : TERRACOTTA,
                accent == 11 ? 11 : (accent == 4 ? 4 : 7));
        put(dx, floor + 3, dz, REDSTONE_LAMP, 0);
        put(dx, floor + 1, dz + 1, STONE_BUTTON, 1);
    }

    private void rack(int dx, int floor, int dz) {
        put(dx, floor + 1, dz, IRON_BLOCK, 0);
        put(dx, floor + 2, dz, IRON_BARS, 0);
        put(dx, floor + 3, dz, REDSTONE_LAMP, 0);
        put(dx, floor + 4, dz, IRON_BARS, 0);
    }

    private void generator(int dx, int floor, int dz) {
        put(dx, floor + 1, dz, FURNACE, 2);
        put(dx, floor + 2, dz, IRON_BLOCK, 0);
        put(dx, floor + 3, dz, REDSTONE_LAMP, 0);
        put(dx + 1, floor + 1, dz, REDSTONE_BLOCK, 0);
        put(dx - 1, floor + 1, dz, IRON_BARS, 0);
    }

    private void labBench(int dx, int floor, int dz) {
        put(dx, floor + 1, dz, QUARTZ, 0);
        put(dx, floor + 2, dz, GLASS, 0);
        put(dx, floor + 3, dz, GLASS_PANE, 0);
        put(dx + 1, floor + 1, dz, BREWING_STAND, 0);
        put(dx - 1, floor + 1, dz, CAULDRON, 0);
    }

    private void paintSiloCablewaysAndBlastLock() {
        int[] levels = lccLevels();
        for (int i = 0; i < levels.length; i++) {
            int floor = levels[i];

            // A narrow reinforced service cableway reaches through the east
            // shell of the launch duct. Only the upper control level carries
            // the personnel blast-lock sequence; the other floors stay service
            // conduits and cannot become accidental second entrances.
            for (int dx = 15; dx <= 20; dx++) {
                for (int dz = -2; dz <= 2; dz++) {
                    put(dx, floor, dz, STONE_SLAB, 5);
                    put(dx, floor + 4, dz, STONE_BRICKS, 0);
                    for (int y = floor + 1; y <= floor + 3; y++) {
                        if (Math.abs(dz) == 2) put(dx, y, dz, IRON_BARS, 0);
                        else put(dx, y, dz, AIR, 0);
                    }
                }
            }

            // The cableway punches only through the east-facing arc of the
            // circular shaft. The central launch duct and its rocket remain
            // untouched.
            for (int dx = 17; dx <= 20; dx++) {
                for (int dz = -2; dz <= 2; dz++) {
                    for (int y = floor + 1; y <= floor + 3; y++) put(dx, y, dz, AIR, 0);
                }
            }

            if (i == 3) {
                // Continue a short, clearly bounded passage into the control
                // module. Two reinforced bulkheads create a real air-lock
                // chamber rather than two doors floating in an open room.
                for (int dx = 21; dx <= 23; dx++) {
                    for (int dz = -2; dz <= 2; dz++) {
                        put(dx, floor, dz, STONE_SLAB, 5);
                        put(dx, floor + 4, dz, STONE_BRICKS, 0);
                        for (int y = floor + 1; y <= floor + 3; y++) {
                            if (Math.abs(dz) == 2) put(dx, y, dz, IRON_BARS, 0);
                            else put(dx, y, dz, AIR, 0);
                        }
                    }
                }

                int[] bulkheads = {17, 23};
                for (int bulkhead : bulkheads) {
                    // Each pressure bulkhead is three blocks wide, with
                    // reinforced side jambs and a continuous steel lintel.
                    for (int y = floor + 1; y <= floor + 3; y++) {
                        put(bulkhead, y, -2, IRON_BLOCK, 0);
                        put(bulkhead, y, 2, IRON_BLOCK, 0);
                    }
                    for (int dz = -2; dz <= 2; dz++)
                        put(bulkhead, floor + 3, dz, IRON_BLOCK, 0);
                    for (int dz = -1; dz <= 1; dz++)
                        door(bulkhead, floor + 1, dz, 0);
                }

                // Status lamps and cable trays live above the chamber, outside
                // the player clearance envelope.
                put(20, floor + 5, -1, REDSTONE_LAMP, 0);
                put(20, floor + 5, 1, REDSTONE_LAMP, 0);
                for (int dx = 15; dx <= 20; dx += 2)
                    put(dx, floor + 3, -1, IRON_BLOCK, 0);
            }
        }
        // The lower access-portal pair (Doors 6 and 7) protects the entry
        // entrapment room before crew members enter the control-centre levels.
        paintAccessPortalBlastLock(levels[0]);
    }

    private void paintAccessPortalBlastLock(int floor) {
        // A compact five-block-wide entrapment chamber sits between the caged
        // access ladder and the crew quarters. Doors 6 and 7 are each three
        // blocks wide, with reinforced jambs, ceiling and observation glazing.
        for (int dx = 33; dx <= 38; dx++) {
            for (int dz : new int[]{7, 11}) {
                for (int y = floor + 1; y <= floor + 3; y++) put(dx, y, dz, IRON_BLOCK, 0);
                put(dx, floor + 2, dz, GLASS_PANE, 0);
            }
            for (int dz = 7; dz <= 11; dz++)
                put(dx, floor + 4, dz, this.wallId, this.wallData);
        }

        int[] doors = {33, 38};
        for (int bulkhead : doors) {
            for (int y = floor + 1; y <= floor + 3; y++) {
                put(bulkhead, y, 7, IRON_BLOCK, 0);
                put(bulkhead, y, 11, IRON_BLOCK, 0);
            }
            for (int dz = 7; dz <= 11; dz++)
                put(bulkhead, floor + 3, dz, IRON_BLOCK, 0);
            for (int dz = 8; dz <= 10; dz++)
                door(bulkhead, floor + 1, dz, 0);
        }

        // An intercom / identification console occupies the side of the
        // chamber without blocking the straight route between both doors.
        console(35, floor, 9, 0);
        put(37, floor + 3, 8, REDSTONE_LAMP, 0);
        put(37, floor + 3, 10, REDSTONE_LAMP, 0);
    }

    private void paintConcealedSurfaceAccess() {
        // One flush, openable service hatch replaces the entire guard station.
        // No room, windows, light, canopy, facade, signage or raised structure.
        // The adjoining surface block anchors the hatch; the ladder meets its underside.
        put(43, this.h, 9, STONE_BRICKS, 0);
        put(42, this.h, 9, WOOD_TRAPDOOR, 4);
        put(42, this.h + 1, 9, AIR, 0);
    }

    private void paintSurfaceApronAndGrating() {
        // The apron deliberately surrounds an OPEN shaft. Do not fill the
        // launch duct with stone underneath a decorative layer of bars.
        for (int dx = -22; dx <= 22; dx++) {
            for (int dz = -22; dz <= 22; dz++) {
                double d = Math.hypot(dx, dz);
                if (d <= 20.0) {
                    // Explicitly clear the generated ground layer: otherwise
                    // the grate would sit on dirt/grass and the launch duct
                    // would remain capped even though the geometry emits bars.
                    put(dx, this.h, dz, AIR, 0);
                } else if (d <= 22.0) {
                    int mat = d > 21.0 ? COBBLE : STONE_BRICKS;
                    put(dx, this.h, dz, mat, 0);
                }
            }
        }

        // Full-aperture steel grating at the same grade as the surrounding
        // apron. One-block mesh openings preserve a view down the launch duct;
        // reinforced cardinal beams and a steel collar carry the ring.
        for (int dx = -19; dx <= 19; dx++) {
            for (int dz = -19; dz <= 19; dz++) {
                if (Math.hypot(dx, dz) <= 19.6
                        && (Math.floorMod(dx, 2) == 0 || Math.floorMod(dz, 2) == 0)) {
                    put(dx, this.h, dz, IRON_BARS, 0);
                }
            }
        }
        for (int d = -19; d <= 19; d++) {
            put(d, this.h, 0, IRON_BLOCK, 0);
            put(0, this.h, d, IRON_BLOCK, 0);
        }
        for (int dx = -20; dx <= 20; dx++) {
            for (int dz = -20; dz <= 20; dz++) {
                double d = Math.hypot(dx, dz);
                if (d >= 19.6 && d <= 20.2) put(dx, this.h, dz, IRON_BLOCK, 0);
                // The steel-block collar below is the perimeter edge; do not
                // add a separate diagonal-only iron-bar ring above it.
            }
        }

        // Four low ventilation risers with protected intakes. They remain
        // equipment plinths, not habitable towers or raised lookout platforms.
        int[][] vents = {{-27,-14},{27,-14},{-27,14},{27,14}};
        for (int[] vent : vents) {
            for (int y = this.h + 1; y <= this.h + 5; y++) {
                put(vent[0], y, vent[1], STONE_BRICKS, 0);
                put(vent[0] + 1, y, vent[1], IRON_BARS, 0);
            }
            put(vent[0], this.h + 6, vent[1], STONE_SLAB, 5);
        }
    }

    private boolean gateGap(int dx, int dz) {
        // Align the closed vehicle gate with the actual gravel spur and portal road.
        return dz == 45 && dx >= 24 && dx <= 34;
    }

    private void paintPerimeterFence() {
        // Reinforced perimeter, steel mesh and a barbed-wire-like top edge.
        // The only opening in the fence line is the closed vehicle gate.
        for (int i = -45; i <= 45; i++) {
            for (int[] point : new int[][]{{i,-45},{i,45},{-45,i},{45,i}}) {
                int dx = point[0], dz = point[1];
                if (gateGap(dx, dz)) continue;
                put(dx, this.h + 1, dz, STONE_BRICKS, 0);
                put(dx, this.h + 2, dz, IRON_BARS, 0);
                put(dx, this.h + 3, dz, IRON_BARS, 0);
                if (Math.floorMod(i, 6) == 0) {
                    put(dx, this.h + 4, dz, TRIPWIRE_HOOK, 0);
                    }
            }
        }

        // Concrete security posts and short, non-habitable floodlight masts.
        // Leave a clear span around the vehicle gate, then brace its two ends.
        for (int i = -45; i <= 45; i += 9) {
            for (int[] p : new int[][]{{i,-45},{i,45},{-45,i},{45,i}}) {
                boolean gateSpan = p[1] == 45 && p[0] >= 23 && p[0] <= 36;
                if (!gateSpan)
                    supportColumn(p[0], p[1], this.h + 1, this.h + 4, STONE_BRICKS, 0);
                if (Math.abs(p[0]) == 45 && Math.abs(p[1]) == 45) {
                    supportColumn(p[0], p[1], this.h + 5, this.h + 7, IRON_BARS, 0);
                    put(p[0], this.h + 8, p[1], REDSTONE_LAMP_ON, 0);
                }
            }
        }
        supportColumn(23, 45, this.h + 1, this.h + 4, STONE_BRICKS, 0);
        supportColumn(35, 45, this.h + 1, this.h + 4, STONE_BRICKS, 0);

        // Closed vehicle gate: three-block-high steel mesh, aligned directly
        // with the access spur. The gravel road reaches the inside face of it.
        for (int dx = 24; dx <= 34; dx++) {
            put(dx, this.h + 1, 45, IRON_BARS, 0);
            put(dx, this.h + 2, 45, IRON_BARS, 0);
            put(dx, this.h + 3, 45, IRON_BARS, 0);
            if (dx % 2 == 0) put(dx, this.h + 4, 45, TRIPWIRE_HOOK, 0);
        }

        // Vehicle spur from the gate to the low-profile access portal.
        for (int dz = 16; dz <= 44; dz++)
            for (int dx = 27; dx <= 31; dx++)
                put(dx, this.h, dz, dz % 5 == 0 ? COBBLE : STONE_SLAB, 5);
        // Turn the vehicle spur west-to-north into the portal's west-facing
        // personnel entrance; it meets the actual door, not a nearby dead end.
        for (int dz = 9; dz <= 16; dz++)
            for (int dx = 30; dx <= 32; dx++)
                put(dx, this.h, dz, dz % 4 == 0 ? COBBLE : STONE_SLAB, 5);
    }

    private void paintSurfaceEquipment() {
        // Antennas and warning beacons are narrow poles with no platform.
        for (int[] pole : new int[][]{{-31,-28},{-31,28},{29,-31},{29,31}}) {
            supportColumn(pole[0], pole[1], this.h + 1, this.h + 6, IRON_BARS, 0);
            put(pole[0], this.h + 7, pole[1], REDSTONE_LAMP_ON, 0);
        }

        if (this.variant == Variant.TEST_FACILITY) {
            // A test range adds sensor pads and instrument tripods, not an
            // additional watchtower or an oversized surface building.
            for (int dx = -14; dx <= 14; dx += 7) {
                for (int dz = -39; dz <= -30; dz += 3) {
                    put(dx, this.h, dz, QUARTZ, 0);
                    put(dx, this.h + 1, dz, IRON_BLOCK, 0);
                    put(dx, this.h + 2, dz, GLASS, 0);
                    put(dx, this.h + 3, dz, REDSTONE_LAMP, 0);
                }
            }
            for (int dx = -12; dx <= 12; dx += 4)
                put(dx, this.h + 1, -26, TERRACOTTA, 4);
            for (int dx = -12; dx <= 12; dx += 4)
                put(dx, this.h + 2, -26, IRON_BARS, 0);
        } else if (this.variant == Variant.VAULT) {
            // The vault installation has a distinctive blue/yellow approach
            // stripe and a sealed, broad blast-door facade on the entry annex.
            for (int dx = 34; dx <= 43; dx++) {
                put(dx, this.h, 2, WOOL, (dx % 2 == 0) ? 11 : 4);
                put(dx, this.h + 1, 2, WOOL, (dx % 2 == 0) ? 11 : 4);
            }
            for (int dx = 35; dx <= 42; dx++) {
                put(dx, this.h + 1, 3, TERRACOTTA, 11);
                put(dx, this.h + 2, 3, TERRACOTTA, 4);
            }
        } else {
            // Standard military site: comms cabinets, cable junctions, air
            // intake heads and low concrete equipment plinths.
            for (int dx : new int[]{-28,-24,-20}) {
                put(dx, this.h, -30, STONE_BRICKS, 0);
                put(dx, this.h + 1, -30, IRON_BLOCK, 0);
                put(dx, this.h + 2, -30, IRON_BARS, 0);
            }
            for (int dz : new int[]{-8,0,8}) {
                put(-31, this.h + 1, dz, IRON_BLOCK, 0);
                put(-31, this.h + 2, dz, REDSTONE_LAMP, 0);
            }
        }
    }

    private void paintVariantSignature() {
        // The control-centre module's exterior is intentionally distinct by
        // variant, without changing its collision envelope or access route.
        if (this.variant == Variant.TEST_FACILITY) {
            for (int y = this.bottom + 19; y <= this.bottom + 55; y += 9) {
                for (int dz = -13; dz <= 13; dz += 2)
                    put(44, y, dz, GLASS_PANE, 0);
            }
            for (int dz = -13; dz <= 13; dz += 4) {
                put(44, this.bottom + 22, dz, TERRACOTTA, 4);
                put(44, this.bottom + 33, dz, TERRACOTTA, 4);
                put(44, this.bottom + 44, dz, TERRACOTTA, 4);
            }
        } else if (this.variant == Variant.VAULT) {
            for (int y = this.bottom + 19; y <= this.bottom + 55; y += 3) {
                for (int dz = -14; dz <= 14; dz++) {
                    if (Math.abs(dz) == 14) put(44, y, dz, WOOL, (y / 3) % 2 == 0 ? 11 : 4);
                }
            }
            // Heavy inner blast door surround at the silo/control connection.
            for (int dy = 0; dy <= 5; dy++) {
                put(20, this.bottom + 50 + dy, -4, IRON_BLOCK, 0);
                put(20, this.bottom + 50 + dy, 4, IRON_BLOCK, 0);
            }
            door(20, this.bottom + 51, 0, 0);
        } else {
            for (int dz = -12; dz <= 12; dz += 4) {
                put(44, this.bottom + 23, dz, IRON_BARS, 0);
                put(44, this.bottom + 33, dz, IRON_BARS, 0);
                put(44, this.bottom + 43, dz, IRON_BARS, 0);
            }
        }
    }
}
