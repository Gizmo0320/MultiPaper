package puregero.multipaper.externalserverprotocol;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import org.bukkit.Bukkit;
import org.bukkit.NamespacedKey;
import org.bukkit.World;
import org.bukkit.craftbukkit.CraftWorld;
import org.bukkit.craftbukkit.entity.CraftEntity;
import org.bukkit.craftbukkit.persistence.CraftPersistentDataContainer;
import puregero.multipaper.ExternalServerConnection;
import puregero.multipaper.MultiPaper;

import java.io.IOException;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

public class EntityPersistentDataUpdatePacket extends ExternalServerPacket {
    private static final CompoundTag NULL_TAG = new CompoundTag();
    static {
        NULL_TAG.putBoolean("isNullTag", true);
    }

    public static boolean modifyingPersistentData = false;

    private final UUID world;
    private final UUID uuid;
    private final byte[] data;

    public EntityPersistentDataUpdatePacket(Entity entity, CraftPersistentDataContainer container, Set<NamespacedKey> keys) {
        this.world = ((ServerLevel) entity.level()).uuid;
        this.uuid = entity.getUUID();

        CompoundTag tag = new CompoundTag();
        for (NamespacedKey key : keys) {
            Tag value = container.getRaw().getOrDefault(key.toString(), NULL_TAG);
            tag.put(key.toString(), value);
        }

        try {
            this.data = MultiPaper.nbtToBytes(tag);
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    public EntityPersistentDataUpdatePacket(FriendlyByteBuf in) {
        this.world = in.readUUID();
        this.uuid = in.readUUID();
        this.data = in.readByteArray();
    }

    @Override
    public void write(FriendlyByteBuf out) {
        out.writeUUID(world);
        out.writeUUID(uuid);
        out.writeByteArray(data);
    }

    @Override
    public void handle(ExternalServerConnection connection) {
        MultiPaper.runSync(() -> {
            try {
                modifyingPersistentData = true;
                World bukkitWorld = Bukkit.getWorld(world);

                if (bukkitWorld instanceof CraftWorld craftWorld) {
                    ServerLevel level = craftWorld.getHandle();
                    CompoundTag tag = MultiPaper.nbtFromBytes(data);
                    Entity entity = level.getEntityLookup().getEntityIgnoringAccessible(uuid);
                    if (entity != null) {
                        CraftEntity craftEntity = entity.getBukkitEntity();
                        for (Map.Entry<String, Tag> entry : tag.tags.entrySet()) {
                            if (Objects.equals(entry.getValue(), NULL_TAG)) {
                                craftEntity.getPersistentDataContainer().remove(NamespacedKey.fromString(entry.getKey()));
                            } else {
                                craftEntity.getPersistentDataContainer().put(entry.getKey(), entry.getValue());
                            }
                        }
                    }
                }
                modifyingPersistentData = false;
            } catch (IOException e) {
                e.printStackTrace();
            }
        });
    }
}
