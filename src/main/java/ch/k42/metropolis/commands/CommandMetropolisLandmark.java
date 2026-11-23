package ch.k42.metropolis.commands;

import ch.k42.metropolis.generator.MetropolisGenerator;
import ch.k42.metropolis.grid.urbanGrid.districts.LandmarkPlans;
import ch.k42.metropolis.grid.urbanGrid.layout.WorldLayout;
import ch.k42.metropolis.grid.urbanGrid.layout.WorldLayouts;
import ch.k42.metropolis.plugin.MetropolisPlugin;
import java.io.IOException;
import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.WeakHashMap;
import java.util.regex.Pattern;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;

/** Persistent index of built, accepted landmark footprints for /landmark. */
public final class CommandMetropolisLandmark implements CommandExecutor, TabCompleter {
    private static final String FILE = "metropolis-landmarks.tsv";
    private static final String VERSION = "METROPOLIS_LANDMARKS_V1";
    private static final int PAGE_SIZE = 10;
    private static final Pattern WHOLE = Pattern.compile("[0-9]+", Pattern.CASE_INSENSITIVE);
    private static final Map<MetropolisPlugin, CommandMetropolisLandmark> ACTIVE = new WeakHashMap<>();

    private final MetropolisPlugin plugin;
    private final Map<UUID, List<Site>> lastList = new HashMap<>();
    private final Map<UUID, World> lastWorld = new HashMap<>();
    private final Map<UUID, Map<String, Site>> known = new HashMap<>();

    public CommandMetropolisLandmark(MetropolisPlugin plugin) {
        this.plugin = plugin;
        ACTIVE.put(plugin, this);
    }

    private static String clean(String s) {
        return s == null ? "" : s.replace('\t', ' ').replace('\r', ' ').replace('\n', ' ').trim();
    }

    private static String label(LandmarkPlans.Choice choice) {
        String name = choice.clip.getConfig() == null ? null : choice.clip.getConfig().getBuildName();
        if (name == null || name.isBlank()) name = choice.clip.getGroupId();
        name = clean(name);
        if (name.toLowerCase(Locale.ROOT).contains("ikea") && !name.toLowerCase(Locale.ROOT).contains("minekea")) {
            return "Minekea (" + name + ")";
        }
        return name;
    }

    @SuppressWarnings("unchecked")
    private static List<Site> snapshot(WorldLayout layout, World world) throws ReflectiveOperationException {
        List<Site> sites = new ArrayList<>();
        Field byId = WorldLayout.class.getDeclaredField("byId");
        byId.setAccessible(true);
        Class<?> domainClass = Class.forName("ch.k42.metropolis.grid.urbanGrid.layout.WorldLayout$Domain");
        Field solved = domainClass.getDeclaredField("solved");
        Field landmarks = domainClass.getDeclaredField("landmarks");
        solved.setAccessible(true);
        landmarks.setAccessible(true);
        synchronized (layout) {
            Map<?, ?> domains = (Map<?, ?>) byId.get(layout);
            for (Object domain : domains.values()) {
                if (!solved.getBoolean(domain)) continue;
                Map<WorldLayout.Rect, WorldLayout.Landmark> reserved =
                    (Map<WorldLayout.Rect, WorldLayout.Landmark>) landmarks.get(domain);
                for (Map.Entry<WorldLayout.Rect, WorldLayout.Landmark> e : reserved.entrySet()) {
                    WorldLayout.Rect rect = e.getKey();
                    Object object = e.getValue().choice;
                    if (!(object instanceof LandmarkPlans.Choice)) continue;
                    LandmarkPlans.Choice choice = (LandmarkPlans.Choice) object;
                    if (choice.clip == null) continue;
                    sites.add(new Site(label(choice), clean(choice.clip.getGroupId()), rect.minX, rect.minZ,
                        rect.width(), rect.depth(), choice.direction.name()));
                }
            }
        }
        sites.removeIf(site -> !world.isChunkGenerated(site.minX, site.minZ));
        return sites;
    }

    private Map<String, Site> sitesFor(World world) {
        UUID id = world.getUID();
        Map<String, Site> entries = known.get(id);
        if (entries == null) {
            entries = load(world);
            known.put(id, entries);
        }
        return entries;
    }

    private static Map<String, Site> load(World world) {
        Map<String, Site> entries = new HashMap<>();
        Path file = world.getWorldFolder().toPath().resolve(FILE);
        if (!Files.isRegularFile(file)) return entries;
        try {
            List<String> lines = Files.readAllLines(file, StandardCharsets.UTF_8);
            String header = VERSION + "\t" + world.getSeed() + "\t" + world.getUID();
            if (lines.isEmpty() || !header.equals(lines.get(0))) return entries;
            for (int i = 1; i < lines.size(); i++) {
                String[] v = lines.get(i).split("\t", -1);
                if (v.length != 7) continue;
                try {
                    Site site = new Site(v[0], v[1], Integer.parseInt(v[2]), Integer.parseInt(v[3]),
                        Integer.parseInt(v[4]), Integer.parseInt(v[5]), v[6]);
                    if (site.width > 0 && site.depth > 0 && site.width <= 64 && site.depth <= 64) {
                        entries.put(site.key(), site);
                    }
                } catch (NumberFormatException ignored) { }
            }
        } catch (IOException ignored) { }
        return entries;
    }

    private static void save(World world, Map<String, Site> entries) throws IOException {
        Path file = world.getWorldFolder().toPath().resolve(FILE);
        List<Site> sites = new ArrayList<>(entries.values());
        sites.sort(Comparator.comparingInt((Site s) -> s.minX).thenComparingInt(s -> s.minZ));
        List<String> lines = new ArrayList<>(sites.size() + 1);
        lines.add(VERSION + "\t" + world.getSeed() + "\t" + world.getUID());
        for (Site s : sites) lines.add(clean(s.name) + "\t" + clean(s.group) + "\t" + s.minX + "\t" + s.minZ
            + "\t" + s.width + "\t" + s.depth + "\t" + clean(s.direction));
        Path temp = file.resolveSibling(file.getFileName() + ".tmp");
        Files.write(temp, lines, StandardCharsets.UTF_8);
        try {
            Files.move(temp, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (AtomicMoveNotSupportedException ignored) {
            Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private List<Site> collect(World world, boolean save) {
        Map<String, Site> entries = sitesFor(world);
        MetropolisGenerator generator = plugin.generatorForWorld(world);
        if (generator != null && generator.getWorldSeed() != null) {
            WorldLayout layout = WorldLayouts.forSeed(generator.getWorldSeed());
            if (layout != null) {
                try {
                    for (Site site : snapshot(layout, world)) entries.put(site.key(), site);
                } catch (ReflectiveOperationException e) {
                    plugin.getLogger().warning("Could not inspect accepted landmarks: " + e);
                }
            }
        }
        entries.values().removeIf(site -> !world.isChunkGenerated(site.minX, site.minZ));
        if (save) {
            try { save(world, entries); }
            catch (IOException e) { plugin.getLogger().warning("Cannot save landmarks for " + world.getName() + ": " + e); }
        }
        return new ArrayList<>(entries.values());
    }

    public static void saveOnShutdown(MetropolisPlugin plugin) {
        CommandMetropolisLandmark command = ACTIVE.remove(plugin);
        if (command == null) return;
        for (World world : Bukkit.getWorlds()) {
            try { if (plugin.generatorForWorld(world) != null) command.collect(world, true); }
            catch (RuntimeException e) { plugin.getLogger().warning("Landmark flush for " + world.getName() + ": " + e); }
        }
    }

    private static long distance2(Site site, int x, int z) {
        long dx = (long) site.centerX() - x, dz = (long) site.centerZ() - z;
        return dx * dx + dz * dz;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String alias, String[] args) {
        if (!(sender instanceof Player)) { sender.sendMessage("Use /landmark from inside a Metropolis world."); return true; }
        Player player = (Player) sender;
        if (!player.hasPermission("metropolis.command")) { player.sendMessage("No permission."); return true; }
        if (args.length == 0 || "help".equalsIgnoreCase(args[0])) { usage(player); return true; }
        World world = player.getWorld();
        if (plugin.generatorForWorld(world) == null) {
            player.sendMessage("This world does not use Metropolis generation."); return true;
        }
        if ("list".equalsIgnoreCase(args[0])) {
            if (args.length > 3) { usage(player); return true; }
            String filter = "all"; int page = 1;
            for (int i = 1; i < args.length; i++) {
                if (WHOLE.matcher(args[i]).matches()) {
                    try { page = Integer.parseInt(args[i]); }
                    catch (NumberFormatException e) { usage(player); return true; }
                } else if ("all".equals(filter)) filter = args[i];
                else { usage(player); return true; }
            }
            if (page < 1) { usage(player); return true; }
            List<Site> found = collect(world, true);
            String selected = filter;
            found.removeIf(site -> !site.matches(selected));
            Location current = player.getLocation();
            found.sort(Comparator.comparingLong((Site s) -> distance2(s, current.getBlockX(), current.getBlockZ()))
                .thenComparing(s -> s.name).thenComparingInt(s -> s.minX).thenComparingInt(s -> s.minZ));
            lastList.put(player.getUniqueId(), found);
            lastWorld.put(player.getUniqueId(), world);
            int pages = Math.max(1, (found.size() + PAGE_SIZE - 1) / PAGE_SIZE);
            if (page > pages) { player.sendMessage("Last page: " + pages); return true; }
            player.sendMessage("Landmarks: " + found.size() + " matches, page " + page + "/" + pages);
            if (found.isEmpty()) player.sendMessage("No matching sites in solved regions yet. Generate terrain, then try again.");
            for (int i = (page - 1) * PAGE_SIZE; i < Math.min(page * PAGE_SIZE, found.size()); i++) {
                Site s = found.get(i);
                player.sendMessage((i + 1) + ". " + s.name + " [" + s.width + "x" + s.depth + "] "
                    + s.centerX() + "," + s.centerZ() + " — /landmark tp " + (i + 1));
            }
            return true;
        }
        if ("tp".equalsIgnoreCase(args[0])) {
            if (args.length < 2 || args.length > 3) { usage(player); return true; }
            Site site;
            if (WHOLE.matcher(args[1]).matches()) {
                if (args.length != 2 || lastWorld.get(player.getUniqueId()) != world) {
                    player.sendMessage("Run /landmark list first to select a numbered site."); return true;
                }
                List<Site> previous = lastList.get(player.getUniqueId());
                int number;
                try { number = Integer.parseInt(args[1]); } catch (NumberFormatException e) { usage(player); return true; }
                if (previous == null || number < 1 || number > previous.size()) {
                    player.sendMessage("That landmark number is not on your last list."); return true;
                }
                site = previous.get(number - 1);
            } else {
                int ordinal = 1;
                try { if (args.length == 3) ordinal = Integer.parseInt(args[2]); }
                catch (NumberFormatException e) { usage(player); return true; }
                if (ordinal < 1) { usage(player); return true; }
                List<Site> found = collect(world, true);
                String filter = args[1];
                found.removeIf(s -> !s.matches(filter));
                Location current = player.getLocation();
                found.sort(Comparator.comparingLong(s -> distance2(s, current.getBlockX(), current.getBlockZ())));
                if (ordinal > found.size()) {
                    player.sendMessage("Only " + found.size() + " landmarks matched " + filter); return true;
                }
                site = found.get(ordinal - 1);
            }
            teleport(player, world, site);
            return true;
        }
        usage(player); return true;
    }

    private void teleport(Player player, World world, Site site) {
        if (!world.isChunkGenerated(site.minX, site.minZ)) {
            player.sendMessage("Landmark anchor chunk is no longer generated. Run /landmark list again."); return;
        }
        MetropolisGenerator generator = plugin.generatorForWorld(world);
        if (generator == null || generator.getWorldSeed() == null) { player.sendMessage("No active generator."); return; }
        WorldLayout layout = WorldLayouts.forSeed(generator.getWorldSeed());
        if (layout == null) { player.sendMessage("World layout is unavailable."); return; }
        WorldLayout.Rect rect = layout.blockAt(site.minX, site.minZ);
        WorldLayout.Landmark target = rect == null ? null : layout.landmarkAt(rect);
        if (rect == null || rect.minX != site.minX || rect.minZ != site.minZ || rect.width() != site.width
            || rect.depth() != site.depth || target == null || !(target.choice instanceof LandmarkPlans.Choice)
            || !site.group.equals(((LandmarkPlans.Choice) target.choice).clip.getGroupId())) {
            player.sendMessage("This indexed landmark does not match the current world plan. Run /landmark list again.");
            return;
        }
        List<int[]> roads = new ArrayList<>();
        for (int x = rect.minX; x <= rect.maxX; x++) {
            roads.add(new int[]{x, rect.minZ - 1});
            roads.add(new int[]{x, rect.maxZ + 1});
        }
        for (int z = rect.minZ; z <= rect.maxZ; z++) {
            roads.add(new int[]{rect.minX - 1, z});
            roads.add(new int[]{rect.maxX + 1, z});
        }
        Location origin = player.getLocation();
        roads.sort(Comparator.comparingLong(pos -> {
            long dx = ((long)pos[0] << 4) + 8 - origin.getBlockX();
            long dz = ((long)pos[1] << 4) + 8 - origin.getBlockZ();
            return dx * dx + dz * dz;
        }));
        for (int[] pos : roads) {
            if (!world.isChunkGenerated(pos[0], pos[1]) || !layout.isCityRoadCell(pos[0], pos[1])) continue;
            int x = (pos[0] << 4) + 8, z = (pos[1] << 4) + 8;
            int y = Math.max(world.getMinHeight() + 2, Math.min(world.getMaxHeight() - 2, world.getHighestBlockYAt(x, z) + 1));
            if (player.teleport(new Location(world, x + 0.5, y, z + 0.5))) {
                player.sendMessage("Teleported to road outside " + site.name + ".");
            } else player.sendMessage("Could not teleport to the landmark road.");
            return;
        }
        player.sendMessage("No generated road entrance found for " + site.name);
    }

    private static void usage(Player player) {
        player.sendMessage("/landmark list [name] [page] ? list indexed accepted landmarks in generated terrain");
        player.sendMessage("/landmark tp <list-number>  OR  /landmark tp <name> [ordinal]");
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (!(sender instanceof Player) || !sender.hasPermission("metropolis.command")) return List.of();
        List<String> choices = new ArrayList<>();
        if (args.length == 1) { choices.add("list"); choices.add("tp"); }
        if (args.length == 2 && "list".equalsIgnoreCase(args[0])) {
            choices.add("all"); choices.add("Minekea"); choices.add("MegaMall"); choices.add("CentralPark");
        }
        if (args.length == 2 && "tp".equalsIgnoreCase(args[0])) {
            List<Site> previous = lastList.get(((Player) sender).getUniqueId());
            if (previous != null) for (int i = 1; i <= Math.min(previous.size(), 30); i++) choices.add(Integer.toString(i));
            choices.add("Minekea"); choices.add("MegaMall"); choices.add("CentralPark");
        }
        if (args.length < 1 || args.length > 3) return List.of();
        String prefix = args[args.length - 1].toLowerCase(Locale.ROOT);
        choices.removeIf(s -> !s.toLowerCase(Locale.ROOT).startsWith(prefix));
        return choices;
    }

    static final class Site {
        final String name, group;
        final int minX, minZ, width, depth;
        final String direction;
        Site(String name, String group, int minX, int minZ, int width, int depth, String direction) {
            this.name = name; this.group = group; this.minX = minX; this.minZ = minZ;
            this.width = width; this.depth = depth; this.direction = direction;
        }
        String key() { return minX + ":" + minZ + ":" + width + ":" + depth; }
        int centerX() { return minX * 16 + width * 8; }
        int centerZ() { return minZ * 16 + depth * 8; }
        boolean matches(String input) {
            String token = input.toLowerCase(Locale.ROOT);
            return token.isEmpty() || token.equals("all") || name.toLowerCase(Locale.ROOT).contains(token)
                || group.toLowerCase(Locale.ROOT).contains(token)
                || (token.equals("minekea") && name.toLowerCase(Locale.ROOT).contains("ikea"));
        }
    }
}
