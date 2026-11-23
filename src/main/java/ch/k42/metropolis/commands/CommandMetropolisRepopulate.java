package ch.k42.metropolis.commands;

import ch.k42.metropolis.generator.MetropolisGenerator;
import ch.k42.metropolis.plugin.MetropolisPlugin;
import org.bukkit.Bukkit;
import org.bukkit.Chunk;
import org.bukkit.World;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

/**
 * /repopulate [radius] [world chunkX chunkZ]
 *
 * Repairs holes: it queues every already-generated chunk within `radius` chunks
 * (default 8, at most 64) for another Metropolis populate pass. A chunk whose
 * populate was lost -- most likely because the server was stopped while it sat
 * in the deferred queue -- is otherwise never populated again, since the server
 * fires the populate hook only once per chunk. The telltale is a street or
 * building that is bare flat stone. Chunks that only get painted by plain block
 * placement (ocean land, rivers, fallback terrain) are skipped. The queue is
 * worked through a few chunks per tick, so a big radius takes a while; watch
 * the "Populate stats" lines in the log.
 *
 * As a player it uses your own chunk; from the console give world, chunkX, chunkZ.
 */
public class CommandMetropolisRepopulate implements CommandExecutor {
   private final MetropolisPlugin plugin;

   public CommandMetropolisRepopulate(MetropolisPlugin plugin) {
      this.plugin = plugin;
   }

   @Override
   public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
      int radius = 8;
      if (args.length > 0) {
         try {
            radius = Math.max(1, Math.min(64, Integer.parseInt(args[0])));
         } catch (NumberFormatException e) {
            sender.sendMessage("Usage: /repopulate [radius in chunks, 1-64] [world chunkX chunkZ]");
            return true;
         }
      }

      World world;
      int cx;
      int cz;
      if (args.length >= 4) {
         world = Bukkit.getWorld(args[1]);
         if (world == null) {
            sender.sendMessage("There is no world called " + args[1] + ".");
            return true;
         }
         try {
            cx = Integer.parseInt(args[2]);
            cz = Integer.parseInt(args[3]);
         } catch (NumberFormatException e) {
            sender.sendMessage("Usage: /repopulate [radius] [world chunkX chunkZ]");
            return true;
         }
      } else if (sender instanceof Player) {
         Player player = (Player) sender;
         world = player.getWorld();
         Chunk chunk = player.getLocation().getChunk();
         cx = chunk.getX();
         cz = chunk.getZ();
      } else {
         sender.sendMessage("From the console: /repopulate <radius> <world> <chunkX> <chunkZ>");
         return true;
      }

      if (!(world.getGenerator() instanceof MetropolisGenerator)) {
         sender.sendMessage("World " + world.getName() + " is not a Metropolis world.");
         return true;
      }
      int queued = ((MetropolisGenerator) world.getGenerator()).requeueChunks(cx - radius, cz - radius, cx + radius, cz + radius);
      sender.sendMessage("Queued " + queued + " chunk(s) around [" + cx + "," + cz + "] in " + world.getName()
              + " for another populate pass. They are worked through a few per tick; progress shows in the server log.");
      return true;
   }
}
