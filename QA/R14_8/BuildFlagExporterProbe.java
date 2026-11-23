package ch.k42.metropolis.grid.urbanGrid.parcel;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.logging.Logger;

/** Restart/duplicate/partial-tail probe for the JSONL handoff. */
public final class BuildFlagExporterProbe {
   private static void check(boolean condition, String message) {
      if (!condition) throw new AssertionError(message);
   }

   public static void main(String[] args) throws Exception {
      check(MetropolisBuildFlagExporter.isEligibleBuild(
              ch.k42.metropolis.grid.urbanGrid.enums.SchematicType.BUILD,
              ch.k42.metropolis.grid.urbanGrid.enums.ContextType.RESIDENTIAL, false),
              "ordinary residential BUILD should export");
      check(!MetropolisBuildFlagExporter.isEligibleBuild(
              ch.k42.metropolis.grid.urbanGrid.enums.SchematicType.BUILD,
              ch.k42.metropolis.grid.urbanGrid.enums.ContextType.RESIDENTIAL, true),
              "forced landmark BUILD must not export, even with fallback district context");
      check(!MetropolisBuildFlagExporter.isEligibleBuild(
              ch.k42.metropolis.grid.urbanGrid.enums.SchematicType.BUILD,
              ch.k42.metropolis.grid.urbanGrid.enums.ContextType.LANDMARK, false),
              "explicit LANDMARK context must not export");
      check(!MetropolisBuildFlagExporter.isEligibleBuild(
              ch.k42.metropolis.grid.urbanGrid.enums.SchematicType.BUILD,
              ch.k42.metropolis.grid.urbanGrid.enums.ContextType.PARK, false),
              "PARK BUILD must not export");
      for (ch.k42.metropolis.grid.urbanGrid.enums.SchematicType type :
              ch.k42.metropolis.grid.urbanGrid.enums.SchematicType.values()) {
         if (type != ch.k42.metropolis.grid.urbanGrid.enums.SchematicType.BUILD) {
            check(!MetropolisBuildFlagExporter.isEligibleBuild(type,
                    ch.k42.metropolis.grid.urbanGrid.enums.ContextType.RESIDENTIAL, false),
                    type + " must not export");
         }
      }

      Path temp = Files.createTempDirectory("metro-build-flags-qa");
      UUID world = UUID.fromString("7d0b8ca1-8b65-4bc8-aac9-c8ddf5d5be11");
      MetropolisBuildFlagExporter.Footprint footprint =
              new MetropolisBuildFlagExporter.Footprint(160, 191, 63, 78, -96, -65);
      MetropolisBuildFlagExporter.Reference reference = new MetropolisBuildFlagExporter.Reference(175, -81);
      String first = MetropolisBuildFlagExporter.buildingId(world, "HIGHRISE", "schematic-a", footprint);
      String second = MetropolisBuildFlagExporter.buildingId(world, "RESIDENTIAL", "schematic-b",
              new MetropolisBuildFlagExporter.Footprint(192, 223, 63, 75, -96, -65));

      MetropolisBuildFlagExporter initial = new MetropolisBuildFlagExporter(temp, Logger.getLogger("build-flags-probe"));
      initial.export(world, first, "HIGHRISE", footprint, reference,
              List.of(new MetropolisBuildFlagExporter.InteriorCandidate(170, 68, -80, true, true)));
      initial.export(world, first, "HIGHRISE", footprint, reference,
              List.of(new MetropolisBuildFlagExporter.InteriorCandidate(170, 68, -80, true, true)));
      initial.export(world, second, "RESIDENTIAL",
              new MetropolisBuildFlagExporter.Footprint(192, 223, 63, 75, -96, -65),
              new MetropolisBuildFlagExporter.Reference(207, -81), List.of());
      initial.close();

      Path file = temp.resolve("deadstreets-build-flags-v1").resolve(world.toString()).resolve("buildings.jsonl");
      check(Files.exists(file), "JSONL file was not created");
      check(Files.readAllLines(file).size() == 2, "duplicate id must write exactly once");

      Files.writeString(file, "{\"schemaVersion\":1,\"buildingId\":\"interrupted", StandardCharsets.UTF_8,
              java.nio.file.StandardOpenOption.APPEND);
      String third = MetropolisBuildFlagExporter.buildingId(world, "INDUSTRIAL", "schematic-c",
              new MetropolisBuildFlagExporter.Footprint(224, 239, 63, 72, -96, -81));
      MetropolisBuildFlagExporter restarted = new MetropolisBuildFlagExporter(temp, Logger.getLogger("build-flags-probe"));
      restarted.export(world, first, "HIGHRISE", footprint, reference, List.of());
      restarted.export(world, third, "INDUSTRIAL",
              new MetropolisBuildFlagExporter.Footprint(224, 239, 63, 72, -96, -81),
              new MetropolisBuildFlagExporter.Reference(231, -89), List.of());
      restarted.close();

      List<String> lines = Files.readAllLines(file, StandardCharsets.UTF_8);
      check(lines.size() == 3, "restart and incomplete-tail recovery should leave three complete records");
      Set<String> ids = new HashSet<>();
      for (String line : lines) {
         check(line.endsWith("}") && !line.contains("interrupted"), "partial JSONL tail leaked into file");
         JsonObject record = JsonParser.parseString(line).getAsJsonObject();
         check(record.get("schemaVersion").getAsInt() == 1, "schema version missing");
         check(record.get("worldId").getAsString().equals(world.toString()), "world UUID mismatch");
         ids.add(record.get("buildingId").getAsString());
      }
      check(ids.size() == 3 && ids.contains(first) && ids.contains(second) && ids.contains(third),
              "records were duplicated or lost across restart");
      System.out.println("BuildFlagExporterProbe: PASS (JSONL, idempotency, restart recovery, partial tail)");
   }
}
