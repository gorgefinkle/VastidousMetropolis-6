package ch.k42.metropolis.minions;

import com.google.gson.JsonSyntaxException;
import com.google.gson.TypeAdapter;
import com.google.gson.stream.JsonReader;
import com.google.gson.stream.JsonToken;
import com.google.gson.stream.JsonWriter;
import java.io.IOException;
import org.bukkit.Material;

/**
 * Reads a Material from JSON, and refuses anything that is not a real modern
 * material.
 *
 * Gson's built-in enum handling does the opposite of what we want here: a name
 * that is not a constant of the enum -- including every pre-1.13 name such as
 * THIN_GLASS or MOB_SPAWNER -- silently becomes {@code null}. A null Material
 * in a set or an ore list is then ignored or crashes much later, far from the
 * file that caused it. This adapter fails right there instead, naming the
 * value, so a bad config is rejected loudly and never produces a null.
 *
 * Used for every Gson-read config that holds materials: the per-schematic
 * CONFIG.json files and populators.json.
 */
public final class StrictMaterialAdapter extends TypeAdapter<Material> {
   @Override
   public void write(JsonWriter out, Material material) throws IOException {
      if (material == null) {
         out.nullValue();
      } else {
         out.value(material.name());
      }
   }

   @Override
   public Material read(JsonReader in) throws IOException {
      if (in.peek() == JsonToken.NULL) {
         in.nextNull();
         throw new JsonSyntaxException("a material entry is null (at " + in.getPath() + ")");
      }
      String raw = in.nextString();
      try {
         return Mats.require(raw, "at " + in.getPath());
      } catch (IllegalArgumentException e) {
         throw new JsonSyntaxException(e.getMessage());
      }
   }
}
