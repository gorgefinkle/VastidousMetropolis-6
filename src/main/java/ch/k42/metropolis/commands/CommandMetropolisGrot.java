package ch.k42.metropolis.commands;

import ch.k42.metropolis.plugin.MetropolisPlugin;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

public class CommandMetropolisGrot implements CommandExecutor {
   private final MetropolisPlugin plugin;

   public CommandMetropolisGrot(MetropolisPlugin plugin) {
      this.plugin = plugin;
   }

   public boolean onCommand(CommandSender sender, Command command, String label, String[] split) {
      if (sender instanceof Player) {
         Player player = (Player)sender;
         if (player.hasPermission("metropolis.command")) {
            World metropolis = Bukkit.getServer().getWorld("Metropolis");
            if (player.getWorld() == metropolis) {
               Location location = player.getLocation();
               int chunkX = location.getBlockX() >> 4;
               int chunkZ = location.getBlockZ() >> 4;
               return true;
            } else {
               return false;
            }
         } else {
            sender.sendMessage("You do not have permission to use this command");
            return false;
         }
      } else {
         sender.sendMessage("This command is only usable by a player.");
         return false;
      }
   }
}
