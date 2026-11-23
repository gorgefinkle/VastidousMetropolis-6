package ch.k42.metropolis.commands;

import ch.k42.metropolis.generator.MetropolisGenerator;
import ch.k42.metropolis.grid.urbanGrid.forest.ForestGenerator;
import ch.k42.metropolis.grid.urbanGrid.vanilla.VanillaWilderness;
import ch.k42.metropolis.minions.Minions;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

/**
 * Recovery of the R30ea windfarm search and verification command. This command
 * is diagnostic: it searches planned sites, checks loaded tower structures,
 * and optionally teleports to a successfully verified location.
 *
 * Reconstructed from the published R30ea bytecode; not an independently built
 * server release. Do not conflate these diagnostic chunk loads with durable
 * save acknowledgements.
 */
public final class CommandMetropolisWindFarm implements CommandExecutor {
    private static final Set<UUID> ACTIVE = new HashSet<>();
    private static final int MAX_REJECTED_CANDIDATES = 18;

    public CommandMetropolisWindFarm() { }

    @Override public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage("Use this command in a Metropolis world as a player.");
            return true;
        }
        if (!player.hasPermission("metropolis.command")) {
            player.sendMessage("You do not have permission.");
            return true;
        }
        MetropolisGenerator generator = Minions.getGeneratorForPlayer(player);
        if (generator == null) {
            player.sendMessage("This is not a Metropolis world.");
            return true;
        }
        if (args.length > 0 && args[0].equalsIgnoreCase("verify")) {
            verify(player, generator, args);
            return true;
        }
        boolean teleport = args.length > 0 && args[0].equalsIgnoreCase("tp");
        int offset = teleport ? 1 : 0;
        int radius = 128, minimum = 1;
        if (args.length > offset) {
            try { radius = Integer.parseInt(args[offset]); }
            catch (NumberFormatException e) { usage(player); return true; }
        }
        radius = Math.max(16, Math.min(256, radius));
        if (args.length > offset + 1) {
            try { minimum = Integer.parseInt(args[offset + 1]); }
            catch (NumberFormatException e) { usage(player); return true; }
        }
        if (minimum < 1 || minimum > 81) {
            player.sendMessage("minimumTurbines must be from 1 to 81.");
            return true;
        }
        if (VanillaWilderness.isActive(generator.getWorld())) {
            player.sendMessage("Windfarms cannot be painted while generator.wilderness.vanilla.enabled is true.");
            return true;
        }
        Location at = player.getLocation();
        if (!teleport) {
            int[] found = ForestGenerator.nearestWindFarm(generator, at.getBlockX(), at.getBlockZ(), radius * 16, minimum);
            if (found == null)
                player.sendMessage("No terrain-valid planned windfarm within " + radius + " chunks.");
            else
                player.sendMessage("Nearest planned windfarm at " + found[0] + ", " + found[1]
                    + " (" + found[2] + " turbines); core tower at " + found[3] + ", " + found[5] + ", " + found[4] + ".");
            return true;
        }
        if (!ACTIVE.add(player.getUniqueId())) {
            player.sendMessage("Your previous windfarm verification is still running.");
            return true;
        }
        player.sendMessage("Finding a built windfarm within " + radius + " chunks (minimum " + minimum
                + " turbines). Tower population will be checked after chunk generation finishes.");
        new Search(player, generator, at.getBlockX(), at.getBlockZ(), radius * 16, minimum).nextCandidate();
        return true;
    }

    private static void usage(Player player) {
        player.sendMessage("Usage: /windfarm [tp] [radiusChunks 16..256] [minimumTurbines 1..81]");
        player.sendMessage("       /windfarm verify <towerX> <plannedGroundY> <towerZ>");
    }
    private static void verify(Player player, MetropolisGenerator generator, String[] args) {
        if (args.length != 4) { usage(player); return; }
        int x, y, z;
        try {
            x = Integer.parseInt(args[1]); y = Integer.parseInt(args[2]); z = Integer.parseInt(args[3]);
        } catch (NumberFormatException e) { usage(player); return; }
        World world = player.getWorld();
        loadFoundationChunks(world, x, z);
        Bukkit.getScheduler().runTaskLater(generator.getPlugin(), () -> verifyKnown(player, generator, world, x, y, z, 0), 20L);
    }
    private static void verifyKnown(Player player, MetropolisGenerator generator, World world, int x, int plannedY, int z, int tries) {
        if (!player.isOnline() || player.getWorld() != world || !generator.getPlugin().isEnabled()) return;
        WindfarmStructureProbe.Result result = WindfarmStructureProbe.inspect(world, x, plannedY, z);
        if (result.found) {
            player.sendMessage("[WIND-CHECK] BUILT tower at " + x + ", " + (result.ringY - 1) + ", " + z
                + " (" + result.ringArms + " ring arms, yScan=" + result.ringY + ").");
            Minions.i("[WIND-CHECK] VERIFIED KNOWN tower=(%d,%d,%d) predictedGround=%d", x, result.ringY - 1, z, plannedY);
            return;
        }
        if (tries < 5) {
            Bukkit.getScheduler().runTaskLater(generator.getPlugin(), () -> verifyKnown(player, generator, world, x, plannedY, z, tries + 1), 20L);
            return;
        }
        player.sendMessage("[WIND-CHECK] No matching concrete tower structure visible Y=" + (plannedY - 32) + ".." + (plannedY + 33)
            + " near " + x + "," + z + "; closest pattern at Y=" + result.ringY + ", ring=" + result.ringArms
            + ", shaft=" + result.shaftArms + ", curb=" + result.curbArms + ". A later population may still change this.");
        Minions.w("[WIND-CHECK] KNOWN UNVERIFIED tower=(%d,%d,%d) ring=%d shaft=%d curb=%d", x, plannedY, z,
            result.ringArms, result.shaftArms, result.curbArms);
    }
    private static void loadFoundationChunks(World world, int x, int z) {
        for (int cx = (x - 5) >> 4; cx <= (x + 5) >> 4; cx++)
            for (int cz = (z - 5) >> 4; cz <= (z + 5) >> 4; cz++)
                world.getChunkAt(cx, cz);
    }
    private static long originKey(int x, int z) { return ((long)x << 32) ^ (z & 0xffffffffL); }

    private static final class Search {
        private final Player player;
        private final MetropolisGenerator generator;
        private final World world;
        private final int fromX, fromZ, range, minimum;
        private final Set<Long> examined = new HashSet<>();
        private int oldUnverified, newUnverified;
        private int[] lastUnverified;
        Search(Player player, MetropolisGenerator generator, int fromX, int fromZ, int range, int minimum) {
            this.player = player; this.generator = generator; this.world = player.getWorld();
            this.fromX = fromX; this.fromZ = fromZ; this.range = range; this.minimum = minimum;
        }
        private boolean active() { return player.isOnline() && player.getWorld().equals(world) && generator.getPlugin().isEnabled(); }
        private void close() { ACTIVE.remove(player.getUniqueId()); }
        void nextCandidate() {
            if (!active()) { close(); return; }
            if (examined.size() >= MAX_REJECTED_CANDIDATES) { exhausted(true); return; }
            int[] candidate;
            try {
                candidate = ForestGenerator.nearestWindFarm(generator, fromX, fromZ, range, minimum,
                    c -> !examined.contains(originKey(c[0], c[1])));
            } catch (Exception ex) {
                close(); player.sendMessage("Windfarm planning lookup failed: " + ex.getMessage());
                Minions.w("[WIND-CHECK] lookup failure: %s", ex.toString()); return;
            }
            if (candidate == null) { exhausted(false); return; }
            examined.add(originKey(candidate[0], candidate[1]));
            boolean generated = world.isChunkGenerated(candidate[3] >> 4, candidate[4] >> 4);
            try { loadFoundationChunks(world, candidate[3], candidate[4]); }
            catch (RuntimeException ex) {
                close(); player.sendMessage("Could not load windfarm tower chunks: " + ex.getMessage());
                Minions.w("[WIND-CHECK] chunk loading failed: %s", ex.toString()); return;
            }
            Minions.i("[WIND-CHECK] candidate origin=(%d,%d) tower=(%d,%d,%d) turbines=%d generatedBefore=%s",
                candidate[0], candidate[1], candidate[3], candidate[5], candidate[4], candidate[2], generated);
            Bukkit.getScheduler().runTaskLater(generator.getPlugin(), () -> inspect(candidate, generated, 0), 10L);
        }
        private void inspect(int[] candidate, boolean generated, int tries) {
            if (!active()) { close(); return; }
            WindfarmStructureProbe.Result result = WindfarmStructureProbe.inspect(world, candidate[3], candidate[5], candidate[4]);
            if (result.found) { verified(candidate, result); return; }
            int limit = generated ? 4 : 12;
            if (tries + 1 < limit) {
                Bukkit.getScheduler().runTaskLater(generator.getPlugin(), () -> inspect(candidate, generated, tries + 1), 10L);
                return;
            }
            if (generated) oldUnverified++; else newUnverified++;
            lastUnverified = candidate;
            Minions.w("[WIND-CHECK] UNVERIFIED origin=(%d,%d) tower=(%d,%d,%d) oldChunk=%s trials=%d closestRingY=%d ring=%d shaft=%d curb=%d",
                candidate[0], candidate[1], candidate[3], candidate[5], candidate[4], generated, limit,
                result.ringY, result.ringArms, result.shaftArms, result.curbArms);
            nextCandidate();
        }
        private void verified(int[] candidate, WindfarmStructureProbe.Result result) {
            close();
            int x = candidate[6], z = candidate[7];
            int height = world.getHighestBlockYAt(x, z);
            int y = height >= result.ringY - 6 && height <= result.ringY + 9 ? height + 2 : result.ringY + 2;
            boolean ok = player.teleport(new Location(world, x + 0.5, y, z + 0.5));
            if (ok) {
                player.sendMessage("[WIND-CHECK] BUILT windfarm with " + candidate[2] + " turbines. Core tower at " + candidate[3]
                        + ", " + (result.ringY - 1) + ", " + candidate[4] + ". Teleported to its service spur.");
                if (oldUnverified + newUnverified > 0)
                    player.sendMessage("Other planned sites not verified: " + oldUnverified + " old, " + newUnverified + " newly generated.");
            } else {
                player.sendMessage("Turbine VERIFIED but teleport failed; tower at " + candidate[3]
                    + ", " + (result.ringY - 1) + ", " + candidate[4] + ".");
            }
            Minions.i("[WIND-CHECK] VERIFIED origin=(%d,%d) tower=(%d,%d,%d) turbines=%d",
                candidate[0], candidate[1], candidate[3], result.ringY - 1, candidate[4], candidate[2]);
        }
        private void exhausted(boolean capped) {
            close();
            if (lastUnverified == null) {
                player.sendMessage("No matching planned windfarm found in the requested search radius.");
                return;
            }
            player.sendMessage("No windfarm verified after population checks. " + (oldUnverified + newUnverified) + " planned sites remained unverified ("
                + oldUnverified + " existing, " + newUnverified + " newly generated)."
                + (capped ? " Search capped at 18 candidates." : ""));
            player.sendMessage("Last site tower " + lastUnverified[3] + ", " + lastUnverified[5] + ", " + lastUnverified[4]
                + ". Use /windfarm verify " + lastUnverified[3] + " " + lastUnverified[5] + " " + lastUnverified[4] + " to inspect it later.");
            Minions.w("[WIND-CHECK] search completed without verified structure: existing=%d generated=%d candidates=%d",
                oldUnverified, newUnverified, examined.size());
        }
    }
}
