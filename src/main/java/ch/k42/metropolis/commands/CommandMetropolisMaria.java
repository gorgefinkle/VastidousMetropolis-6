package ch.k42.metropolis.commands;

import ch.k42.metropolis.generator.MetropolisGenerator;
import ch.k42.metropolis.plugin.MetropolisPlugin;
import org.bukkit.Bukkit;
import org.bukkit.World;
import org.bukkit.WorldCreator;
import org.bukkit.World.Environment;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

public class CommandMetropolisMaria implements CommandExecutor {
   private final MetropolisPlugin plugin;
   public static final String DEFAULT_WORLD_NAME = "Metropolis";

   public CommandMetropolisMaria(MetropolisPlugin plugin) {
      this.plugin = plugin;
   }

   public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
      if (sender instanceof Player) {
         Player player = (Player)sender;
         World world = Bukkit.getServer().getWorld("Metropolis");
         if (world == null && player.hasPermission("metropolis.create")) {
            sender.sendMessage("Maria is working hard... This will take a moment...");
            String worldname = "Metropolis";
            if (args.length > 0 && !args[0].isEmpty()) {
               worldname = args[0];
            }

            if (args.length > 1) {
               if (args[1].isEmpty() || !args[1].equals("NORMAL") && !args[1].equals("NETHER") && !args[1].equals("THE_END")) {
                  world = this.getDefaultMetropolis(worldname, Environment.NORMAL);
               } else {
                  world = this.getDefaultMetropolis(worldname, Environment.valueOf(args[1]));
               }
            } else {
               world = this.getDefaultMetropolis(worldname, Environment.NORMAL);
            }
         } else if (!player.hasPermission("metropolis.create")) {
            sender.sendMessage("Maria has detected that you do not have permissions for this command.");
         }

         if (world == null) {
            sender.sendMessage("Sorry for the inconvenience, but we seem to be unable to travel to Metropolis");
            return false;
         }

         if (!(world.getGenerator() instanceof MetropolisGenerator)) {
            sender.sendMessage("WARNING: The world called Metropolis was NOT shaped by Maria!");
         }

         if (player.getLocation().getWorld() == world) {
            sender.sendMessage("You are in Metropolis. Welcome!");
            return true;
         } else {
            player.sendMessage("Traveling to Metropolis, please stay seated until arrival.");
            player.teleport(world.getSpawnLocation());
            return true;
         }
      } else {
         String worldname = "Metropolis";
         if (args.length > 0 && !args[0].isEmpty()) {
            worldname = args[0];
         }

         if (args.length > 1) {
            if (args[1].isEmpty() || !args[1].equals("NORMAL") && !args[1].equals("NETHER") && !args[1].equals("THE_END")) {
               World world = this.getDefaultMetropolis(worldname, Environment.NORMAL);
            } else {
               World world = this.getDefaultMetropolis(worldname, Environment.valueOf(args[1]));
            }
         } else {
            World var9 = this.getDefaultMetropolis(worldname, Environment.NORMAL);
         }

         return false;
      }
   }

   public World getDefaultMetropolis(String worldname, Environment env) {
      Bukkit.getLogger().info("---- looking for Metropolis");
      World metropolis = Bukkit.getServer().getWorld(worldname);
      if (metropolis != null) {
         Bukkit.getLogger().info("---- Metropolis already built");
      } else {
         WorldCreator worldCreator = new WorldCreator(worldname);
         worldCreator.environment(env);
         // Attach the generator HERE. It used to be created only AFTER the world
         // (and thrown away), which left the new world vanilla unless bukkit.yml
         // happened to map this world's name to the Metropolis generator.
         worldCreator.generator(this.plugin.getDefaultWorldGenerator(worldname, ""));
         metropolis = Bukkit.getServer().createWorld(worldCreator);
      }

      return metropolis;
   }
}
