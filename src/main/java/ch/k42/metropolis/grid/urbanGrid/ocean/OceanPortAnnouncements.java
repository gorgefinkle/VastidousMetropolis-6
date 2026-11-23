package ch.k42.metropolis.grid.urbanGrid.ocean;

import ch.k42.metropolis.generator.MetropolisGenerator;
import ch.k42.metropolis.grid.urbanGrid.parcel.StreetParcel;
import ch.k42.metropolis.minions.Minions;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.entity.Player;

/**
 * Generator notifications: log synchronously as soon as each decision is made, and send
 * in-game messages on the primary thread (chunk planning can happen asynchronously).
 * PLANNED means reserved, not yet built. BUILT means blocks were placed in the world.
 */
public final class OceanPortAnnouncements {
    private OceanPortAnnouncements() { }

    private static void chat(final MetropolisGenerator generator, final String message) {
        if (generator == null || generator.getPlugin() == null) return;
        Runnable send = new Runnable() {
            @Override public void run() {
                for (Player player : Bukkit.getOnlinePlayers()) {
                    if (player.getWorld() != null && generator.worldName.equals(player.getWorld().getName())) {
                        player.sendMessage(ChatColor.GOLD + "[Metropolis Ports] " + ChatColor.YELLOW + message);
                    }
                }
            }
        };
        if (Bukkit.isPrimaryThread()) {
            send.run();
        } else {
            Bukkit.getScheduler().runTask(generator.getPlugin(), send);
        }
    }

    public static void planned(MetropolisGenerator generator, int chunkX, int chunkZ, int streetDistance) {
        int x = (chunkX << 4) + 8;
        int z = (chunkZ << 4) + 8;
        int y = StreetParcel.ROAD_SURFACE_Y + 3;
        Minions.i("[Ports] PLANNED world=%s chunk=(%d,%d) blocks=(%d,%d,%d) "
                + "streetDistance=%d chunks. Teleport: /tp %d %d %d (not built until generated)",
                generator.worldName, chunkX, chunkZ, x, y, z, streetDistance, x, y, z);
        chat(generator, "Port site FOUND at X=" + x + " Z=" + z + " (" + generator.worldName
                + "); /tp " + x + " " + y + " " + z + ". Planned, not built yet.");
    }

    public static void built(MetropolisGenerator generator, int chunkX, int chunkZ) {
        int x = (chunkX << 4) + 8;
        int z = (chunkZ << 4) + 8;
        int y = StreetParcel.ROAD_SURFACE_Y + 3;
        Minions.i("[Ports] BUILT world=%s chunk=(%d,%d), blocks=(%d,%d,%d). Teleport: /tp %d %d %d",
                generator.worldName, chunkX, chunkZ, x, y, z, x, y, z);
        chat(generator, "Port BUILT at X=" + x + " Z=" + z + ". /tp " + x + " " + y + " " + z);
    }

    public static void noSite(MetropolisGenerator generator, int tileX, int tileZ) {
        chat(generator, "No valid port in surveyed tile (chunk " + tileX + ", " + tileZ
                + "). See server log for rejection counts.");
    }
}
