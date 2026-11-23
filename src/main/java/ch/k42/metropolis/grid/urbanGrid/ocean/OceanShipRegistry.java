package ch.k42.metropolis.grid.urbanGrid.ocean;

import ch.k42.metropolis.grid.urbanGrid.clipboard.Clipboard;
import java.util.ArrayList;
import java.util.List;

/**
 * Registry of all placed ocean ships in the current region.
 * Tracks bounding boxes and validates placement collisions.
 *
 * Pre-validation model: before pasting, check if placement is valid.
 * Only register if validation passes.
 */
public class OceanShipRegistry {
    private final List<PlacedShip> ships = new ArrayList<>();
    private final int spacingBuffer;  // Minimum distance between ships

    /**
     * @param spacingBuffer minimum block distance to maintain between ships (prevents clipping)
     */
    public OceanShipRegistry(int spacingBuffer) {
        this.spacingBuffer = spacingBuffer;
    }

    /**
     * Check if a ship can be placed at the given location without collision.
     * Returns true if the placement is valid (no overlap with buffered existing ships).
     */
    public synchronized boolean canPlace(OceanBoundingBox bbox) {
        for (PlacedShip ship : this.ships) {
            OceanBoundingBox bufferedShip = ship.bbox.withBuffer(this.spacingBuffer);
            if (bufferedShip.overlaps(bbox)) {
                return false;  // Collision detected
            }
        }
        return true;  // Clear to place
    }

    /**
     * Register a ship as placed. Call only after successful paste + validation.
     */
    public synchronized void register(OceanBoundingBox bbox, Clipboard ship, int chunkX, int chunkZ) {
        this.ships.add(new PlacedShip(bbox, ship, chunkX, chunkZ));
    }

    /**
     * Snapshot of every ship registered so far (safe to iterate while another
     * tile is still placing: neighbouring tiles read this).
     */
    public synchronized List<PlacedShip> getShips() {
        return new ArrayList<>(this.ships);
    }

    /**
     * Get total number of ships placed so far.
     */
    public synchronized int getShipCount() {
        return this.ships.size();
    }

    /**
     * Represents a single placed ship with its metadata.
     */
    public static class PlacedShip {
        public final OceanBoundingBox bbox;
        public final Clipboard clipboard;
        public final int chunkX;
        public final int chunkZ;

        public PlacedShip(OceanBoundingBox bbox, Clipboard clipboard, int chunkX, int chunkZ) {
            this.bbox = bbox;
            this.clipboard = clipboard;
            this.chunkX = chunkX;
            this.chunkZ = chunkZ;
        }

        public String getGroupId() {
            return this.clipboard.getGroupId();
        }
    }
}