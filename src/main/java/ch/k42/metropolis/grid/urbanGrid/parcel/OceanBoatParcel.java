package ch.k42.metropolis.grid.urbanGrid.parcel;

import ch.k42.metropolis.generator.MetropolisGenerator;
import ch.k42.metropolis.grid.urbanGrid.UrbanGrid;
import ch.k42.metropolis.grid.urbanGrid.clipboard.Clipboard;
import ch.k42.metropolis.minions.Cartesian2D;
import ch.k42.metropolis.minions.Cartesian3D;
import ch.k42.metropolis.minions.GridRandom;
import ch.k42.metropolis.minions.Minions;
import org.bukkit.Chunk;
import org.bukkit.Material;
import org.bukkit.World;

/**
 * An OceanParcel that also carries one boat/ship schematic.
 *
 * Deliberately a SUBCLASS of OceanParcel rather than a ClipboardParcel that
 * replaces it: the ocean chunk still has to be carved (water, bed, beach)
 * by OceanParcel.populate(), and a boat is only a decoration on top of that
 * water. Replacing the parcel with a ClipboardParcel would have skipped the
 * carve entirely and pasted the hull onto whatever raw terrain was there.
 * Because it is still an OceanParcel, every existing "is this ocean?" check
 * (instanceof OceanParcel, isOpenWater(), getDepthTier()) keeps working.
 *
 * The constructor re-registers this parcel over the plain OceanParcel the
 * super constructor just put in the same cell (fillParcels), so building one
 * is all that's needed to "place" a boat.
 *
 * Paste level: the schematic's ground-level layer (CONFIG groundLevelY) is
 * aligned with the TOP WATER BLOCK, so the hull sits below the waterline and
 * the deck/mast above it. Any air the schematic writes below the waterline
 * is refilled with water afterwards so the boat doesn't carve a dry pocket
 * into the sea.
 */
public class OceanBoatParcel extends OceanParcel {

    private final Clipboard boat;
    private final int spanX;
    private final int spanZ;

    public OceanBoatParcel(UrbanGrid grid, Cartesian2D base, Clipboard boat) {
        super(grid, base);
        this.boat = boat;
        Cartesian3D size = boat.getSize();
        this.spanX = Math.max(1, (size.X + 15) >> 4);
        this.spanZ = Math.max(1, (size.Z + 15) >> 4);
        // Every other chunk under the hull becomes a tail parcel that never
        // carves by itself -- see populate().
        for (int cx = 0; cx < this.spanX; cx++) {
            for (int cz = 0; cz < this.spanZ; cz++) {
                if (cx != 0 || cz != 0) {
                    new OceanBoatTailParcel(grid, new Cartesian2D(this.chunkX + cx, this.chunkZ + cz));
                }
            }
        }
        grid.getStatistics().logSchematic(boat);
    }

    public Clipboard getBoat() {
        return this.boat;
    }

    @Override
    public void populate(MetropolisGenerator generator, Chunk chunk) {
        if (chunk.getX() != this.chunkX || chunk.getZ() != this.chunkZ) {
            return;
        }
        World world = generator.getWorld();
        if (world == null || this.boat == null) {
            super.populate(generator, chunk);
            return;
        }

        // Carve every chunk under the boat FIRST (tails included), so no carve
        // can run after the paste and cut the boat off at a chunk border.
        for (int cx = 0; cx < this.spanX; cx++) {
            for (int cz = 0; cz < this.spanZ; cz++) {
                if (cx == 0 && cz == 0) {
                    continue;
                }
                Parcel p = this.grid.peekParcel(this.chunkX + cx, this.chunkZ + cz);
                if (p instanceof OceanBoatTailParcel) {
                    ((OceanBoatTailParcel) p).carveForBoat(generator);
                }
            }
        }
        super.populate(generator, chunk);

        int baseX = this.chunkX << 4;
        int baseZ = this.chunkZ << 4;
        int waterY = this.grid.getOceanProvider().sample(baseX + 8.0, baseZ + 8.0).surfaceY;

        try {
            this.boat.paste(generator, new Cartesian2D(this.chunkX, this.chunkZ), waterY);
        } catch (Exception e) {
            Minions.w("Ocean boat paste failed at chunk (" + this.chunkX + "," + this.chunkZ + "): " + e);
            return;
        }

        Cartesian3D size = this.boat.getSize();
        refillExteriorWater(world, baseX, baseZ, size.X, size.Z, Math.max(1, this.boat.getBottom(waterY)), waterY);
        nameChestsAfterPaste(world, baseX, baseZ, size, this.boat.getBottom(waterY));
    }

    /**
     * Safety net for chest loot. FileClipboard.paste() is supposed to name every
     * surviving chest "(section)a<collection>_<level>" (the format ChestLootz
     * requires: a custom name starting with the section sign), but on boats those
     * chests were coming through unnamed and therefore never got loot. This pass
     * runs after the paste and names any chest in the boat's box that still has no
     * such name, using the schematic's own lootCollections / lootMinLevel /
     * lootMaxLevel. Chests that were already named are left alone, and no chest is
     * ever removed here (the schematic's chestOddsInPercent is applied by the paste).
     */
    private void nameChestsAfterPaste(World world, int baseX, int baseZ, Cartesian3D size, int bottomY) {
        if (!ch.k42.metropolis.plugin.PluginConfig.isChestRenaming()) {
            return;
        }
        try {
            ch.k42.metropolis.grid.urbanGrid.config.SchematicConfig config = this.boat.getConfig();
            if (config.getLootCollections() == null || config.getLootCollections().length == 0) {
                Minions.w("Boat " + config.getBuildName() + " has no lootCollections; its chests stay empty.");
                return;
            }
            GridRandom rand = this.grid.getRandom();
            int min = Math.max(1, config.getLootMinLevel());
            int max = Math.max(min, config.getLootMaxLevel());
            int found = 0;
            int alreadyNamed = 0;
            int renamed = 0;
            for (int x = baseX; x < baseX + size.X; x++) {
                for (int z = baseZ; z < baseZ + size.Z; z++) {
                    for (int y = Math.max(1, bottomY); y < bottomY + size.Y && y < 256; y++) {
                        org.bukkit.block.Block block = world.getBlockAt(x, y, z);
                        if (block.getType() != Material.CHEST) {
                            continue;
                        }
                        found++;
                        org.bukkit.block.Chest chest = (org.bukkit.block.Chest) block.getState();
                        String current = chest.getCustomName();
                        if (current != null && current.startsWith("\u00a7")) {
                            alreadyNamed++;
                            continue;
                        }
                        int level = min + rand.getRandomInt(max - min + 1);
                        ch.k42.metropolis.grid.urbanGrid.enums.LootType collection = config.getRandomLootCollection(rand);
                        if (collection == null) {
                            collection = ch.k42.metropolis.grid.urbanGrid.enums.LootType.INDUSTRIAL;
                        }
                        String name = "\u00a7" + org.bukkit.ChatColor.GREEN.getChar()
                                + collection.name + "_" + level;
                        ch.k42.metropolis.minions.DirtyHacks.setChestName(chest, name);
                        chest.update();
                        renamed++;
                    }
                }
            }
            Minions.dOcean("[BOAT_CHEST] %s at chunk (%d,%d): %d chest(s), %d already named by paste, %d named here",
                    config.getBuildName(), this.chunkX, this.chunkZ, found, alreadyNamed, renamed);
        } catch (Exception e) {
            Minions.w("Boat chest naming failed at chunk (" + this.chunkX + "," + this.chunkZ + "): " + e);
        }
    }

    /**
     * The paste writes the schematic's air over the sea, which would leave a dry
     * pocket around the hull. This turns that air back into (non-flowing) water,
     * but ONLY the air that is connected to the outside water at or below the
     * waterline: flood-fill from the paste box's outer faces through air cells
     * with y <= waterY. Air sealed inside the hull (cabins, holds) is not
     * reachable, so it stays dry instead of being flooded.
     */
    private static void refillExteriorWater(World world, int baseX, int baseZ, int width, int length, int minY, int waterY) {
        int height = waterY - minY + 1;
        if (width <= 0 || length <= 0 || height <= 0) {
            return;
        }
        int total = width * height * length;
        boolean[] air = new boolean[total];
        for (int x = 0; x < width; x++) {
            for (int y = 0; y < height; y++) {
                for (int z = 0; z < length; z++) {
                    if (world.getBlockAt(baseX + x, minY + y, baseZ + z).getType().isAir()) {
                        air[(x * height + y) * length + z] = true;
                    }
                }
            }
        }

        boolean[] flood = new boolean[total];
        int[] queue = new int[total];
        int head = 0;
        int tail = 0;
        // Seeds: air cells on the outer faces of the box (side walls and bottom) touch untouched sea/bed.
        for (int x = 0; x < width; x++) {
            for (int y = 0; y < height; y++) {
                for (int z = 0; z < length; z++) {
                    boolean boundary = x == 0 || x == width - 1 || z == 0 || z == length - 1 || y == 0;
                    int i = (x * height + y) * length + z;
                    if (boundary && air[i]) {
                        flood[i] = true;
                        queue[tail++] = i;
                    }
                }
            }
        }
        while (head < tail) {
            int i = queue[head++];
            int z = i % length;
            int y = (i / length) % height;
            int x = i / (length * height);
            tail = spread(air, flood, queue, tail, x - 1, y, z, width, height, length);
            tail = spread(air, flood, queue, tail, x + 1, y, z, width, height, length);
            tail = spread(air, flood, queue, tail, x, y - 1, z, width, height, length);
            tail = spread(air, flood, queue, tail, x, y + 1, z, width, height, length);
            tail = spread(air, flood, queue, tail, x, y, z - 1, width, height, length);
            tail = spread(air, flood, queue, tail, x, y, z + 1, width, height, length);
        }

        for (int i = 0; i < total; i++) {
            if (flood[i]) {
                int z = i % length;
                int y = (i / length) % height;
                int x = i / (length * height);
                world.getBlockAt(baseX + x, minY + y, baseZ + z).setType(Material.WATER, false);
            }
        }
    }

    private static int spread(boolean[] air, boolean[] flood, int[] queue, int tail,
                              int x, int y, int z, int width, int height, int length) {
        if (x < 0 || y < 0 || z < 0 || x >= width || y >= height || z >= length) {
            return tail;
        }
        int i = (x * height + y) * length + z;
        if (air[i] && !flood[i]) {
            flood[i] = true;
            queue[tail++] = i;
        }
        return tail;
    }
}