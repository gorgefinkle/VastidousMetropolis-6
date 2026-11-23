package ch.k42.metropolis.plugin;

import ch.k42.metropolis.commands.CommandMetropolisLandmark;
import ch.k42.metropolis.commands.CommandMetropolisFreder;
import ch.k42.metropolis.commands.CommandMetropolisGrot;
import ch.k42.metropolis.commands.CommandMetropolisMaria;
import ch.k42.metropolis.grid.urbanGrid.farm.FarmPainter;
import ch.k42.metropolis.generator.ChunkyMemoryValve;
import ch.k42.metropolis.generator.LodCompletionSync;
import ch.k42.metropolis.generator.MetropolisGenerator;
import ch.k42.metropolis.generator.populators.PopulatorConfig;
import ch.k42.metropolis.grid.common.Factory;
import ch.k42.metropolis.grid.urbanGrid.clipboard.DeferredSchematicMetadata;
import ch.k42.metropolis.grid.urbanGrid.parcel.PlacedParcels;
import ch.k42.metropolis.grid.urbanGrid.provider.BranchGrowthDrain;
import ch.k42.metropolis.grid.urbanGrid.enums.ContextType;
import ch.k42.metropolis.grid.urbanGrid.clipboard.ClipboardProvider;
import ch.k42.metropolis.grid.urbanGrid.clipboard.ClipboardProviderDB;
import ch.k42.metropolis.grid.urbanGrid.context.ContextConfig;
import ch.k42.metropolis.minions.Minions;
import ch.k42.metropolis.minions.Nimmersatt;
import ch.k42.metropolis.minions.StrictMaterialAdapter;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import java.io.File;
import java.io.FileNotFoundException;
import java.nio.file.Files;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.world.ChunkLoadEvent;
import org.bukkit.event.world.ChunkPopulateEvent;
import org.bukkit.event.world.WorldLoadEvent;
import org.bukkit.command.PluginCommand;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.generator.ChunkGenerator;
import org.bukkit.plugin.java.JavaPlugin;

public class MetropolisPlugin extends JavaPlugin {
   // R30ea field: registered at enable, flushed periodically and at shutdown.
   private CityNameExporter cityNameExporter;
   private PopulatorConfig populatorConfig = new PopulatorConfig();
   private ContextConfig contextConfig = new ContextConfig();
   private ClipboardProvider clipboardProvider;

   private final List<MetropolisGenerator> generators = new CopyOnWriteArrayList<>();

   public void recordCityChunk(World world, int x, int z, ContextType type) {
      if (this.cityNameExporter != null) this.cityNameExporter.record(world, x, z, type);
   }

   public MetropolisGenerator generatorForWorld(World world) {
      for (MetropolisGenerator generator : this.generators) {
         if (generator.getWorld() == world) return generator;
      }
      return null;
   }

   public ChunkGenerator getDefaultWorldGenerator(String worldName, String id) {
      MetropolisGenerator generator = new MetropolisGenerator(this, worldName, this.clipboardProvider);
      this.generators.add(generator);
      return generator;
   }

   public void onDisable() {
      CommandMetropolisLandmark.saveOnShutdown(this);
      BranchGrowthDrain.shutdownNow();
      DeferredSchematicMetadata.shutdownNow();
      for (MetropolisGenerator generator : this.generators) {
         generator.saveDeferredQueue();
      }
      if (this.cityNameExporter != null) this.cityNameExporter.flush();
      super.onDisable();
   }

   private void loadJsonConfigs() {
      // Fresh Paper installations do not guarantee the plugin data directory
      // exists before onEnable(). Create it before writing either JSON file.
      File dataFolder = this.getDataFolder();
      if (!dataFolder.isDirectory() && !dataFolder.mkdirs() && !dataFolder.isDirectory()) {
         this.getLogger().severe("Cannot create plugin data folder: " + dataFolder.getAbsolutePath());
         return;
      }
      File contextsConfig = new File(this.getDataFolder().getPath() + "/contexts.json");
      File populatorsConfig = new File(this.getDataFolder().getPath() + "/populators.json");
      // Strict about materials (the ore list in populators.json): a bad name
      // is an error, never a silent null.
      Gson gson = new GsonBuilder().setPrettyPrinting().registerTypeAdapter(Material.class, new StrictMaterialAdapter()).create();

      try {
         if (!populatorsConfig.exists()) {
            String file = gson.toJson(this.populatorConfig);
            Files.write(populatorsConfig.toPath(), file.getBytes());
         } else {
            String json = new String(Files.readAllBytes(populatorsConfig.toPath()));
            json = Nimmersatt.friss(json);
            this.populatorConfig = gson.fromJson(json, PopulatorConfig.class);
         }

         if (!contextsConfig.exists()) {
            String file = gson.toJson(this.contextConfig);
            Files.write(contextsConfig.toPath(), file.getBytes());
         } else {
            String json = new String(Files.readAllBytes(contextsConfig.toPath()));
            json = Nimmersatt.friss(json);
            this.contextConfig = gson.fromJson(json, ContextConfig.class);
         }
      } catch (Exception e) {
         this.getLogger().severe("Could not read populators.json / contexts.json -- using the built-in defaults instead. " + e.getMessage());
      }
   }

   private void loadPluginConfig() {
      this.saveDefaultConfig();
      FileConfiguration configFile = this.getConfig();
      PluginConfig.loadFromFile(configFile);
   }

   private void loadClipboards() {
      try {
         this.clipboardProvider = Factory.getDefaultClipboardProvider();
         if (this.clipboardProvider == null) {
            System.out.println();
         }

         this.clipboardProvider.loadClips(this);
      } catch (ClipboardProviderDB.PluginNotFoundException e) {
         this.getLogger().throwing(this.getClass().getName(), "Failed to load clipboard provider: " + e.getMessage(), e);
         e.printStackTrace();
      } catch (FileNotFoundException e) {
         this.getLogger().throwing(this.getClass().getName(), "Failed to load clipboards: " + e.getMessage(), e);
         e.printStackTrace();
      }
   }

   public void onEnable() {
      LodCompletionSync.install(this);
      this.loadJsonConfigs();
      this.loadPluginConfig();
      this.cityNameExporter = new CityNameExporter(this.getLogger());
      this.getServer().getScheduler().runTaskTimer(this, () -> this.cityNameExporter.flush(), 200L, 200L);
      Minions.initBranchNodeLog(this.getDataFolder());
      this.loadClipboards();
      DeferredSchematicMetadata.resume(this);

      // Match R30ea's world-load resume hook for previously persisted schematics.
      this.getServer().getPluginManager().registerEvents(new Listener() {
         @EventHandler public void onWorldLoad(WorldLoadEvent event) {
            DeferredSchematicMetadata.resume(MetropolisPlugin.this);
            for (MetropolisGenerator generator : generators) {
               if (generator.getWorld() == event.getWorld()) generator.resumeDeferredQueue();
            }
         }
      }, this);

      // Match R30ea's chunk callbacks; only the generator for the same World receives a job.
      this.getServer().getPluginManager().registerEvents(new Listener() {
         @EventHandler public void onChunkLoad(ChunkLoadEvent event) {
            for (MetropolisGenerator generator : generators) {
               if (generator.getWorld() != event.getWorld()) continue;
               int x = event.getChunk().getX(), z = event.getChunk().getZ();
               PlacedParcels.of(generator).onChunkLoaded(x, z, generator);
               FarmPainter.reconnectFarmSeams(event.getChunk());
               schedulePendingGrowth(generator, event.getWorld(), x, z);
               break;
            }
         }
         @EventHandler public void onChunkPopulate(ChunkPopulateEvent event) {
            for (MetropolisGenerator generator : generators) {
               if (generator.getWorld() != event.getWorld()) continue;
               schedulePendingGrowth(generator, event.getWorld(), event.getChunk().getX(), event.getChunk().getZ());
               break;
            }
         }
      }, this);
      this.registerCommands();
      ChunkyMemoryValve.startFromPluginEnable();
      for (MetropolisGenerator generator : generators) generator.resumeDeferredQueue();
   }

   private void schedulePendingGrowth(MetropolisGenerator generator, World world, int x, int z) {
      if (generator.getDecayProvider() == null || !generator.getDecayProvider().hasPendingGrowthAt(x, z)) return;
      this.getServer().getScheduler().runTaskLater(this, () -> {
         if (world.isChunkLoaded(x, z) && generator.getDecayProvider() != null) {
            generator.getDecayProvider().onChunkLoaded(world, x, z);
         }
      }, 1L);
   }

   private void registerCommands() {
      PluginCommand cmd = this.getCommand("landmark");
      CommandMetropolisLandmark landmark = new CommandMetropolisLandmark(this);
      if (cmd != null) {
         cmd.setExecutor(landmark);
         cmd.setTabCompleter(landmark);
      }
      cmd = this.getCommand("metropolis");
      cmd.setExecutor(new CommandMetropolisMaria(this));
      cmd = this.getCommand("freder");
      cmd.setExecutor(new CommandMetropolisFreder(this));
      cmd = this.getCommand("grot");
      cmd.setExecutor(new CommandMetropolisGrot(this));
      cmd = this.getCommand("repopulate");
      cmd.setExecutor(new ch.k42.metropolis.commands.CommandMetropolisRepopulate(this));
      cmd = this.getCommand("creek");
      if (cmd != null) cmd.setExecutor(new ch.k42.metropolis.commands.CommandMetropolisCreek(this));
      cmd = this.getCommand("windfarm");
      if (cmd != null) cmd.setExecutor(new ch.k42.metropolis.commands.CommandMetropolisWindFarm());
      cmd = this.getCommand("citynames");
      if (cmd != null) cmd.setExecutor((sender, command, label, args) -> {
         if (args.length < 1 || args.length > 2 || !"export".equalsIgnoreCase(args[0])) {
            sender.sendMessage("Usage: /citynames export [world]");
            return true;
         }
         World world = args.length == 2 ? getServer().getWorld(args[1])
               : (sender instanceof Player p ? p.getWorld() : null);
         if (world == null) {
            sender.sendMessage("Specify a loaded world: /citynames export <world>");
            return true;
         }
         if (cityNameExporter == null) {
            sender.sendMessage("City export module is unavailable.");
            return true;
         }
         cityNameExporter.exportExisting(this, world, sender);
         return true;
      });
      cmd = this.getCommand("port");
      if (cmd != null) {
         ch.k42.metropolis.commands.CommandMetropolisPort port = new ch.k42.metropolis.commands.CommandMetropolisPort(this);
         cmd.setExecutor(port);
         cmd.setTabCompleter(port);
      }
   }

   public PopulatorConfig getPopulatorConfig() {
      return this.populatorConfig;
   }

   public ContextConfig getContextConfig() {
      return this.contextConfig;
   }
}