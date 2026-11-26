package ch.k42.metropolis.commands;

import ch.k42.metropolis.generator.MetropolisGenerator;
import ch.k42.metropolis.grid.urbanGrid.river.RiverConfig;
import ch.k42.metropolis.grid.urbanGrid.wild.WildernessFeatures;
import ch.k42.metropolis.minions.Minions;
import ch.k42.metropolis.plugin.MetropolisPlugin;
import ch.k42.metropolis.plugin.PluginConfig;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;

import java.util.List;
import java.util.Locale;

public final class CommandMetropolisLake implements CommandExecutor, TabCompleter {
   private final MetropolisPlugin plugin;

   public CommandMetropolisLake(MetropolisPlugin plugin) { this.plugin = plugin; }

   @Override
   public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
      if (!(sender instanceof Player player)) {
         sender.sendMessage("This command is only usable by a player.");
         return true;
      }
      if (!player.hasPermission("metropolis.command")) {
         player.sendMessage("You do not have permission to use this command.");
         return true;
      }
      if (args.length > 1 || (args.length == 1 && !args[0].equalsIgnoreCase("tp"))) {
         player.sendMessage("Usage: /lake [tp]");
         return true;
      }
      MetropolisGenerator generator = Minions.getGeneratorForPlayer(player);
      if (generator == null || generator.getWorld() != player.getWorld() || generator.getWorldSeed() == null) {
         player.sendMessage("This is not a Metropolis-generated world.");
         return true;
      }
      if (!PluginConfig.isCityRingsEnabled() || !PluginConfig.isForestGenerationEnabled()
              || !RiverConfig.isRiverEnabled()) {
         player.sendMessage("Named lakes require generator.cityRings.enabled, generator.forest.generate, "
                 + "and generator.river.enabled. Their defaults are all true.");
         return true;
      }

      final int originX = player.getLocation().getBlockX();
      final int originZ = player.getLocation().getBlockZ();
      final World world = player.getWorld();
      final boolean teleport = args.length == 1;
      player.sendMessage("Searching outward for the nearest planned lake. There is no fixed search radius; "
              + "the search checks the deterministic feature plan.");
      plugin.getServer().getScheduler().runTaskAsynchronously(plugin, () -> {
         WildernessFeatures.LakeSite lake = WildernessFeatures.forSeed(generator.getWorldSeed())
                 .nearestLake(originX, originZ);
         plugin.getServer().getScheduler().runTask(plugin, () -> {
            if (!player.isOnline() || player.getWorld() != world || !plugin.isEnabled()) return;
            if (lake == null) {
               player.sendMessage("No eligible lake could be planned. Check the lake prerequisites and river receiving-water configuration.");
               return;
            }
            int centerX = (int)Math.round(lake.centerX), centerZ = (int)Math.round(lake.centerZ);
            long dx = (long)centerX - originX, dz = (long)centerZ - originZ;
            long distance = Math.round(Math.sqrt((double)dx * dx + (double)dz * dz));
            player.sendMessage(lake.name + " is centered at x=" + centerX + " z=" + centerZ
                    + " (" + distance + " blocks away); waterline y=" + lake.waterY + ".");
            if (!teleport) {
               player.sendMessage("Use /lake tp to go to its nearest safe shoreline.");
               return;
            }
            Location destination = shorelineLanding(world, lake, player.getLocation());
            if (destination == null) {
               player.sendMessage("The lake was found, but no safe shoreline landing was available.");
               return;
            }
            if (player.teleport(destination)) {
               player.sendMessage("Teleported to the shoreline of " + lake.name + " at x="
                       + destination.getBlockX() + " y=" + destination.getBlockY()
                       + " z=" + destination.getBlockZ() + ".");
            } else player.sendMessage("Teleport to " + lake.name + " was cancelled.");
         });
      });
      return true;
   }

   private static Location shorelineLanding(World world, WildernessFeatures.LakeSite lake, Location player) {
      double c = Math.cos(lake.angle), s = Math.sin(lake.angle);
      double dx = player.getX() - lake.centerX, dz = player.getZ() - lake.centerZ;
      double nx = (dx * c - dz * s) / lake.radiusX;
      double nz = (dx * s + dz * c) / lake.radiusZ;
      double length = Math.hypot(nx, nz);
      if (length < 1.0e-6) { nx = 1.0; nz = 0.0; }
      else { nx /= length; nz /= length; }
      for (double radius : new double[] {0.90, 0.95, 0.98}) {
         double x = lake.centerX + radius * (nx * lake.radiusX * c + nz * lake.radiusZ * s);
         double z = lake.centerZ + radius * (-nx * lake.radiusX * s + nz * lake.radiusZ * c);
         int bx = (int)Math.floor(x), bz = (int)Math.floor(z);
         int groundY = world.getHighestBlockYAt(bx, bz);
         if (groundY < world.getMinHeight() || groundY + 2 >= world.getMaxHeight()) continue;
         Block ground = world.getBlockAt(bx, groundY, bz);
         Block feet = world.getBlockAt(bx, groundY + 1, bz), head = world.getBlockAt(bx, groundY + 2, bz);
         if (!ground.getType().isSolid() || ground.isLiquid() || !feet.isPassable() || feet.isLiquid()
                 || !head.isPassable() || head.isLiquid()) continue;
         return new Location(world, bx + .5, groundY + 1.0, bz + .5, player.getYaw(), player.getPitch());
      }
      return null;
   }

   @Override
   public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
      if (args.length != 1) return List.of();
      String typed = args[0] == null ? "" : args[0].toLowerCase(Locale.ROOT);
      return "tp".startsWith(typed) ? List.of("tp") : List.of();
   }
}
