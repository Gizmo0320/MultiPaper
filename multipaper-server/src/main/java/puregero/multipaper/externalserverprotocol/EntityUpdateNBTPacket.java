package puregero.multipaper.externalserverprotocol;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.level.entity.Visibility;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.bukkit.Bukkit;
import org.bukkit.World;
import org.bukkit.craftbukkit.CraftWorld;
import puregero.multipaper.ExternalServerConnection;
import puregero.multipaper.MultiPaper;

import java.io.IOException;
import java.util.UUID;

public class EntityUpdateNBTPacket extends ExternalServerPacket {

    private static final Logger LOGGER = LogManager.getLogger(EntityUpdateNBTPacket.class.getSimpleName());

    private final String world;
    private final UUID uuid;
    private final byte[] data;

    public EntityUpdateNBTPacket(Entity entity) {
        this.world = ((ServerLevel) entity.level()).convertable.getLevelId();
        this.uuid = entity.getUUID();

        CompoundTag tag = new CompoundTag();

        entity.isSyncing = true;
        entity.save(tag);
        entity.isSyncing = false;

        if (tag.getAllKeys().isEmpty()) {
            new Exception("Sending an empty entity " + entity).printStackTrace();
        }

        try {
            this.data = MultiPaper.nbtToBytes(tag);
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    public EntityUpdateNBTPacket(FriendlyByteBuf in) {
        this.world = in.readUtf();
        this.uuid = in.readUUID();
        this.data = in.readByteArray();
    }

    @Override
    public void write(FriendlyByteBuf out) {
        out.writeUtf(world);
        out.writeUUID(uuid);
        out.writeByteArray(data);
    }

    @Override
    public void handle(ExternalServerConnection connection) {
        MultiPaper.runSync(() -> {
            try {
                World bukkitWorld = Bukkit.getWorld(world);

                if (bukkitWorld instanceof CraftWorld craftWorld) {
                    ServerLevel level = craftWorld.getHandle();
                    CompoundTag tag = MultiPaper.nbtFromBytes(data);
                    loadEntity(level, tag, uuid);
                }
            } catch (IOException e) {
                e.printStackTrace();
            }
        });
    }

    public static Entity loadEntity(ServerLevel level, CompoundTag tag, UUID uuid) {
        Entity.RemovalReason removalReason = EntityRemovePacket.removedEntities.get(uuid);
        if (removalReason != null && removalReason.shouldDestroy()) {
            // We've already removed this entity. This is likely a race condition, so don't recreate the entity.
            return null;
        }

        Entity entity = level.getEntityLookup().getEntityIgnoringAccessible(uuid);

        if (entity == null) {
            entity = EntityType.loadEntityRecursive(tag, level, entity2 -> {
                if (level.getEntityLookup().isChunkLoaded(entity2.chunkPosition())) {
                    level.getEntityLookup().addNewEntity(entity2);
                    if (entity2 instanceof Mob mob) mob.restoreLeashFromSave();
                    return entity2;
                } else {
                    EntityRemovePacket.setEntityRemoved(uuid, Entity.RemovalReason.UNLOADED_TO_CHUNK, 20);
                    LOGGER.warn("Tried to create an entity from nbt, but the entities for that chunk aren't loaded: " + entity2); // Warnings are there for a reason
                    return null;
                }
            });
        } else if (entity instanceof ServerPlayer player) {
            new Exception("Tried to update the nbt of player " + player.getScoreboardName() + " to " + tag).printStackTrace();
        } else {
            entity.load(tag);
            if (entity.tracker != null) {
                entity.tracker.serverEntity.teleportDelay = 10000;
            }
        }

        if (tag.contains("Passengers", 9)) {
            ListTag nbttaglist = tag.getList("Passengers", 10);

            for (int i = 0; i < nbttaglist.size(); ++i) {
                CompoundTag passengerTag = nbttaglist.getCompound(i);
                Entity passenger = loadEntity(level, passengerTag, passengerTag.getUUID("UUID"));

                if (passenger != null) {
                    passenger.startRiding(entity, true);
                }
            }
        }

        if (entity instanceof Mob mob) mob.restoreLeashFromSave();

        return entity;
    }
}
