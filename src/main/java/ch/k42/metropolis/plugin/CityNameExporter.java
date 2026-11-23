package ch.k42.metropolis.plugin;

import ch.k42.metropolis.grid.urbanGrid.context.CityRingField;
import ch.k42.metropolis.grid.urbanGrid.enums.ContextType;
import ch.k42.metropolis.grid.urbanGrid.layout.WaterField;
import ch.k42.metropolis.grid.urbanGrid.layout.WorldLayouts;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.File;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Logger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.DeflaterOutputStream;
import java.util.zip.InflaterInputStream;
import org.bukkit.Bukkit;
import org.bukkit.World;
import org.bukkit.command.CommandSender;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitRunnable;

/** Persistent generated-city registry and coverage export, R30ea file-format compatible. */
public final class CityNameExporter {
    public static final String FILE_NAME = "metropolis-cities.tsv";
    public static final String RETIRED_NAMES_FILE = "metropolis-cities-retired-names.tsv";
    public static final String PRE_FILTER_BACKUP_FILE = "metropolis-cities-before-highway-filter.tsv";
    private final Logger logger;
    private final Map<String, WorldCities> worlds = new ConcurrentHashMap<>();
    private static final String[] ROOTS = {
        "Ash", "Brindle", "Clear", "Dun", "Elder", "Fair", "Glen", "Hart", "Iron", "Juniper", "Kings", "Lark",
        "Marl", "North", "Oak", "Pine", "Quarry", "Raven", "Silver", "Stone", "Thorn", "Vale", "West", "Willow",
        "Amber", "Briar", "Cinder", "Dove", "Elm", "Fox", "Gold", "Heather"
    };
    private static final String[] ENDS = {
        "bridge", "brook", "bury", "cross", "dale", "field", "ford", "gate", "haven", "hill", "hurst", "mere",
        "mont", "mouth", "port", "ridge", "stead", "ton", "vale", "wick", "wood", "crest", "borough", "fall",
        "harbor", "hollow", "minster", "reach", "shire", "view", "well", "worth"
    };
    private static final byte NONE = 0;
    private static final Pattern REGION = Pattern.compile("r\\.(-?\\d+)\\.(-?\\d+)\\.mca");

    public CityNameExporter(Logger logger) { this.logger = logger; }

    private static long chunkKey(int x, int z) { return ((long)x << 32) ^ (z & 0xffffffffL); }
    private static long key(int x, int z) { return ((long)x << 32) ^ (z & 0xffffffffL); }

    public void record(World world, int x, int z, ContextType context) {
        if (!PluginConfig.isCityRingsEnabled() || world.getEnvironment() != World.Environment.NORMAL) return;
        WorldCities data = worlds.computeIfAbsent(world.getUID().toString(), id -> new WorldCities(world));
        CityRingField.Site site = data.field.nearestSite(x, z);
        if (!data.field.withinOuterCity(x, z, site)) return;
        data.sites.putIfAbsent(key(site.a, site.b), site);
        setZone(data, x, z, classify(data, x, z, context));
    }

    private static void setZone(WorldCities data, int x, int z, byte zone) {
        Byte prior = data.chunkZones.put(chunkKey(x, z), zone);
        if (prior == null || prior.byteValue() != zone) data.revision++;
    }

    static byte displayBand(double distance, boolean farm) {
        if (!Double.isFinite(distance) || distance < 0.0) return NONE;
        if (distance <= 1.0) {
            double squared = distance * distance;
            if (squared <= 2.0 / 23.0) return 3;
            if (squared <= 14.0 / 23.0) return 2;
            return 1;
        }
        return farm ? (byte)1 : NONE;
    }

    private static int fromLegacyZone(int rank) {
        if (rank >= 1 && rank <= 6) return 1;
        if (rank >= 7 && rank <= 9) return 2;
        if (rank >= 10 && rank <= 12) return 3;
        return 0;
    }

    private static byte classify(WorldCities data, int x, int z, ContextType context) {
        CityRingField.Site site = data.field.nearestSite(x, z);
        if (!data.field.withinOuterCity(x, z, site)) return NONE;
        if (context == ContextType.OCEAN || WorldLayouts.waterFor(data.world.getSeed()).kindAt(x, z) == WaterField.Kind.OCEAN) return NONE;
        return displayBand(data.field.distance(x, z), data.field.isFarm(x, z));
    }

    /** Scans pre-existing MCA region headers without loading world chunks. */
    public void exportExisting(JavaPlugin plugin, World world, CommandSender sender) {
        if (!PluginConfig.isCityRingsEnabled()) {
            sender.sendMessage("Metropolis city rings must be enabled to export city limits.");
            return;
        }
        File region = new File(world.getWorldFolder(), "region");
        File[] files = region.listFiles((dir, name) -> name.matches("r\\.-?\\d+\\.-?\\d+\\.mca"));
        if (files == null || files.length == 0) {
            sender.sendMessage("No pregenerated .mca region files found in " + region);
            return;
        }
        Arrays.sort(files, Comparator.comparing(File::getName));
        sender.sendMessage("Rebuilding city HUD coverage from " + files.length
            + " generated-region headers and the committed city layout. No world chunks will be loaded.");
        WorldCities wc = worlds.computeIfAbsent(world.getUID().toString(), id -> new WorldCities(world));
        new BukkitRunnable() {
            int fileIndex = 0, regionPos = 1024, visited = 0, failed = 0, populated = 0, rx, rz;
            ByteBuffer header;
            @Override public void run() {
                for (int budget = 0; budget < 64; budget++) {
                    if (fileIndex >= files.length && (header == null || regionPos == 1024)) break;
                    if (header == null || regionPos == 1024) {
                        header = null;
                        File file = files[fileIndex++];
                        Matcher m = REGION.matcher(file.getName());
                        if (!m.matches()) continue;
                        rx = Integer.parseInt(m.group(1)); rz = Integer.parseInt(m.group(2));
                        try (FileChannel channel = FileChannel.open(file.toPath(), StandardOpenOption.READ)) {
                            header = ByteBuffer.allocate(4096);
                            while (header.hasRemaining()) if (channel.read(header) < 0) throw new IOException("short region header");
                            header.flip(); regionPos = 0; visited++;
                        } catch (Exception e) {
                            header = null; regionPos = 1024; failed++;
                            logger.warning("Could not read region header " + file + ": " + e);
                            continue;
                        }
                    }
                    int pos = regionPos++;
                    if (header.getInt() == 0) continue;
                    int x = rx * 32 + (pos & 31), z = rz * 32 + (pos >> 5);
                    try {
                        CityRingField.Site site = wc.field.nearestSite(x, z);
                        if (!wc.field.withinOuterCity(x, z, site)) continue;
                        wc.sites.putIfAbsent(key(site.a, site.b), site);
                        byte band = classify(wc, x, z, null);
                        setZone(wc, x, z, band);
                        if (band != NONE) populated++;
                    } catch (Exception e) {
                        failed++; logger.warning("Cannot classify region cell " + x + "," + z + ": " + e);
                    }
                }
                if (fileIndex >= files.length && (header == null || regionPos == 1024)) {
                    cancel();
                    try { save(wc); }
                    catch (IOException e) {
                        logger.warning("Could not export city registry: " + e);
                        sender.sendMessage("City export failed: " + e);
                        return;
                    }
                    String result = "City export complete: " + populated + " city chunks in " + wc.sites.size()
                        + " cities across " + visited + " region files" + (failed == 0 ? "" : " (" + failed + " failures)");
                    sender.sendMessage(result); logger.info(result);
                }
            }
        }.runTaskTimer(plugin, 1L, 1L);
    }

    private static long mix(long x) {
        x += -7046029254386353131L;
        x = (x ^ (x >>> 30)) * -4658895280553007687L;
        x = (x ^ (x >>> 27)) * -7723592293110705685L;
        return x ^ (x >>> 31);
    }

    private static String generatedName(long worldSeed, int a, int b) {
        long m = mix(worldSeed ^ ((long)a * 7146057691288625177L) ^ ((long)b * -7046029254386353131L));
        return ROOTS[Math.floorMod(m, ROOTS.length)] + ENDS[Math.floorMod(mix(m), ENDS.length)];
    }

    private static Map<Long, String> readCityNames(Path path) throws IOException {
        Map<Long, String> names = new HashMap<>();
        if (!Files.isRegularFile(path)) return names;
        for (String line : Files.readAllLines(path, StandardCharsets.UTF_8)) {
            String[] v = line.split("\t", -1);
            if (v.length < 4 || !v[0].equals("CITY")) continue;
            try { names.put(key(Integer.parseInt(v[1]), Integer.parseInt(v[2])), v[3]); }
            catch (NumberFormatException ignored) { }
        }
        return names;
    }

    private static Map<Long, String> readRetiredNames(Path path, long seed) throws IOException {
        Map<Long, String> names = new HashMap<>();
        if (!Files.isRegularFile(path)) return names;
        List<String> lines = Files.readAllLines(path, StandardCharsets.UTF_8);
        if (lines.isEmpty() || !lines.get(0).equals("METROPOLIS_RETIRED_NAMES_V1\t" + seed))
            throw new IOException("retired names archive seed/format mismatch: " + path);
        for (String line : lines.subList(1, lines.size())) {
            String[] v = line.split("\t", -1);
            if (v.length < 4 || !v[0].equals("CITY")) continue;
            try { names.put(key(Integer.parseInt(v[1]), Integer.parseInt(v[2])), v[3]); }
            catch (NumberFormatException ignored) { }
        }
        return names;
    }

    private static void writeRetiredNames(Path path, long seed, Map<Long, String> names) throws IOException {
        List<Long> ordered = new ArrayList<>(names.keySet());
        ordered.sort(Long::compare);
        List<String> lines = new ArrayList<>(ordered.size() + 1);
        lines.add("METROPOLIS_RETIRED_NAMES_V1\t" + seed);
        for (long key : ordered) {
            String cleaned = names.get(key).replace('\t', ' ').replace('\r', ' ').replace('\n', ' ').trim();
            lines.add("CITY\t" + (int)(key >> 32) + "\t" + (int)key + "\t" + cleaned);
        }
        Path tmp = Files.createTempFile(path.getParent(), "metropolis-retired-", ".tmp");
        try { Files.write(tmp, lines, StandardCharsets.UTF_8);
            try { Files.move(tmp, path, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING); }
            catch (AtomicMoveNotSupportedException ignored) { Files.move(tmp, path, StandardCopyOption.REPLACE_EXISTING); } }
        finally { Files.deleteIfExists(tmp); }
    }

    public synchronized void flush() {
        for (World world : Bukkit.getWorlds()) {
            if (new File(world.getWorldFolder(), FILE_NAME).isFile())
                worlds.computeIfAbsent(world.getUID().toString(), id -> new WorldCities(world));
        }
        for (WorldCities data : worlds.values()) {
            if (data.revision == data.savedRevision) continue;
            if (data.sites.isEmpty() && !new File(data.world.getWorldFolder(), FILE_NAME).isFile()) continue;
            try { save(data); }
            catch (IOException e) { logger.warning("Cannot export cities in " + data.world.getName() + ": " + e); }
        }
    }

    private void save(WorldCities data) throws IOException {
        Path path = data.world.getWorldFolder().toPath().resolve(FILE_NAME);
        Path retired = data.world.getWorldFolder().toPath().resolve(RETIRED_NAMES_FILE);
        Map<Long, String> names = readCityNames(path);
        Map<Long, String> allNames = new HashMap<>(readRetiredNames(retired, data.world.getSeed()));
        allNames.putAll(names);
        List<CityRingField.Site> sites = new ArrayList<>();
        for (CityRingField.Site site : data.sites.values()) if (data.field.cityAccepted(site.a, site.b)) sites.add(site);
        sites.sort(Comparator.comparingInt((CityRingField.Site site) -> site.a).thenComparingInt(site -> site.b));
        Set<Long> accepted = new HashSet<>();
        for (CityRingField.Site site : sites) accepted.add(key(site.a, site.b));
        Map<Long, CityZoneBucket> buckets = new HashMap<>();
        for (Map.Entry<Long, Byte> entry : data.chunkZones.entrySet()) {
            long k = entry.getKey();
            int cx = (int)(k >> 32), cz = (int)k;
            CityRingField.Site site = data.field.nearestSite(cx, cz);
            if (site == null) continue;
            long owner = key(site.a, site.b);
            if (accepted.contains(owner)) buckets.computeIfAbsent(owner, ignored -> new CityZoneBucket()).add(k, entry.getValue());
        }
        Set<String> usedNames = new HashSet<>();
        for (String value : allNames.values()) usedNames.add(value.toLowerCase(Locale.ROOT));
        List<String> lines = new ArrayList<>();
        lines.add("METROPOLIS_CITIES_V6\t" + data.world.getSeed() + "\t" + data.world.getName() + "\t"
            + PluginConfig.getCityRingsRadius() + "\t" + PluginConfig.getCityRingsSpacing() + "\t"
            + PluginConfig.getCityRingsSizeVariation()/100.0 + "\t" + PluginConfig.getCityRingsEdgeWobble()/100.0
            + "\t" + PluginConfig.getCityRingsFarmBandWidth()/100.0 + "\t" + PluginConfig.getCityRingsFarmForestOverlap()/100.0);
        lines.add("# CITY\tsiteA\tsiteB\tname(editable)\tcenterBlockX\tcenterBlockZ\tminChunkX\tminChunkZ\twidth\theight\tzonesDeflate(base64)");
        for (CityRingField.Site site : sites) {
            double wobble = Math.min(0.9, Math.abs(PluginConfig.getCityRingsEdgeWobble() / 100.0));
            double farm = PluginConfig.getCityRingsFarmBandWidth() / 100.0;
            double overlap = PluginConfig.getCityRingsFarmForestOverlap() / 100.0;
            int radius = (int)Math.ceil(site.radius * (1 + farm + overlap) / (1 - wobble)) + 3;
            int minX = (int)Math.floor(site.centerX) - radius;
            int minZ = (int)Math.floor(site.centerZ) - radius;
            int width = radius * 2 + 2;
            if (width <= 0 || width > 2500) {
                logger.warning("City mask too large for site " + site.a + "," + site.b + "; check city ring settings");
                continue;
            }
            TreeMap<Integer, Byte> indices = new TreeMap<>();
            CityZoneBucket bucket = buckets.remove(key(site.a, site.b));
            if (bucket != null) for (int i = 0; i < bucket.size; i++) {
                long k = bucket.chunks[i];
                int dx = (int)(k >> 32) - minX, dz = (int)k - minZ;
                if (dx >= 0 && dx < width && dz >= 0 && dz < width) indices.put(dz * width + dx, bucket.zones[i]);
            }
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            try (DataOutputStream stream = new DataOutputStream(new DeflaterOutputStream(bytes))) {
                stream.writeInt(indices.size());
                for (Map.Entry<Integer, Byte> entry : indices.entrySet()) {
                    stream.writeInt(entry.getKey()); stream.writeByte(entry.getValue());
                }
            }
            String encoded = Base64.getEncoder().encodeToString(bytes.toByteArray());
            long cityKey = key(site.a, site.b);
            String name = allNames.getOrDefault(cityKey, generatedName(data.world.getSeed(), site.a, site.b));
            name = name.replace('\t', ' ').replace('\r', ' ').replace('\n', ' ').trim();
            if (name.isEmpty()) name = generatedName(data.world.getSeed(), site.a, site.b);
            if (!allNames.containsKey(cityKey) && usedNames.contains(name.toLowerCase(Locale.ROOT))) {
                String[] suffixes = {"North", "East", "South", "West", "Upper", "Lower", "New", "Old", "Greater", "Little",
                    "High", "Low", "Cross", "Harbor", "Heights", "Grove"};
                long suffixSeed = mix(data.world.getSeed() ^ cityKey);
                for (int i = 0; i <= suffixes.length; i++) {
                    String suffix = i < suffixes.length ? suffixes[Math.floorMod(suffixSeed + i, suffixes.length)] :
                        "(" + site.a + "," + site.b + ")";
                    String candidate = name + " " + suffix;
                    if (!usedNames.contains(candidate.toLowerCase(Locale.ROOT))) { name = candidate; break; }
                }
            }
            usedNames.add(name.toLowerCase(Locale.ROOT));
            lines.add("CITY\t" + site.a + "\t" + site.b + "\t" + name + "\t" + Math.round(site.centerX * 16)
                + "\t" + Math.round(site.centerZ * 16) + "\t" + minX + "\t" + minZ + "\t" + width + "\t" + width + "\t" + encoded);
        }
        Map<Long, String> newlyRetired = new HashMap<>();
        for (Map.Entry<Long, String> entry : names.entrySet()) {
            long oldKey = entry.getKey();
            if (!data.field.cityAccepted((int)(oldKey >> 32), (int)oldKey)) newlyRetired.put(oldKey, entry.getValue());
        }
        if (!newlyRetired.isEmpty()) {
            Path backup = data.world.getWorldFolder().toPath().resolve(PRE_FILTER_BACKUP_FILE);
            if (!Files.exists(backup)) {
                try { Files.copy(path, backup); } catch (FileAlreadyExistsException ignored) { }
            }
            Map<Long, String> archive = readRetiredNames(retired, data.world.getSeed());
            archive.putAll(newlyRetired);
            writeRetiredNames(retired, data.world.getSeed(), archive);
        }
        Path tmp = Files.createTempFile(path.getParent(), "metropolis-cities-", ".tmp");
        try { Files.write(tmp, lines, StandardCharsets.UTF_8);
            try { Files.move(tmp, path, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING); }
            catch (AtomicMoveNotSupportedException ignored) { Files.move(tmp, path, StandardCopyOption.REPLACE_EXISTING); } }
        finally { Files.deleteIfExists(tmp); }
        data.savedRevision = data.revision;
        logger.info("Exported " + sites.size() + " geographic city footprints (3 city rings) to " + path);
    }

    static final class WorldCities {
        final World world;
        final CityRingField field;
        final Map<Long, CityRingField.Site> sites = new ConcurrentHashMap<>();
        final Map<Long, Byte> chunkZones = new ConcurrentHashMap<>();
        volatile long revision = 0;
        volatile long savedRevision = -1;

        WorldCities(World world) {
            this.world = world;
            field = new CityRingField(world.getSeed());
            Path path = world.getWorldFolder().toPath().resolve(FILE_NAME);
            if (!Files.isRegularFile(path)) return;
            try {
                List<String> lines = Files.readAllLines(path, StandardCharsets.UTF_8);
                if (lines.isEmpty()) return;
                String first = lines.get(0);
                boolean v6 = first.startsWith("METROPOLIS_CITIES_V6\t" + world.getSeed() + "\t");
                boolean v5 = first.startsWith("METROPOLIS_CITIES_V5\t" + world.getSeed() + "\t");
                boolean v4 = first.startsWith("METROPOLIS_CITIES_V4\t" + world.getSeed() + "\t");
                if (!v6 && !v5 && !v4) throw new IOException("city registry world seed or format mismatch");
                // R30ea loads all valid geographic sites before reading mask payloads.
                // A corrupt mask must not discard the names/locations of later cities.
                for (String line : lines) {
                    String[] values = line.split("\t", -1);
                    if (values.length < 4 || !values[0].equals("CITY")) continue;
                    try {
                        int a = Integer.parseInt(values[1]); int b = Integer.parseInt(values[2]);
                        if (field.cityAccepted(a, b)) sites.put(key(a, b), field.site(a, b));
                    } catch (NumberFormatException ignored) { }
                }
                for (String line : lines) {
                    String[] values = line.split("\t", -1);
                    if (values.length != 11 || !values[0].equals("CITY")) continue;
                    int minX = Integer.parseInt(values[6]); int minZ = Integer.parseInt(values[7]);
                    int width = Integer.parseInt(values[8]), height = Integer.parseInt(values[9]);
                    if (width < 1 || height < 1 || (long)width * height > 8_000_000) continue;
                    try (DataInputStream stream = new DataInputStream(new InflaterInputStream(
                        new ByteArrayInputStream(Base64.getDecoder().decode(values[10]))))) {
                        int size = stream.readInt();
                        if (size < 0 || size > Math.min((long)width * height, 8_000_000L)) continue;
                        for (int i = 0; i < size; i++) {
                            int index = stream.readInt(), zone = stream.readUnsignedByte();
                            if (index < 0 || index >= width * height || (!v6 && zone < 1)
                                || (v4 && zone > 12) || (!v4 && zone > 3)) throw new IOException("bad stored city rank");
                            chunkZones.put(chunkKey(minX + index % width, minZ + index / width),
                                (byte)(v4 ? fromLegacyZone(zone) : zone));
                        }
                    }
                }
            } catch (Exception e) {
                Logger.getLogger(CityNameExporter.class.getName()).warning("Cannot reload city masks: " + e);
            }
        }
    }

    static final class CityZoneBucket {
        long[] chunks = new long[32];
        byte[] zones = new byte[32];
        int size;
        private CityZoneBucket() { }
        void add(long key, byte band) {
            if (size == chunks.length) {
                int next = chunks.length + (chunks.length >> 1);
                chunks = Arrays.copyOf(chunks, next);
                zones = Arrays.copyOf(zones, next);
            }
            chunks[size] = key;
            zones[size++] = band;
        }
    }
}
