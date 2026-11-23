package ch.k42.metropolis.commands;

import ch.k42.metropolis.generator.MetropolisGenerator;
import ch.k42.metropolis.grid.urbanGrid.UrbanGrid;
import ch.k42.metropolis.grid.urbanGrid.river.BoundedRiverSites;
import ch.k42.metropolis.minions.Minions;
import ch.k42.metropolis.plugin.MetropolisPlugin;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

/**
 * /creek        -- tells you where the nearest creek is (its building's wall, where the water starts).
 * /creek tp     -- the same, and teleports you there.
 * Creek sites are a pure function of the seed, so this works for creeks in tiles that have not been generated yet.
 */
public class CommandMetropolisCreek implements CommandExecutor {
   /** Only a stop for a world with no creek site at all: this many tiles out in every direction. */
   private static final int MAX_RINGS = 100;

   private final MetropolisPlugin plugin;

   public CommandMetropolisCreek(MetropolisPlugin plugin) {
      this.plugin = plugin;
   }

   public boolean onCommand(CommandSender sender, Command command, String label, String[] split) {
      if (!(sender instanceof Player)) {
         sender.sendMessage("This command is only usable by a player.");
         return false;
      }
      Player player = (Player) sender;
      if (!player.hasPermission("metropolis.command")) {
         sender.sendMessage("You do not have permission to use this command");
         return false;
      }
      MetropolisGenerator generator = Minions.getGeneratorForPlayer(player);
      if (generator == null) {
         player.sendMessage("This is not a Metropolis world.");
         return false;
      }
      BoundedRiverSites provider = UrbanGrid.boundedSitesFor(generator);
      Location here = player.getLocation();
      BoundedRiverSites.Site site = provider.nearestSite(here.getBlockX() >> 4, here.getBlockZ() >> 4, MAX_RINGS);
      if (site == null) {
         player.sendMessage("No creek site found within " + MAX_RINGS + " tiles. Check bounded creek origin schematics and creekSiteChancePercent.");
         return true;
      }
      int x = site.origin.chunkX * 16 + 8;
      int z = site.origin.chunkZ * 16 + 8;
      int distance = (int) Math.round(Math.sqrt(Math.pow(x - here.getBlockX(), 2) + Math.pow(z - here.getBlockZ(), 2)));
      player.sendMessage("Nearest creek: origin at x=" + x + " z=" + z + " (" + distance + " blocks away), flowing "
              + site.origin.outward + " out of its building, water " + site.origin.widthBlocks + " wide, building tile root "
              + (site.hostTileX * 64) + "," + (site.hostTileZ * 64));
      if (split.length > 0 && split[0].equalsIgnoreCase("tp")) {
         World world = player.getWorld();
         int y = world.getHighestBlockYAt(x, z) + 2; // loads (and so generates) the chunk
         player.teleport(new Location(world, x + 0.5, y, z + 0.5));
      } else {
         player.sendMessage("Use /creek tp to go there.");
      }
      return true;
   }
}
