package ch.k42.metropolis.grid.urbanGrid.ocean;

import ch.k42.metropolis.grid.urbanGrid.clipboard.Clipboard;
import ch.k42.metropolis.minions.Cartesian2D;
import ch.k42.metropolis.minions.Cartesian3D;

/**
 * 3D bounding box for a placed ocean ship, calculated from placement location
 * and schematic dimensions. Used for collision detection.
 * 
 * Coordinates are in blocks (not chunks). Box is axis-aligned.
 */
public class OceanBoundingBox {
    public final int minX, maxX;
    public final int minY, maxY;
    public final int minZ, maxZ;

    /**
     * Create a bounding box from placement location and ship dimensions.
     * 
     * @param placeChunkX chunk X coordinate (converted to block coords)
     * @param placeChunkZ chunk Z coordinate (converted to block coords)
     * @param shipSize size of ship (Cartesian3D from Clipboard.getSize())
     * @param buildHeight Y coordinate where ship is placed
     */
    public OceanBoundingBox(int placeChunkX, int placeChunkZ, Cartesian3D shipSize, int buildHeight) {
        // Convert chunk coords to block coords (chunk size = 16 blocks)
        int blockX = placeChunkX * 16;
        int blockZ = placeChunkZ * 16;

        // Calculate extents from size
        this.minX = blockX;
        this.maxX = blockX + shipSize.X;
        this.minZ = blockZ;
        this.maxZ = blockZ + shipSize.Z;
        this.minY = buildHeight;
        this.maxY = buildHeight + shipSize.Y;
    }

    /**
     * Create a bounding box from block coordinates directly.
     */
    public OceanBoundingBox(int minX, int maxX, int minY, int maxY, int minZ, int maxZ) {
        this.minX = minX;
        this.maxX = maxX;
        this.minY = minY;
        this.maxY = maxY;
        this.minZ = minZ;
        this.maxZ = maxZ;
    }

    /**
     * Check if this box overlaps with another (AABB collision).
     */
    public boolean overlaps(OceanBoundingBox other) {
        return !(this.maxX < other.minX || this.minX > other.maxX ||
                 this.maxY < other.minY || this.minY > other.maxY ||
                 this.maxZ < other.minZ || this.minZ > other.maxZ);
    }

    /**
     * Create an expanded box with a buffer around this one.
     * Used for spacing enforcement -- no ships within buffer distance.
     */
    public OceanBoundingBox withBuffer(int buffer) {
        return new OceanBoundingBox(
                this.minX - buffer,
                this.maxX + buffer,
                this.minY - buffer,
                this.maxY + buffer,
                this.minZ - buffer,
                this.maxZ + buffer
        );
    }

    @Override
    public String toString() {
        return String.format("BBox[X:%d-%d Y:%d-%d Z:%d-%d]", minX, maxX, minY, maxY, minZ, maxZ);
    }
}
