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
import ch.k42.metropolis.grid.urbanGrid.parcel.Parcel;

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
      if (!PluginConfig.isCityRingsEnabled() || !PluginConfig.isForestGenerationEnabled()) {
         player.sendMessage("Named lakes require generator.cityRings.enabled and generator.forest.generate. "
                 + "Their defaults are both true.");
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
               player.sendMessage("No eligible lake could be planned. Check the city-ring and forest-generation settings.");
               return;
            }
            int centerX = (int)Math.round(lake.centerX), centerZ = (int)Math.round(lake.centerZ);
            long dx = (long)centerX - originX, dz = (long)centerZ - originZ;
            long distance = Math.round(Math.sqrt((double)dx * dx + (double)dz * dz));
            player.sendMessage(lake.name + " is centered at x=" + centerX + " z=" + centerZ
                    + " (" + distance + " blocks away); waterline y=" + lake.waterY + ".");
            if (lake.stream == null) {
               player.sendMessage("This is a closed lake basin; no legal outlet stream was available in the current river layout.");
            }
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

   private static Location shorelineLanding(World world,WildernessFeatures.LakeSite lake,Location player){
      double dx=player.getX()-lake.centerX,dz=player.getZ()-lake.centerZ;
      double baseAngle=Math.atan2(dz,dx);
      // Find the actual noise-shaped shore instead of guessing an ellipse radius.
      for(int step=0;step<24;step++){
         int signed=step==0?0:((step+1)/2)*(step%2==1?1:-1);
         double angle=baseAngle+Math.toRadians(signed*15.0),ux=Math.cos(angle),uz=Math.sin(angle);
         double maxRadius=Math.max(lake.radiusX,lake.radiusZ)*1.20+WildernessFeatures.LakeSite.SHORE_WIDTH_BLOCKS;
         for(double radius=8.0;radius<=maxRadius;radius+=8.0){
            double x=lake.centerX+ux*radius,z=lake.centerZ+uz*radius;
            double d=lake.distanceInsideShoreBlock(x,z);
            if(d>-10.0||d<-WildernessFeatures.LakeSite.SHORE_WIDTH_BLOCKS+8.0)continue;
            int bx=(int)Math.floor(x),bz=(int)Math.floor(z),groundY=world.getHighestBlockYAt(bx,bz);
            while(groundY>world.getMinHeight()&&Parcel.isTreeBlock(world.getBlockAt(bx,groundY,bz).getType()))groundY--;
            if(groundY<world.getMinHeight()||groundY+2>=world.getMaxHeight())continue;
            Block ground=world.getBlockAt(bx,groundY,bz);
            Block feet=world.getBlockAt(bx,groundY+1,bz),head=world.getBlockAt(bx,groundY+2,bz);
            if(!ground.getType().isSolid()||ground.isLiquid()||Parcel.isTreeBlock(ground.getType())
                    ||!feet.isPassable()||feet.isLiquid()||Parcel.isTreeBlock(feet.getType())
                    ||!head.isPassable()||head.isLiquid()||Parcel.isTreeBlock(head.getType()))continue;
            return new Location(world,bx+0.5,groundY+1.0,bz+0.5,player.getYaw(),player.getPitch());
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
