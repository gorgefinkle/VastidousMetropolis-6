package ch.k42.metropolis.commands;

import ch.k42.metropolis.generator.MetropolisGenerator;
import ch.k42.metropolis.grid.common.Grid;
import ch.k42.metropolis.grid.common.GridProvider;
import ch.k42.metropolis.grid.urbanGrid.UrbanGrid;
import ch.k42.metropolis.grid.urbanGrid.ocean.OceanConfig;
import ch.k42.metropolis.grid.urbanGrid.ocean.OceanPortPlanner;
import ch.k42.metropolis.grid.urbanGrid.ocean.OceanProvider;
import ch.k42.metropolis.grid.urbanGrid.parcel.OceanPortParcel;
import ch.k42.metropolis.minions.Minions;
import ch.k42.metropolis.plugin.MetropolisPlugin;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.TabCompleter;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

/**
 * /port [type] [count] [tp] -- find the first N ports of the requested type.
 * /port tp [type] [count] -- also teleport to the closest of those found.
 * /port types -- list supported port types.
 * /port [type] [count] debug -- report WHY examined districts have no matching port.
 * Default: stop as soon as TWO MATCHING PORTS have been found.
 * The optional count (1..MAX_PORTS) is a match target, NOT a district budget.
 * If fewer than N matching ports exist, all eligible ocean districts are inspected.
 *
 * A port is only selected when its 64x64-chunk city tile has been planned.
 * Inspect one tile per server tick; do not load terrain chunks to search.
 */
public final class CommandMetropolisPort implements CommandExecutor, TabCompleter {
    private static final int TILE_CHUNKS = 64;
    private static final int MAX_PORTS = 64; // maximum requested matching results
    private static final int DEFAULT_PORTS = 2;
    private final MetropolisPlugin plugin;

    private static final String USAGE = "Usage: /port [residential|commercial|industrial] [ports 1-64] [tp] [debug] (any order). /port types lists options.";

    private static final class Query {
        final OceanPortParcel.Kind kind; // null means any port type
        final String label;
        final boolean teleport;
        final boolean debug;
        final int count;
        Query(OceanPortParcel.Kind kind, boolean teleport, int count, boolean debug) {
            this.kind = kind;
            this.label = label(kind);
            this.teleport = teleport;
            this.debug = debug;
            this.count = count;
        }
    }

    private static Query parseQuery(String[] args) {
        OceanPortParcel.Kind kind = null;
        boolean teleport = false;
        boolean debug = false;
        boolean sawType = false;
        boolean sawCount = false;
        int count = DEFAULT_PORTS;
        if (args.length > 4) return null;
        for (String token : args) {
            String key = token.toLowerCase(Locale.ROOT);
            if ("debug".equals(key)) {
                if (debug) return null;
                debug = true;
                continue;
            }
            if ("tp".equals(key)) {
                if (teleport) return null;
                teleport = true;
                continue;
            }
            if (key.matches("[0-9]+")) {
                if (sawCount) return null;
                sawCount = true;
                try {
                    count = Integer.parseInt(key);
                } catch (NumberFormatException e) {
                    return null;
                }
                if (count < 1 || count > MAX_PORTS) return null;
                continue;
            }
            if (sawType) return null;
            sawType = true;
            if ("any".equals(key) || "all".equals(key)) {
                kind = null;
            } else if ("residential".equals(key) || "res".equals(key) || "neighborhood".equals(key)) {
                kind = OceanPortParcel.Kind.RESIDENTIAL_LANDING;
            } else if ("commercial".equals(key) || "trading".equals(key) || "merchant".equals(key)) {
                kind = OceanPortParcel.Kind.COMMERCIAL_QUAY;
            } else if ("industrial".equals(key) || "freight".equals(key) || "cargo".equals(key)) {
                kind = OceanPortParcel.Kind.INDUSTRIAL_WHARF;
            } else {
                return null;
            }
        }
        return new Query(kind, teleport, count, debug);
    }

    private static String label(OceanPortParcel.Kind kind) {
        if (kind == OceanPortParcel.Kind.RESIDENTIAL_LANDING) return "residential";
        if (kind == OceanPortParcel.Kind.COMMERCIAL_QUAY) return "commercial";
        if (kind == OceanPortParcel.Kind.INDUSTRIAL_WHARF) return "industrial";
        return "any";
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (!(sender instanceof Player) || !((Player) sender).hasPermission("metropolis.command")
                || args.length < 1 || args.length > 4) return Collections.emptyList();
        String[] prior = new String[args.length - 1];
        System.arraycopy(args, 0, prior, 0, prior.length);
        if (parseQuery(prior) == null) return Collections.emptyList();
        boolean hasTp = false, hasType = false, hasCount = false, hasDebug = false;
        for (String part : prior) {
            String key = part.toLowerCase(Locale.ROOT);
            if ("tp".equals(key)) hasTp = true;
            else if ("debug".equals(key)) hasDebug = true;
            else if (key.matches("[0-9]+")) hasCount = true;
            else hasType = true;
        }
        List<String> choices = new ArrayList<String>();
        if (args.length == 1) choices.add("types");
        if (!hasTp) choices.add("tp");
        if (!hasDebug) choices.add("debug");
        if (!hasType) {
            choices.add("residential");
            choices.add("commercial");
            choices.add("industrial");
        }
        if (!hasCount) {
            choices.add("2"); choices.add("5"); choices.add("10");
        }
        String prefix = args[args.length - 1].toLowerCase(Locale.ROOT);
        List<String> matches = new ArrayList<String>();
        for (String choice : choices) if (choice.startsWith(prefix)) matches.add(choice);
        return matches;
    }

    public CommandMetropolisPort(MetropolisPlugin plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!(sender instanceof Player)) {
            sender.sendMessage("This command is only usable by a player.");
            return true;
        }
        final Player player = (Player) sender;
        if (!player.hasPermission("metropolis.command")) {
            player.sendMessage("You do not have permission to use this command.");
            return true;
        }
        if (args.length == 1 && args[0].equalsIgnoreCase("types")) {
            player.sendMessage("[Metropolis Ports] Types: residential (res, neighborhood), commercial (merchant, trading), industrial (freight, cargo).");
            player.sendMessage("Use /port [type] [count] [tp] [debug]; default finds 2 matching ports, e.g. /port industrial 5 debug shows rejections while searching.");
            return true;
        }
        final Query query = parseQuery(args);
        if (query == null) {
            player.sendMessage(USAGE);
            return true;
        }
        final MetropolisGenerator generator = Minions.getGeneratorForPlayer(player);
        if (generator == null || generator.getWorldSeed() == null || generator.getGridProvider() == null) {
            player.sendMessage("This is not an initialized Metropolis world.");
            return true;
        }
        if (!OceanConfig.isOceanEnabled() || !OceanConfig.isOceanPortsEnabled()) {
            player.sendMessage("Ports are disabled; enable generator.ocean.ports.enabled and generate a fresh world area.");
            return true;
        }
        final OceanProvider ocean = new OceanProvider(generator.getWorldSeed());
        final Location start = player.getLocation();
        final List<Tile> tiles = oceanTiles(ocean, start.getBlockX(), start.getBlockZ());
        if (tiles.isEmpty()) {
            player.sendMessage("No ocean tiles are eligible for port planning in this world.");
            return true;
        }
        player.sendMessage("[Metropolis Ports] Searching for " + query.count + " " + query.label
                + " port(s) across " + tiles.size() + " eligible ocean district(s)."
                + " Stops as soon as " + query.count + " matching port(s) are found.");
        new Search(player, generator, start.getWorld(), start.getBlockX(), start.getBlockZ(),
                query, tiles).scheduleStep();
        return true;
    }

    /** Exact tile ownership check, using the same envelope as OceanProvider.tileReached(). */
    private static List<Tile> oceanTiles(OceanProvider ocean, int x, int z) {
        double cx = ocean.getCenterChunkX() + 0.5;
        double cz = ocean.getCenterChunkZ() + 0.5;
        double rx = ocean.getEnvelopeRadiusChunksX();
        double rz = ocean.getEnvelopeRadiusChunksZ();
        int minX = Math.floorDiv((int) Math.floor(cx - rx), TILE_CHUNKS) * TILE_CHUNKS;
        int maxX = Math.floorDiv((int) Math.ceil(cx + rx), TILE_CHUNKS) * TILE_CHUNKS;
        int minZ = Math.floorDiv((int) Math.floor(cz - rz), TILE_CHUNKS) * TILE_CHUNKS;
        int maxZ = Math.floorDiv((int) Math.ceil(cz + rz), TILE_CHUNKS) * TILE_CHUNKS;
        List<Tile> tiles = new ArrayList<Tile>();
        for (int tx = minX; tx <= maxX; tx += TILE_CHUNKS) {
            for (int tz = minZ; tz <= maxZ; tz += TILE_CHUNKS) {
                if (ocean.tileReached(tx, tz)) {
                    tiles.add(new Tile(tx, tz, minDistanceSquared(x, z, tx, tz)));
                }
            }
        }
        Collections.sort(tiles, new Comparator<Tile>() {
            @Override public int compare(Tile a, Tile b) {
                int distance = Long.compare(a.minimumDistanceSquared, b.minimumDistanceSquared);
                if (distance != 0) return distance;
                if (a.chunkX != b.chunkX) return Integer.compare(a.chunkX, b.chunkX);
                return Integer.compare(a.chunkZ, b.chunkZ);
            }
        });
        return tiles;
    }

    /** Lower bound on distance to any port anchor within the tile. */
    private static long minDistanceSquared(int x, int z, int rootX, int rootZ) {
        long minX = (long) rootX * 16L, maxX = minX + TILE_CHUNKS * 16L - 1L;
        long minZ = (long) rootZ * 16L, maxZ = minZ + TILE_CHUNKS * 16L - 1L;
        long dx = x < minX ? minX - x : (x > maxX ? x - maxX : 0);
        long dz = z < minZ ? minZ - z : (z > maxZ ? z - maxZ : 0);
        return dx * dx + dz * dz;
    }

    private static final class Tile {
        final int chunkX, chunkZ;
        final long minimumDistanceSquared;
        Tile(int x, int z, long d2) { chunkX = x; chunkZ = z; minimumDistanceSquared = d2; }
    }

    private static final class Site {
        final int x, z;
        final OceanPortParcel.Kind kind;
        final long distanceSquared;
        Site(int x, int z, OceanPortParcel.Kind kind, long distanceSquared) {
            this.x = x;
            this.z = z;
            this.kind = kind;
            this.distanceSquared = distanceSquared;
        }
    }

    private final class Search {
        final Player player;
        final MetropolisGenerator generator;
        final World world;
        final int x, z;
        final Query query;
        final List<Tile> tiles;
        final int target;
        final List<Site> found = new ArrayList<Site>();
        int next;
        int searched;
        int debugLines;

        Search(Player player, MetropolisGenerator generator, World world, int x, int z,
               Query query, List<Tile> tiles) {
            this.player = player;
            this.generator = generator;
            this.world = world;
            this.x = x;
            this.z = z;
            this.query = query;
            this.tiles = tiles;
            this.target = query.count;
        }

        void scheduleStep() {
            Bukkit.getScheduler().runTask(plugin, new Runnable() {
                @Override public void run() { step(); }
            });
        }

        void step() {
            if (!player.isOnline() || player.getWorld() != world) return;
            if (found.size() >= target || next >= tiles.size()) {
                finish();
                return;
            }
            Tile tile = tiles.get(next++);
            searched++;
            try {
                Grid candidate = generator.getGridProvider().getGrid(tile.chunkX, tile.chunkZ);
                if (candidate instanceof UrbanGrid) {
                    UrbanGrid grid = (UrbanGrid) candidate;
                    // The planner reserves at most one port per tile.
                    outer:
                    for (int cz = tile.chunkZ + 1; cz <= tile.chunkZ + 62; cz++) {
                        for (int cx = tile.chunkX + 1; cx <= tile.chunkX + 62; cx++) {
                            Object parcel = grid.peekParcel(cx, cz);
                            if (parcel instanceof OceanPortParcel) {
                                OceanPortParcel port = (OceanPortParcel) parcel;
                                if (query.kind != null && port.kind() != query.kind) continue;
                                int px = (cx << 4) + 8, pz = (cz << 4) + 8;
                                long dx = (long) px - x, dz = (long) pz - z;
                                found.add(new Site(px, pz, port.kind(), dx * dx + dz * dz));
                                break outer;
                            }
                        }
                    }
                }
                if (query.debug) {
                    String diagnosis = OceanPortPlanner.getDiagnostic(generator.worldName,
                            tile.chunkX, tile.chunkZ, query.kind);
                    if (debugLines < 20) {
                        player.sendMessage("[Port Debug] tile=(" + tile.chunkX + "," + tile.chunkZ + "): "
                                + (diagnosis == null ? "no cached planning report (tile may have been planned before this jar loaded)" : diagnosis));
                        debugLines++;
                        if (debugLines == 20) player.sendMessage("[Port Debug] Showing first 20 tile summaries only; check server console for the rest.");
                    }
                }
            } catch (RuntimeException e) {
                player.sendMessage("[Metropolis Ports] Could not inspect ocean district " + tile.chunkX
                        + "," + tile.chunkZ + ": " + e.getMessage());
                Minions.w("[Ports] /port failed inspecting tile (%d,%d): %s",
                        tile.chunkX, tile.chunkZ, e.toString());
                return;
            }
            // Count successful ports, never inspected districts. Stop immediately at N matches.
            if (found.size() >= target || next >= tiles.size()) {
                finish();
            } else {
                if (searched % 10 == 0) {
                    player.sendMessage("[Metropolis Ports] Surveyed " + searched + "/" + tiles.size()
                            + " districts; found " + found.size() + "/" + target + " " + query.label
                            + " port(s). Continuing until " + target + " matches or ocean exhausted.");
                }
                scheduleStep();
            }
        }

        void finish() {
            if (found.isEmpty()) {
                player.sendMessage("[Metropolis Ports] No " + (query.kind == null ? "valid" : query.label)
                        + " port found after checking all " + searched + " eligible ocean districts."
                        + " See server logs for site-rejection reasons.");
                return;
            }
            Collections.sort(found, new Comparator<Site>() {
                @Override public int compare(Site a, Site b) {
                    int cmp = Long.compare(a.distanceSquared, b.distanceSquared);
                    if (cmp != 0) return cmp;
                    if (a.x != b.x) return Integer.compare(a.x, b.x);
                    return Integer.compare(a.z, b.z);
                }
            });
            player.sendMessage("[Metropolis Ports] Found " + found.size() + "/" + target + " " + query.label
                    + " port(s) after surveying " + searched + " district(s). "
                    + (found.size() >= target ? "Stopped because the requested number of ports was found."
                            : "All eligible ocean districts checked; fewer matching ports exist."));
            for (int i = 0; i < found.size(); i++) {
                Site site = found.get(i);
                int distance = (int) Math.round(Math.sqrt(site.distanceSquared));
                player.sendMessage("[Metropolis Ports] #" + (i + 1) + " " + label(site.kind)
                        + ": X=" + site.x + " Z=" + site.z + " (" + distance
                        + " blocks away); /tp " + site.x + " 70 " + site.z);
            }
            if (query.teleport) {
                // Teleport to the closest of the found matches; load only its target chunk.
                Site destination = found.get(0);
                int y = Math.max(65, world.getHighestBlockYAt(destination.x, destination.z) + 2);
                player.teleport(new Location(world, destination.x + 0.5, y, destination.z + 0.5));
            } else {
                player.sendMessage("Use /port tp" + (query.kind == null ? "" : " " + query.label)
                        + " to teleport to the nearest listed port. Existing chunks do not rebuild automatically.");
            }
        }
    }
}
