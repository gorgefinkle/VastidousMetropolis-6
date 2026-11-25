package ch.k42.metropolis.grid.urbanGrid.farm;

/**
 * A full-scale sealed strategic missile silo. The shaft is 41 blocks across
 * and 82 blocks deep; the rocket is about seven blocks across and over fifty
 * blocks tall. It rests on a reinforced launch table carried by eight piers,
 * with four hold-down gantry towers clamping the fuselage at three levels.
 */
final class FarmMissileSilo extends FarmFeature {
    static final int SHAFT_RADIUS = 20;
    static final int SHAFT_DEPTH = 82;
    static final int ROCKET_BASE_OFFSET = 13;
    static final int ROCKET_BODY_HEIGHT = 43;
    static final int ROCKET_NOSE_HEIGHT = 9;

    private final int cx, cz, h;
    private final long seed;

    FarmMissileSilo(int cx, int cz, int h, long seed) {
        super(cx - 22, cz - 22, cx + 22, cz + 22);
        this.cx = cx;
        this.cz = cz;
        this.h = h;
        this.seed = seed;
    }

    private void block(FarmPainter p, int dx, int y, int dz, int id, int data) {
        int x = this.cx + dx, z = this.cz + dz;
        if (p.in(x, z)) p.set(x, y, z, id, data);
    }

    private void rocketSection(FarmPainter p, int y, double radius, int colour) {
        int reach = (int) Math.ceil(radius);
        for (int dx = -reach; dx <= reach; dx++) {
            for (int dz = -reach; dz <= reach; dz++) {
                if (Math.hypot(dx, dz) <= radius + 0.12) {
                    this.block(p, dx, y, dz, FarmPainter.TERRACOTTA, colour);
                }
            }
        }
    }

    private void paintLaunchTable(FarmPainter p, int bottom, int baseY) {
        // Eight substantial foundation piers transfer launch-table loads to bedrock.
        int[][] supports = {
            {-7,0},{7,0},{0,-7},{0,7},
            {-5,-5},{-5,5},{5,-5},{5,5}
        };
        for (int[] support : supports) {
            for (int y = bottom + 1; y < baseY; y++) {
                this.block(p, support[0], y, support[1], FarmPainter.STONE_BRICK, 0);
            }
        }
        // The launch table top touches the bottom engine skirt directly.
        // There is no unsupported air gap beneath the missile.
        for (int dx = -9; dx <= 9; dx++) {
            for (int dz = -9; dz <= 9; dz++) {
                double d = Math.hypot(dx, dz);
                if (d <= 8.5) {
                    this.block(p, dx, baseY, dz,
                            d <= 4.2 ? FarmPainter.STONE_BRICK : FarmPainter.IRON_BARS, 0);
                }
            }
        }
        // Central reinforced pedestal and an offset exhaust/flame trench.
        for (int y = bottom + 1; y < baseY; y++) {
            for (int dx = -2; dx <= 2; dx++) {
                this.block(p, dx, y, 0, FarmPainter.STONE_BRICK, 0);
            }
        }
        for (int dx = -4; dx <= 4; dx++) {
            this.block(p, dx, bottom + 1, -13, FarmPainter.STONE_BRICK, 0);
            this.block(p, dx, bottom + 1, -14, FarmPainter.STONE_BRICK, 0);
        }
        for (int y = bottom + 2; y <= bottom + 8; y++) {
            for (int dx = -4; dx <= 4; dx++) {
                this.block(p, dx, y, -14, FarmPainter.COBBLE, 0);
            }
        }
    }

    private void paintHoldDownGantry(FarmPainter p, int bottom, int baseY) {
        int[][] towers = {{-9,0},{9,0},{0,-9},{0,9}};
        int[] clampLevels = {baseY + 10, baseY + 25, baseY + 39};
        for (int[] tower : towers) {
            int tx = tower[0], tz = tower[1];
            for (int y = bottom + 1; y <= baseY + 42; y++) {
                this.block(p, tx, y, tz, FarmPainter.STONE_BRICK, 0);
                this.block(p, tx != 0 ? tx : 1, y, tz != 0 ? tz : 1,
                        FarmPainter.IRON_BARS, 0);
            }
            for (int level : clampLevels) {
                if (tx != 0) {
                    int sign = Integer.signum(tx);
                    for (int step = 4; step <= 8; step++) {
                        this.block(p, sign * step, level, 0, FarmPainter.IRON_BARS, 0);
                    }
                    this.block(p, sign * 4, level, 0, FarmPainter.STONE_BRICK, 0);
                    this.block(p, sign * 5, level - 1, 0, FarmPainter.IRON_BARS, 0);
                } else {
                    int sign = Integer.signum(tz);
                    for (int step = 4; step <= 8; step++) {
                        this.block(p, 0, level, sign * step, FarmPainter.IRON_BARS, 0);
                    }
                    this.block(p, 0, level, sign * 4, FarmPainter.STONE_BRICK, 0);
                    this.block(p, 0, level - 1, sign * 5, FarmPainter.IRON_BARS, 0);
                }
            }
        }
    }

    private void paintServiceGalleries(FarmPainter p, int bottom, int h) {
        int[] levels = {bottom + 29, bottom + 49, h - 22};
        for (int level : levels) {
            for (int dx = -16; dx <= 16; dx++) {
                for (int dz = -16; dz <= 16; dz++) {
                    double d = Math.hypot(dx, dz);
                    if (d >= 11.5 && d <= 15.5) {
                        this.block(p, dx, level, dz, FarmPainter.SLAB_STONE, 5);
                    }
                    if (d >= 14.6 && d <= 15.5
                            && (Math.abs(dx) == 15 || Math.abs(dz) == 15)) {
                        this.block(p, dx, level + 1, dz, FarmPainter.IRON_BARS, 0);
                    }
                }
            }
            // Radial gangways link the annular service galleries to the gantry.
            for (int r = 9; r <= 12; r++) {
                this.block(p, r, level, 0, FarmPainter.SLAB_STONE, 5);
                this.block(p, -r, level, 0, FarmPainter.SLAB_STONE, 5);
                this.block(p, 0, level, r, FarmPainter.SLAB_STONE, 5);
                this.block(p, 0, level, -r, FarmPainter.SLAB_STONE, 5);
            }
        }
        // Permanent service ladder/elevator guide at the inner shaft wall.
        for (int y = bottom + 2; y <= h - 2; y++) {
            this.block(p, -15, y, 0, FarmPainter.LADDER, 4);
            this.block(p, -16, y, 0, FarmPainter.STONE_BRICK, 0);
            if (y % 8 == 0) this.block(p, -14, y, 0, FarmPainter.IRON_BARS, 0);
        }
    }

    private void paintRocket(FarmPainter p, int baseY) {
        // Engine skirt begins one block above the launch table surface.
        for (int y = baseY + 1; y <= baseY + 3; y++) {
            double radius = y == baseY + 1 ? 3.9 : 3.5;
            this.rocketSection(p, y, radius, y == baseY + 1 ? 8 : 7);
        }
        // Main 43-block fuselage, approximately seven blocks in diameter.
        for (int y = baseY + 4; y <= baseY + ROCKET_BODY_HEIGHT; y++) {
            int offset = y - (baseY + 4);
            int colour = offset % 11 == 0 ? 8 : (offset % 7 == 0 ? 13 : 0);
            this.rocketSection(p, y, 3.35, colour);
            if (offset == 0 || offset == 21 || offset == 42) {
                for (int dx = -4; dx <= 4; dx++) {
                    this.block(p, dx, y, -4, FarmPainter.IRON_BARS, 0);
                    this.block(p, dx, y, 4, FarmPainter.IRON_BARS, 0);
                }
            }
        }
        // Four broad aft fins, blended into the engine skirt.
        for (int y = baseY + 2; y <= baseY + 12; y++) {
            int reach = 4 + (y - (baseY + 2)) / 2;
            for (int r = 3; r <= reach; r++) {
                this.block(p, r, y, 0, FarmPainter.TERRACOTTA, 13);
                this.block(p, -r, y, 0, FarmPainter.TERRACOTTA, 13);
                this.block(p, 0, y, r, FarmPainter.TERRACOTTA, 13);
                this.block(p, 0, y, -r, FarmPainter.TERRACOTTA, 13);
            }
        }
        // Nine-block tapered nose cone remains below the sealed upper closure.
        for (int i = 0; i < ROCKET_NOSE_HEIGHT; i++) {
            double radius = Math.max(0.0,
                    3.0 * (1.0 - (double)i / (ROCKET_NOSE_HEIGHT - 1)));
            this.rocketSection(p, baseY + ROCKET_BODY_HEIGHT + i + 1, radius, 14);
        }
    }

    private void paintSurfaceWorks(FarmPainter p) {
        // Reinforced concrete apron around the flush armored closure.
        for (int dx = -22; dx <= 22; dx++) {
            for (int dz = -22; dz <= 22; dz++) {
                double d = Math.hypot(dx, dz);
                if (d <= 22.0) {
                    int material = d > 19.5 ? FarmPainter.MOSSY_COBBLE
                            : d > 11.0 ? FarmPainter.COBBLE : FarmPainter.STONE_BRICK;
                    this.block(p, dx, this.h, dz, material, 0);
                }
            }
        }
        // Thick closed blast hatch and pressure collar.
        for (int dx = -9; dx <= 9; dx++) {
            for (int dz = -9; dz <= 9; dz++) {
                if (Math.hypot(dx, dz) <= 9.2) {
                    this.block(p, dx, this.h, dz, FarmPainter.STONE_BRICK, 0);
                    if (Math.hypot(dx, dz) >= 7.5) {
                        this.block(p, dx, this.h + 1, dz, FarmPainter.IRON_BARS, 0);
                    }
                }
            }
        }
        for (int dx = -2; dx <= 2; dx++) {
            for (int dz = -2; dz <= 2; dz++) {
                this.block(p, dx, this.h, dz, 167, 0); // closed iron hatch plates
            }
        }
        // Four capped ventilation risers.
        int[][] vents = {{-16,-8},{16,-8},{-16,8},{16,8}};
        for (int[] vent : vents) {
            for (int y = this.h + 1; y <= this.h + 6; y++) {
                this.block(p, vent[0], y, vent[1], FarmPainter.STONE_BRICK, 0);
                if (y <= this.h + 4) {
                    this.block(p, vent[0] + 1, y, vent[1], FarmPainter.IRON_BARS, 0);
                }
            }
            this.block(p, vent[0], this.h + 7, vent[1], FarmPainter.SLAB_STONE, 5);
        }
        // Surface control annex set into the apron, clear of the main hatch.
        for (int dx = 10; dx <= 20; dx++) {
            for (int dz = 9; dz <= 19; dz++) {
                this.block(p, dx, this.h, dz, FarmPainter.COBBLE, 0);
                boolean edge = dx == 10 || dx == 20 || dz == 9 || dz == 19;
                for (int y = this.h + 1; y <= this.h + 4; y++) {
                    if (edge) {
                        boolean window = y == this.h + 2 && (dx == 10 || dx == 20);
                        this.block(p, dx, y, dz,
                                window ? FarmPainter.IRON_BARS : FarmPainter.STONE_BRICK, 0);
                    } else {
                        this.block(p, dx, y, dz, FarmPainter.AIR, 0);
                    }
                }
                this.block(p, dx, this.h + 5, dz, FarmPainter.STONE_BRICK, 0);
            }
        }
        // Closed pressure-rated service door; the annex is abandoned but sealed.
        this.block(p, 15, this.h + 1, 9, 71, 0);
        this.block(p, 15, this.h + 2, 9, 71, 8);
    }

    @Override void paint(FarmPainter p) {
        int bottom = Math.max(p.minY + 8, this.h - SHAFT_DEPTH);
        int baseY = bottom + ROCKET_BASE_OFFSET;

        // 41-block reinforced circular shaft: outer armor, inner lining, open bore.
        for (int dx = -SHAFT_RADIUS; dx <= SHAFT_RADIUS; dx++) {
            for (int dz = -SHAFT_RADIUS; dz <= SHAFT_RADIUS; dz++) {
                if (!p.in(this.cx + dx, this.cz + dz)) continue;
                double d = Math.hypot(dx, dz);
                if (d > SHAFT_RADIUS) continue;
                for (int y = bottom + 1; y <= this.h - 1; y++) {
                    if (d >= 18.0) {
                        int ring = (y - bottom) % 10;
                        int material = ring == 0 || FarmRandom.unit(this.seed,
                                this.cx + dx, this.cz + dz, y + 900) < .025
                                ? FarmPainter.MOSSY_COBBLE : FarmPainter.STONE_BRICK;
                        this.block(p, dx, y, dz, material, 0);
                    } else if (d >= 16.2) {
                        this.block(p, dx, y, dz, FarmPainter.COBBLE, 0);
                    } else {
                        this.block(p, dx, y, dz, FarmPainter.AIR, 0);
                    }
                }
                if (d <= 17.8) {
                    this.block(p, dx, bottom, dz, FarmPainter.STONE_BRICK, 0);
                }
            }
        }

        this.paintLaunchTable(p, bottom, baseY);
        this.paintRocket(p, baseY);
        this.paintHoldDownGantry(p, bottom, baseY);
        this.paintServiceGalleries(p, bottom, this.h);
        this.paintSurfaceWorks(p);
    }
}
