package ch.k42.metropolis.commands;

import ch.k42.metropolis.generator.MetropolisGenerator;
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

public final class CommandMetropolisMassif implements CommandExecutor, TabCompleter {
   private final MetropolisPlugin plugin;
   public CommandMetropolisMassif(MetropolisPlugin plugin) { this.plugin = plugin; }

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
         player.sendMessage("Usage: /massif [tp]");
         return true;
      }
      MetropolisGenerator generator = Minions.getGeneratorForPlayer(player);
      if (generator == null || generator.getWorld() != player.getWorld() || generator.getWorldSeed() == null) {
         player.sendMessage("This is not a Metropolis-generated world.");
         return true;
      }
      if (!PluginConfig.isCityRingsEnabled() || !PluginConfig.isForestGenerationEnabled()) {
         player.sendMessage("Named massifs require generator.cityRings.enabled and generator.forest.generate. "
                 + "Their defaults are both true.");
         return true;
      }

      final int originX = player.getLocation().getBlockX(), originZ = player.getLocation().getBlockZ();
      final World world = player.getWorld();
      final boolean teleport = args.length == 1;
      player.sendMessage("Searching outward for the nearest named massif, independently of the mountain biome. "
              + "There is no fixed search radius.");
      plugin.getServer().getScheduler().runTaskAsynchronously(plugin, () -> {
         WildernessFeatures.MountainSite mountain = WildernessFeatures.forSeed(generator.getWorldSeed())
                 .nearestMountain(originX, originZ);
         plugin.getServer().getScheduler().runTask(plugin, () -> {
            if (!player.isOnline() || player.getWorld() != world || !plugin.isEnabled()) return;
            if (mountain == null) {
               player.sendMessage("No named massif could be planned with the current generator settings.");
               return;
            }
            int centerX = (int)Math.round(mountain.centerX), centerZ = (int)Math.round(mountain.centerZ);
            long dx = (long)centerX - originX, dz = (long)centerZ - originZ;
            long distance = Math.round(Math.sqrt((double)dx * dx + (double)dz * dz));
            player.sendMessage(mountain.name + " is centered at x=" + centerX + " z=" + centerZ
                    + " (" + distance + " blocks away).");
            if (!teleport) {
               player.sendMessage("Use /massif tp to travel to a safe slope on this massif.");
               return;
            }
            Location destination = massifLanding(world, mountain, player.getLocation());
            if (destination == null) {
               player.sendMessage("The massif was found, but no safe slope landing was available.");
               return;
            }
            if (player.teleport(destination)) {
               player.sendMessage("Teleported to " + mountain.name + " at x=" + destination.getBlockX()
                       + " y=" + destination.getBlockY() + " z=" + destination.getBlockZ() + ".");
            } else player.sendMessage("Teleport to " + mountain.name + " was cancelled.");
         });
      });
      return true;
   }

   private static Location massifLanding(World world, WildernessFeatures.MountainSite mountain, Location player) {
      double base = Math.atan2(player.getZ() - mountain.centerZ, player.getX() - mountain.centerX);
      for (double radius : new double[] {.58, .66, .74}) {
         for (int i = 0; i < 16; i++) {
            double angle = base + i * (Math.PI * 2.0 / 16.0);
            double x = mountain.centerX + Math.cos(angle) * mountain.radiusX * radius;
            double z = mountain.centerZ + Math.sin(angle) * mountain.radiusZ * radius;
            int bx = (int)Math.floor(x), bz = (int)Math.floor(z);
            int groundY = world.getHighestBlockYAt(bx, bz);
            if (groundY < world.getMinHeight() || groundY + 2 >= world.getMaxHeight()) continue;
            Block ground = world.getBlockAt(bx, groundY, bz);
            Block feet = world.getBlockAt(bx, groundY + 1, bz), head = world.getBlockAt(bx, groundY + 2, bz);
            if (!ground.getType().isSolid() || ground.isLiquid() || !feet.isPassable() || feet.isLiquid()
                    || !head.isPassable() || head.isLiquid()) continue;
            return new Location(world, bx + .5, groundY + 1.0, bz + .5, player.getYaw(), player.getPitch());
         }
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
