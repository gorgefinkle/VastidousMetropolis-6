package ch.k42.metropolis.grid.urbanGrid.clipboard;

import com.destroystokyo.paper.profile.PlayerProfile;
import com.destroystokyo.paper.profile.ProfileProperty;
import com.sk89q.worldedit.world.block.BaseBlock;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.UUID;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.BlockState;
import org.bukkit.block.Skull;
import org.enginehub.linbus.tree.LinCompoundTag;
import org.enginehub.linbus.tree.LinIntArrayTag;
import org.enginehub.linbus.tree.LinListTag;
import org.enginehub.linbus.tree.LinStringTag;
import org.enginehub.linbus.tree.LinTag;

/** R30ea legacy and modern player-head NBT compatibility, recovered from the original class. */
public final class SkinnedHeadPlacement {
    private SkinnedHeadPlacement() {}
    private static LinCompoundTag compound(LinCompoundTag tag, String key) {
        if (tag == null) return null;
        LinTag<?> value = tag.value().get(key);
        return value instanceof LinCompoundTag c ? c : null;
    }
    private static String string(LinCompoundTag tag, String key) {
        if (tag == null) return null;
        LinTag<?> value = tag.value().get(key);
        return value instanceof LinStringTag s ? s.value() : null;
    }
    private static UUID uuid(LinCompoundTag tag) {
        if (tag == null) return null;
        LinTag<?> id = tag.value().get("id");
        if (id instanceof LinIntArrayTag arr && arr.value().length == 4) {
            int[] a = arr.value();
            long msb = ((long) a[0] << 32) | ((long) a[1] & 0xffffffffL);
            long lsb = ((long) a[2] << 32) | ((long) a[3] & 0xffffffffL);
            return new UUID(msb, lsb);
        }
        if (id instanceof LinStringTag str) {
            try { return UUID.fromString(str.value()); }
            catch (IllegalArgumentException ignored) { }
        }
        return null;
    }
    public static Skin read(BaseBlock block) {
        if (block == null || !block.hasNbtData()) return null;
        return readNbt(block.getNbt());
    }
    public static Skin readNbt(LinCompoundTag tag) {
        LinCompoundTag profile = compound(tag, "profile");
        if (profile == null) profile = compound(tag, "SkullOwner");
        if (profile == null) profile = compound(tag, "Owner");
        if (profile == null) return null;
        String name = string(profile, "name");
        if (name == null) name = string(profile, "Name");
        LinTag<?> properties = profile.value().get("properties");
        if (properties == null) properties = profile.value().get("Properties");
        String texture = null, signature = null;
        if (properties instanceof LinListTag<?> list) {
            for (LinTag<?> entry : list.value()) {
                if (!(entry instanceof LinCompoundTag c)) continue;
                if (!"textures".equals(string(c, "name"))) continue;
                texture = string(c, "value");
                signature = string(c, "signature");
                break;
            }
        } else if (properties instanceof LinCompoundTag c) {
            LinTag<?> textures = c.value().get("textures");
            if (textures instanceof LinListTag<?> list) {
                for (LinTag<?> entry : list.value()) {
                    if (!(entry instanceof LinCompoundTag t)) continue;
                    texture = string(t, "Value");
                    if (texture == null) texture = string(t, "value");
                    signature = string(t, "Signature");
                    if (signature == null) signature = string(t, "signature");
                    break;
                }
            }
        }
        if (texture == null || texture.isEmpty()) return null;
        try {
            String decoded = new String(Base64.getDecoder().decode(texture), StandardCharsets.UTF_8);
            if (!decoded.contains("textures.minecraft.net/texture/")) return null;
        } catch (IllegalArgumentException bad) { return null; }
        UUID owner = uuid(profile);
        if (owner == null) owner = UUID.nameUUIDFromBytes(texture.getBytes(StandardCharsets.UTF_8));
        return new Skin(owner, name == null || name.isBlank() ? null : name, texture, signature);
    }
    public static void restore(World world, int x, int y, int z, Skin skin) {
        if (skin == null || !skin.valid()) return;
        Block block = world.getBlockAt(x, y, z);
        if (block.getType() != Material.PLAYER_HEAD && block.getType() != Material.PLAYER_WALL_HEAD) return;
        BlockState state = block.getState();
        if (!(state instanceof Skull skull)) return;
        PlayerProfile profile = Bukkit.createProfileExact(skin.uuid(), skin.name());
        profile.setProperty(skin.signature() == null || skin.signature().isBlank()
                ? new ProfileProperty("textures", skin.texture())
                : new ProfileProperty("textures", skin.texture(), skin.signature()));
        skull.setPlayerProfile(profile);
        skull.update(false, false);
    }
    public record Skin(UUID uuid, String name, String texture, String signature) {
        public boolean valid() { return texture != null && !texture.isEmpty(); }
    }
}
