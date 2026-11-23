package ch.k42.metropolis.minions;

import ch.k42.metropolis.generator.MetropolisGenerator;
import ch.k42.metropolis.grid.urbanGrid.clipboard.Clipboard;
import ch.k42.metropolis.grid.urbanGrid.parcel.ClipboardParcel;
import ch.k42.metropolis.plugin.PluginConfig;
import java.io.File;
import java.io.FileFilter;
import java.io.FileInputStream;
import java.io.FilenameFilter;
import java.io.IOException;
import java.io.InputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.logging.FileHandler;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.logging.SimpleFormatter;
import org.bukkit.Bukkit;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.generator.ChunkGenerator;

public class Minions {
   private static final String TAG = "[Metropolis] ";
   private static final String DTAG = "[Debug] ";
   private static final int sigma_factor = PluginConfig.getSigmaCut();

   public static final void w(String msg) {
      Bukkit.getLogger().warning("[Metropolis] " + msg);
   }

   public static final void w(String msg, Object... args) {
      Bukkit.getLogger().warning("[Metropolis] " + String.format(msg, args));
   }

   public static void e(Exception e) {
      Bukkit.getLogger().warning("[Metropolis] Exception: " + e.getMessage());
      if (PluginConfig.isDebugEnabled()) {
         e.printStackTrace();
      }
   }

   public static final void i(String msg) {
      Bukkit.getLogger().info("[Metropolis] " + msg);
   }

   public static final void i(String msg, Object... args) {
      Bukkit.getLogger().info("[Metropolis] " + String.format(msg, args));
   }

   public static final void d(String msg) {
      if (PluginConfig.isDebugEnabled()) {
         Bukkit.getLogger().info("[Metropolis] [Debug] " + msg);
      }
   }

   public static final void d(String msg, Object... args) {
      if (PluginConfig.isDebugEnabled()) {
         Bukkit.getLogger().info("[Metropolis] [Debug] " + String.format(msg, args));
      }
   }

   /**
    * Gated on its own consoleOutput.debugCutouts toggle rather than the
    * general debug flag -- cutout tracing fires per-cutout, per-building,
    * and would otherwise flood the console alongside every other debug
    * category whenever isDebugEnabled() is on for unrelated reasons.
    */
   public static final void dCutout(String msg) {
      if (PluginConfig.isCutoutDebugEnabled()) {
         Bukkit.getLogger().info("[Metropolis] [Debug:Cutout] " + msg);
      }
   }

   /**
    * District debug has two tiers. The ordinary toggle reports milestones
    * (including FARM and FOREST chunk population and MORPHOMOS region
    * construction). Recursive searches can generate millions of messages;
    * their detailed trace therefore requires debugDistrictsVerbose=true.
    * Both tiers have independent per-minute limits to keep startup joinable.
    * This changes only observability: district placement is untouched.
    */
   private static final long DISTRICT_LOG_WINDOW_NS = 60_000_000_000L;
   private static final int DISTRICT_SUMMARY_PER_WINDOW = 1800;
   private static final int DISTRICT_VERBOSE_PER_WINDOW = 250;
   private static final int DISTRICT_MAX_MESSAGE_LENGTH = 640;
   private static long districtLogWindowStart = System.nanoTime();
   private static int districtSummaryEmitted, districtVerboseEmitted;
   private static long districtSummarySuppressed, districtVerboseSuppressed;

   private static boolean isDistrictSummary(String msg) {
      return msg.startsWith("MORPHOMOS ")
          || msg.startsWith("FARM chunk=")
          || msg.startsWith("FOREST chunk=")
          || msg.startsWith("SELECTED FINAL ")
          || msg.startsWith("DISTRICT MEMBERSHIP ASSIGNED ")
          || msg.startsWith("DYNAMIC ITERATIONS ")
          || msg.startsWith("LANDMARK PLACEMENT ")
          || msg.startsWith("Found absolutely no schems");
   }

   private static synchronized boolean allowDistrictLog(boolean summary) {
      long now = System.nanoTime();
      if (now - districtLogWindowStart >= DISTRICT_LOG_WINDOW_NS || now - districtLogWindowStart < 0) {
         if (districtSummarySuppressed != 0 || districtVerboseSuppressed != 0) {
            Bukkit.getLogger().info("[Metropolis] [Debug:District] Throttle summary: "
                + districtSummarySuppressed + " summary and " + districtVerboseSuppressed
                + " verbose messages suppressed in preceding 60-second window");
         }
         districtLogWindowStart = now;
         districtSummaryEmitted = districtVerboseEmitted = 0;
         districtSummarySuppressed = districtVerboseSuppressed = 0;
      }
      if (summary) {
         if (districtSummaryEmitted >= DISTRICT_SUMMARY_PER_WINDOW) {
            districtSummarySuppressed++;
            return false;
         }
         districtSummaryEmitted++;
      } else {
         if (districtVerboseEmitted >= DISTRICT_VERBOSE_PER_WINDOW) {
            districtVerboseSuppressed++;
            return false;
         }
         districtVerboseEmitted++;
      }
      return true;
   }

   public static final void dDistrict(String msg) {
      if (!PluginConfig.isDistrictDebugEnabled()) return;
      boolean summary = isDistrictSummary(msg);
      if (!summary && !PluginConfig.isDistrictVerboseDebugEnabled()) return;
      if (!allowDistrictLog(summary)) return;
      String out = msg.length() > DISTRICT_MAX_MESSAGE_LENGTH
          ? msg.substring(0, DISTRICT_MAX_MESSAGE_LENGTH) + "... [truncated]" : msg;
      Bukkit.getLogger().info("[Metropolis] [Debug:District] " + out);
   }

   public static final void dDistrict(String msg, Object... args) {
      if (!PluginConfig.isDistrictDebugEnabled()) return;
      boolean summary = isDistrictSummary(msg);
      if (!summary && !PluginConfig.isDistrictVerboseDebugEnabled()) return;
      if (!allowDistrictLog(summary)) return;
      String formatted = String.format(msg, args);
      if (formatted.length() > DISTRICT_MAX_MESSAGE_LENGTH) {
         formatted = formatted.substring(0, DISTRICT_MAX_MESSAGE_LENGTH) + "... [truncated]";
      }
      Bukkit.getLogger().info("[Metropolis] [Debug:District] " + formatted);
   }

   /**
    * Dedicated filler-placement debug channel. This is deliberately separate
    * from the district channel so filler diagnosis can be enabled without
    * turning on the much noisier partition/split tracing.
    */
   public static final void dFiller(String msg) {
      if (PluginConfig.isFillerDebugEnabled()) {
         Bukkit.getLogger().info("[Metropolis] [Debug:Filler] " + msg);
      }
   }

   public static final void dFiller(String msg, Object... args) {
      if (PluginConfig.isFillerDebugEnabled()) {
         Bukkit.getLogger().info("[Metropolis] [Debug:Filler] " + String.format(msg, args));
      }
   }

   public static final void dFillerRoll(String msg, Object... args) {
      if (PluginConfig.isFillerRollDebugEnabled()) {
         Bukkit.getLogger().info("[Metropolis] [Debug:Filler:Roll] " + String.format(msg, args));
      }
   }

   public static final void dFillerQuery(String msg, Object... args) {
      if (PluginConfig.isFillerQueryDebugEnabled()) {
         Bukkit.getLogger().info("[Metropolis] [Debug:Filler:Query] " + String.format(msg, args));
      }
   }

   public static final void dFillerCandidate(String msg, Object... args) {
      if (PluginConfig.isFillerCandidateDebugEnabled()) {
         Bukkit.getLogger().info("[Metropolis] [Debug:Filler:Candidate] " + String.format(msg, args));
      }
   }

   public static final void dFillerReject(String msg, Object... args) {
      if (PluginConfig.isFillerRejectionDebugEnabled()) {
         Bukkit.getLogger().info("[Metropolis] [Debug:Filler:Reject] " + String.format(msg, args));
      }
   }

   public static final void dFillerSelect(String msg, Object... args) {
      if (PluginConfig.isFillerSelectionDebugEnabled()) {
         Bukkit.getLogger().info("[Metropolis] [Debug:Filler:Select] " + String.format(msg, args));
      }
   }

   public static final void dFillerFallback(String msg, Object... args) {
      if (PluginConfig.isFillerFallbackDebugEnabled()) {
         Bukkit.getLogger().info("[Metropolis] [Debug:Filler:Fallback] " + String.format(msg, args));
      }
   }

   public static final void dFillerResult(String msg, Object... args) {
      if (PluginConfig.isFillerResultDebugEnabled()) {
         Bukkit.getLogger().info("[Metropolis] [Debug:Filler:Result] " + String.format(msg, args));
      }
   }

   /** Dedicated DENSERESIDENTIAL family-pool tracing. Kept separate from district debug. */
   public static final void dDenseResidential(String msg, Object... args) {
      if (PluginConfig.isDenseResidentialFamilyDebugEnabled()) {
         Bukkit.getLogger().info("[Metropolis] [Debug:DenseResidential] " + String.format(msg, args));
      }
   }

   /**
    * Dedicated river-placement tracing -- where the seed placed the
    * river's baseline, whether a given tile's chunk range comes anywhere
    * near it, how tryPlaceRiverBand() resolved each candidate rectangle,
    * and which chunks actually resolved to RIVER during carving. Kept
    * separate from every other debug category (same reasoning as
    * dCutout/dDistrict/dFiller above).
    */
   public static final void dRiver(String msg, Object... args) {
      if (PluginConfig.isRiverDebugEnabled()) {
         Bukkit.getLogger().info("[Metropolis] [Debug:River] " + String.format(msg, args));
      }
   }

   /**
    * Dedicated tracing for JUST reclaimRiverDistricts()'s own road
    * placement -- the wrap-around road cells (separators + water
    * frontage) it paints around each reclaimed rect, and the reasoning
    * for every edge it deliberately leaves unpainted. Gated on its own
    * consoleOutput.debugReclaimRoads toggle rather than debugRiver:
    * debugRiver already fires for placement/corridor/bridge tracing
    * across the WHOLE river system, which drowns out the one narrow
    * question this channel exists to answer.
    */
   public static final void dReclaimRoads(String msg, Object... args) {
      if (PluginConfig.isReclaimRoadsDebugEnabled()) {
         Bukkit.getLogger().info("[Metropolis] [Debug:ReclaimRoads] " + String.format(msg, args));
      }
   }

   /** Dedicated tracing for the ocean feature (OceanProvider placement, UrbanGrid.placeOceanIfPresent()'s per-tile carve decisions) -- same reasoning as dReclaimRoads() for keeping it separate from dRiver(): the two water features are independent and a session debugging one shouldn't be drowned in the other's noise. */
   public static final void dOcean(String msg, Object... args) {
      if (PluginConfig.isOceanDebugEnabled()) {
         Bukkit.getLogger().info("[Metropolis] [Debug:Ocean] " + String.format(msg, args));
      }
   }

   /** Dedicated tracing for the shore relief (ForestGenerator.populateShore(): what the river / ocean bank lifts, next to what the inland wilderness would be at the same column) -- its own channel so it can be switched on for a terrain-seam hunt without the river and ocean traces. consoleOutput.debugShore. */
   public static final void dShore(String msg, Object... args) {
      if (PluginConfig.isShoreDebugEnabled()) {
         Bukkit.getLogger().info("[Metropolis] [Debug:Shore] " + String.format(msg, args));
      }
   }

   /**
    * Dedicated tracing for landmark districts (UrbanGrid.tryPlaceLandmark() /
    * carveLandmark()): every attempt, every reason one is abandoned, and every
    * placement. Own consoleOutput.debugLandmarks toggle so it doesn't ride
    * along with the much noisier general district trace (Minions.dDistrict()).
    */
   public static final void dLandmark(String msg, Object... args) {
      if (PluginConfig.isLandmarkDebugEnabled()) {
         Bukkit.getLogger().info("[Metropolis] [Debug:Landmark] " + String.format(msg, args));
      }
   }

   /**
    * Gated on its own consoleOutput.debugDecay toggle -- decay tracing
    * fires once per road, highway, and building footprint on every chunk
    * population, which adds up fast on a busy generation run.
    */
   public static final void dDecay(String msg) {
      if (PluginConfig.isDecayDebugEnabled()) {
         Bukkit.getLogger().info("[Metropolis] [Debug:Decay] " + msg);
      }
   }

   public static final void dDecay(String msg, Object... args) {
      if (PluginConfig.isDecayDebugEnabled()) {
         Bukkit.getLogger().info("[Metropolis] [Debug:Decay] " + String.format(msg, args));
      }
   }

   /**
    * Dedicated logger for branch node-selection tracing. Deliberately
    * separate from Bukkit's shared server logger: this is meant to be
    * tailed/grepped on its own while chasing a single generation run's
    * node placement, not interleaved with every other plugin/debug
    * category in logs/latest.log. Lazily null until initBranchNodeLog()
    * is called from MetropolisPlugin#onEnable(); dNode() is a no-op
    * until then (or if the toggle is off), so call sites never need to
    * null-check it themselves.
    */
   private static Logger branchNodeLogger;

   /**
    * Written once at the top of every branch-nodes.log session (see
    * initBranchNodeLog()) so the file explains its own tags even read in
    * isolation, without this source tree handy. The GROWTH SCHEDULING
    * tags ([BUILD][CACHE]/[BUILD][FLUSH]/[BUILD][SKIP]/[BUILD][TIMEOUT]/
    * [BUILD][FALLBACK]) cover a different layer than the untagged
    * node/pairing/chain lines around them: those only ever run AFTER a
    * growth pass has already been scheduled to fire, so when nothing
    * scheduled a pass at all (no pending build, or one with zero
    * supportHits), this file would otherwise stay silent with no
    * indication why -- that's what these tags are for. Search this file
    * for [SKIP] first when diagnosing zero branches.
    */
   private static final String BRANCH_NODE_LOG_KEY =
           "==================== Metropolis Branch Node Log -- Key ====================\n" +
                   "Untagged lines are per-node/per-attempt geometry tracing (pool pops,\n" +
                   "pairing, raytraces, chain segments) -- see the surrounding message text,\n" +
                   "they're self-explanatory in context.\n" +
                   "\n" +
                   "Bracketed [BUILD] lines are GROWTH SCHEDULING events instead -- a\n" +
                   "different, higher-level layer: whether a growth pass was even scheduled\n" +
                   "to run at all, before any of the node-level tracing below could ever\n" +
                   "fire. Every build (every Parcel) rolls and flushes its OWN growth\n" +
                   "alone -- nothing pools multiple buildings into one shared roll anymore\n" +
                   "-- but each flush is deliberately held back until a couple of rings of\n" +
                   "real neighbouring buildings around it are already placed, so branches\n" +
                   "still have somewhere real to land across a lot line or a street. See\n" +
                   "DecayProviderNormal.neighbourhoodReady()/PluginConfig.getBranchNeighbourDepth().\n" +
                   "  [BUILD][CACHE]    A build's own decay pass finished and its growth\n" +
                   "                    candidates were cached, waiting on its neighbourhood.\n" +
                   "                    (verbose tier -- fires once per build)\n" +
                   "  [BUILD][FLUSH]    Growth is actually being rolled and placed now, for\n" +
                   "                    this one build -- everything below this line (until\n" +
                   "                    the next [BUILD] line) is that build's node tracing.\n" +
                   "  [BUILD][TIMEOUT]  This build's neighbourhood never fully placed within\n" +
                   "                    PluginConfig.getBranchFlushTimeoutTicks(), so its\n" +
                   "                    watchdog gave up waiting and flushed its cached\n" +
                   "                    growth anyway.\n" +
                   "  [BUILD][SKIP]     Growth was triggered but this build decayed nothing\n" +
                   "                    growable (zero supportHits) -- nothing was rolled.\n" +
                   "                    THIS IS ALMOST ALWAYS THE ANSWER TO \"WHY ARE THERE\n" +
                   "                    ZERO BRANCHES\" for a specific building -- there will\n" +
                   "                    be NO node-level tracing at all following a [SKIP],\n" +
                   "                    since generateGrowth() is never even called.\n" +
                   "  [BUILD][FALLBACK] This decay pass had no grid, or its cell wasn't a\n" +
                   "                    registered parcel at all, so it ran its own growth\n" +
                   "                    immediately instead of caching it and waiting on a\n" +
                   "                    neighbourhood that can't be identified.\n" +
                   "===============================================================================";

   /**
    * Sets up the dedicated branch-node debug file at
    * <pluginDataFolder>/branch-nodes.log. Safe to call every onEnable()
    * (e.g. on a /reload) -- rebuilding the handler just means logging
    * starts a fresh block in the same file rather than duplicating
    * handlers. Failures are reported via the normal plugin logger and
    * otherwise swallowed: a broken debug log must never take the
    * generator down with it.
    */
   public static void initBranchNodeLog(File pluginDataFolder) {
      if (!PluginConfig.isBranchNodeDebugEnabled()) {
         branchNodeLogger = null;
         return;
      }

      try {
         if (!pluginDataFolder.exists()) {
            pluginDataFolder.mkdirs();
         }
         Logger logger = Logger.getLogger("MetropolisBranchNodes");
         logger.setUseParentHandlers(false);
         for (java.util.logging.Handler h : logger.getHandlers()) {
            logger.removeHandler(h);
         }
         FileHandler handler = new FileHandler(
                 new File(pluginDataFolder, "branch-nodes.log").getAbsolutePath(), true);
         handler.setFormatter(new SimpleFormatter());
         logger.addHandler(handler);
         logger.setLevel(Level.ALL);
         branchNodeLogger = logger;
         branchNodeLogger.info(BRANCH_NODE_LOG_KEY);
         Bukkit.getLogger().info("[Metropolis] Branch node debug log ready at "
                 + new File(pluginDataFolder, "branch-nodes.log").getAbsolutePath()
                 + " (enable with consoleOutput.debugBranchNodes: true)");
      } catch (IOException e) {
         Bukkit.getLogger().warning("[Metropolis] Could not set up branch-nodes.log: " + e.getMessage());
         branchNodeLogger = null;
      }
   }

   /**
    * Branch node-selection tracing. Gated on its own
    * isBranchNodeDebugEnabled() toggle, and written to the dedicated
    * branch-nodes.log file rather than the shared console/decay log --
    * see initBranchNodeLog(). No-op if that toggle is off or the file
    * logger never initialized.
    */
   /** Preserve the diagnostics channel for post-placement branch damage checks. */
   public static void dBranchDamage(String message) { dNode("[branch-damage] %s", message); }
   public static void dBranchDamageDetail(String message) { dNode("[branch-damage-detail] %s", message); }

   public static final void dNode(String msg, Object... args) {
      if (PluginConfig.isBranchNodeDebugEnabled() && branchNodeLogger != null) {
         branchNodeLogger.info(String.format(msg, args));
      }
   }

   /**
    * Same destination (branch-nodes.log) as dNode(), but gated on the
    * ADDITIONAL isBranchNodeVerboseDebugEnabled() toggle -- for the
    * per-candidate/per-attempt tracing that fires many times per branch
    * (every pool pop, every rejected pairing, every chain-hop attempt).
    * Use dNode() for the once-per-branch/once-per-outcome lines that
    * answer "did it work and why", and this for "show me every attempt
    * it made along the way". Still a no-op if debugBranchNodes itself is
    * off, since that's what sets up branchNodeLogger in the first place.
    */
   public static final void dNodeVerbose(String msg, Object... args) {
      if (PluginConfig.isBranchNodeVerboseDebugEnabled() && branchNodeLogger != null) {
         branchNodeLogger.info(String.format(msg, args));
      }
   }

   public static final MetropolisGenerator getGeneratorForPlayer(Player player) {
      World world = player.getWorld();
      ChunkGenerator cgenerator = world.getGenerator();
      return cgenerator instanceof MetropolisGenerator ? (MetropolisGenerator)cgenerator : null;
   }

   public static final File findFolder(File parent, String name) {
      File result = new File(parent, name);
      if (!result.isDirectory() && !result.mkdir()) {
         throw new UnsupportedOperationException("Could not create/find the folder: " + parent.getAbsolutePath() + File.separator + name);
      } else {
         return result;
      }
   }

   public static List<File> findAllFilesRecursively(File path, List<File> files, FilenameFilter filter) {
      File[] configFiles = path.listFiles(filter);
      files.addAll(Arrays.asList(configFiles));
      File[] subfolders = path.listFiles(isDirectory());

      for (File folder : subfolders) {
         findAllFilesRecursively(folder, files, filter);
      }

      return files;
   }

   public static List<File> findAllFilesRecursively(String path, List<File> files, FilenameFilter filter) {
      return findAllFilesRecursively(new File(path), files, filter);
   }

   public static FileFilter isDirectory() {
      return new FileFilter() {
         @Override
         public boolean accept(File pathname) {
            return pathname.isDirectory();
         }
      };
   }

   public static byte[] createChecksum(File file) throws NoSuchAlgorithmException, IOException {
      InputStream fis = new FileInputStream(file);
      byte[] buffer = new byte[1024];
      MessageDigest hash = MessageDigest.getInstance("MD5");
      hash.update(file.getName().getBytes());

      int numRead;
      do {
         numRead = fis.read(buffer);
         if (numRead > 0) {
            hash.update(buffer, 0, numRead);
         }
      } while (numRead != -1);

      fis.close();
      return hash.digest();
   }

   public static String getMD5Checksum(File file) throws IOException, NoSuchAlgorithmException {
      byte[] b = createChecksum(file);
      String result = "";

      for (int i = 0; i < b.length; i++) {
         result = result + Integer.toString((b[i] & 255) + 256, 16).substring(1);
      }

      return result;
   }

   public static final int makeCut(GridRandom random, int x) {
      double mean = x / 2.0;
      double sigma = mean / sigma_factor;
      int cut = getNormalCut(random, mean, sigma);
      // Clamp into [1, x-1] so a split always leaves at least one unit on
      // both sides. The previous version clamped the raw (pre-offset) cut
      // into [0, x-3] via limit(x - 2, cut) and then added 1, giving a
      // final range of [1, x-2] -- asymmetric, and one short of [1, x-1] on
      // the top end. Since the Gaussian is centered at x/2 with a fairly
      // tight spread (sigma = mean/sigma_factor), its mass mostly sat AT or
      // ABOVE that artificial ceiling for small/medium x, so draws piled up
      // against the clamp instead of spreading out: x=3 always produced
      // cut=1 (i.e. always (1,2), never (2,1)), x=4 always produced cut=2,
      // x=5 produced cut=3 over 99% of the time, etc. -- splits were
      // deterministic or near-deterministic and consistently lopsided in
      // the same direction, rather than the intended roughly-balanced
      // gaussian spread. That reliably peeled narrow 1-wide slivers off
      // recursive partitions, and slivers rarely match any real BUILD/
      // FILLER schematic (most need >=2 in both dimensions), so they'd
      // split again the same lopsided way and cascade down to the
      // guaranteed 1x1 fallback far more than intended.
      return limit(x, cut);
   }

   /**
    * Same as makeCut(random, x), but additionally keeps both sides of the
    * split at or above min. The road strip between the two pieces still
    * costs 1 unit, so a valid cut only exists when x >= 2*min + 1; callers
    * must check that (e.g. via a size comparison against 2*min + 1) before
    * calling this, since there's no sane single-int fallback if it's not
    * true -- min can't be honoured on both sides at once.
    */
   public static final int makeCut(GridRandom random, int x, int min) {
      double mean = x / 2.0;
      double sigma = mean / sigma_factor;
      int cut = getNormalCut(random, mean, sigma);
      return limit(x, cut, min);
   }

   private static final int limit(int size, int cut) {
      if (cut < 1) {
         return 1;
      } else if (cut > size - 1) {
         return size - 1;
      } else {
         return cut;
      }
   }

   private static final int limit(int size, int cut, int min) {
      int lower = Math.max(1, min);
      int upper = size - 1 - lower;
      if (cut < lower) {
         return lower;
      } else if (cut > upper) {
         return upper;
      } else {
         return cut;
      }
   }

   /**
    * Same idea as makeCut(random, x, min), for splits that do NOT insert a
    * road strip between the two resulting pieces -- unlike UrbanGrid's
    * grid-carving splits (which reserve 1 unit for a road, see limit(size,
    * cut, min) above), District.nextRecPartition()'s intra-district parcel
    * search just divides the space in two with nothing in between. Both
    * resulting pieces (cut, x - cut) land at or above min PROVIDED
    * x >= 2*min -- callers are responsible for clamping min down to
    * something x can actually support (e.g. min(desiredMin, x / 2)) before
    * calling this, since unlike the road-strip makeCut(random, x, min)
    * above, this has no way to reject an unsatisfiable request; it just
    * clamps into whatever range x allows.
    */
   public static final int makeCutNoRoad(GridRandom random, int x, int min) {
      double mean = x / 2.0;
      double sigma = mean / sigma_factor;
      int cut = getNormalCut(random, mean, sigma);
      return limitNoRoad(x, cut, min);
   }

   private static final int limitNoRoad(int size, int cut, int min) {
      int lower = Math.max(1, min);
      int upper = size - lower;
      if (cut < lower) {
         return lower;
      } else if (cut > upper) {
         return upper;
      } else {
         return cut;
      }
   }

   public static final int getNormalCut(GridRandom random, double mean, double sigma) {
      return (int)Math.round(mean + random.getRandomGaussian() * sigma);
   }

   public static final int square(int x) {
      return x * x;
   }

   public static int getRandomWeighted(List<Integer> odds, GridRandom rand) {
      int[] thresholds = new int[odds.size()];
      thresholds[0] = odds.get(0);

      for (int i = 1; i < odds.size(); i++) {
         thresholds[i] = thresholds[i - 1] + odds.get(i);
      }

      int random = rand.getRandomInt(thresholds[thresholds.length - 1]);

      for (int i = 0; i < thresholds.length; i++) {
         if (random < thresholds[i]) {
            return i;
         }
      }

      return 0;
   }

   /**
    * Scales each clip's base placement weight down based on nearby
    * "variant" placements (always) and, optionally, nearby exact
    * duplicates -- same groupId -- found within a caller-supplied radius
    * (used for the ring beyond the hard cloneRadius wall -- see
    * PluginConfig.getCloneRadiusSoftLimit()). Both terms use the same
    * distance-decay shape: a matching neighbour right on top of this
    * candidate counts close to fully, one out near the edge of its radius
    * counts for almost nothing, measured with the same infinity-norm
    * (chunk-square) distance getNeighbours() uses to build these sets in
    * the first place. Weight always floors at 1 -- this is a soft push,
    * never a hard block, for either term.
    *
    * cloneNeighbours/cloneRadius/clonePenalty only matter when the caller
    * wants a clone term at all; pass an empty set to disable it entirely
    * (e.g. the hard-block path handles exact duplicates within cloneRadius
    * by rejecting them outright instead, and doesn't want a second, weaker
    * discouragement stacked on a candidate about to be hard-rejected
    * anyway).
    *
    * Formula: base / (1 + variantEffectiveCount*variantPenalty +
    * cloneEffectiveCount*clonePenalty), where each effectiveCount sums,
    * per matching neighbour, max(0, 1 - distance/radius).
    */
   public static List<Integer> getPlacementWeights(
           Collection<Clipboard> clips, Cartesian2D center,
           Set<ClipboardParcel> variantNeighbours, int variantRadius, int variantPenalty,
           Set<ClipboardParcel> cloneNeighbours, int cloneRadius, int clonePenalty,
           boolean normalizeOddsOfAppearance
   ) {
      List<Integer> weights = new ArrayList<>(clips.size());

      // See PluginConfig.isNormalizeOddsOfAppearance(): rescales this
      // specific competing pool's raw oddsOfAppearance values onto a
      // consistent ~100-point scale (preserving their ratios exactly, so
      // plain no-penalty selection odds are unaffected) before the penalty
      // math below runs. Never touches SchematicConfig/CONFIG.json -- this
      // is purely a per-call, in-memory rescaling of "base" for this one
      // weighting pass.
      Map<Clipboard, Integer> effectiveBase = new HashMap<>();
      if (normalizeOddsOfAppearance) {
         int total = 0;
         for (Clipboard c : clips) {
            total += Math.max(0, c.getConfig().getOddsOfAppearance());
         }
         for (Clipboard c : clips) {
            int raw = c.getConfig().getOddsOfAppearance();
            effectiveBase.put(c, total <= 0 ? raw : Math.max(1, Math.round(100f * raw / total)));
         }
      } else {
         for (Clipboard c : clips) {
            effectiveBase.put(c, c.getConfig().getOddsOfAppearance());
         }
      }

      // Each neighbour's penalty term depends only on the neighbour, so it is worked out ONCE here and filed under the
      // variantKey / groupId it can match -- instead of every candidate rescanning every neighbour (candidates x neighbours,
      // which was the planner's hottest loop). The terms are filed in the neighbours' own iteration order and added back in
      // that order below, variant terms first and then clone terms, so the sums (and the weights) come out exactly as before.
      Map<String, List<Double>> variantTerms = null;
      if (variantNeighbours != null && !variantNeighbours.isEmpty()) {
         variantTerms = new HashMap<>();
         for (ClipboardParcel neighbour : variantNeighbours) {
            Clipboard neighbourClip = neighbour.getClipboard();
            if (neighbourClip == null) {
               continue;
            }
            String key = neighbourClip.getConfig().getVariantKey();
            if (key == null) {
               continue;
            }
            List<Double> terms = variantTerms.get(key);
            if (terms == null) {
               terms = new ArrayList<>(4);
               variantTerms.put(key, terms);
            }
            terms.add(falloff(center, neighbour, variantRadius) * variantPenalty);
         }
      }
      Map<String, List<Double>> cloneTerms = null;
      if (cloneNeighbours != null && !cloneNeighbours.isEmpty()) {
         cloneTerms = new HashMap<>();
         for (ClipboardParcel neighbour : cloneNeighbours) {
            Clipboard neighbourClip = neighbour.getClipboard();
            if (neighbourClip == null) {
               continue;
            }
            String key = neighbourClip.getGroupId();
            if (key == null) {
               continue;
            }
            List<Double> terms = cloneTerms.get(key);
            if (terms == null) {
               terms = new ArrayList<>(4);
               cloneTerms.put(key, terms);
            }
            terms.add(falloff(center, neighbour, cloneRadius) * clonePenalty);
         }
      }

      for (Clipboard c : clips) {
         int base = effectiveBase.get(c);
         if (base <= 0) {
            weights.add(base);
            continue;
         }

         double penaltyTerms = 0.0;

         if (variantTerms != null) {
            List<Double> terms = variantTerms.get(c.getConfig().getVariantKey());
            if (terms != null) {
               for (int i = 0; i < terms.size(); i++) {
                  penaltyTerms += terms.get(i);
               }
            }
         }

         if (cloneTerms != null) {
            List<Double> terms = cloneTerms.get(c.getGroupId());
            if (terms != null) {
               for (int i = 0; i < terms.size(); i++) {
                  penaltyTerms += terms.get(i);
               }
            }
         }

         int scaled = penaltyTerms <= 0.0 ? base : Math.max(1, (int) (base / (1 + penaltyTerms)));
         weights.add(scaled);
      }

      return weights;
   }

   private static double falloff(Cartesian2D center, ClipboardParcel neighbour, int radius) {
      if (radius <= 0) {
         return 1.0;
      }
      double distance = chebyshevDistance(center, neighbour);
      return Math.max(0.0, 1.0 - distance / radius);
   }

   private static double chebyshevDistance(Cartesian2D center, ClipboardParcel neighbour) {
      double neighbourCenterX = neighbour.getChunkX() + neighbour.getChunkSizeX() / 2.0;
      double neighbourCenterZ = neighbour.getChunkZ() + neighbour.getChunkSizeZ() / 2.0;
      return Math.max(Math.abs(neighbourCenterX - center.X), Math.abs(neighbourCenterZ - center.Y));
   }
}