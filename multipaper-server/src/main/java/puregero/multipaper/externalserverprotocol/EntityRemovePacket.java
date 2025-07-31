package puregero.multipaper.externalserverprotocol;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.entity.Visibility;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.bukkit.Bukkit;
import org.bukkit.craftbukkit.CraftWorld;
import org.bukkit.craftbukkit.scheduler.CraftScheduler;
import puregero.multipaper.ExternalServerConnection;
import puregero.multipaper.MultiPaper;
import puregero.multipaper.MultiPaperEntitiesHandler;

import java.util.HashMap;
import java.util.UUID;

public class EntityRemovePacket extends ExternalServerPacket {

    private static final Logger LOGGER = LogManager.getLogger(EntityRemovePacket.class.getSimpleName());

    public static final HashMap<UUID, Entity.RemovalReason> removedEntities = new HashMap<>();

    private final String world;
    private final UUID uuid;
    private final boolean unloadedWithPlayer;

    public EntityRemovePacket(Entity entity, boolean unloadedWithPlayer) {
        this(((ServerLevel) entity.level()).convertable.getLevelId(), entity.getUUID(), unloadedWithPlayer);
    }

    public EntityRemovePacket(String world, UUID uuid, boolean unloadedWithPlayer) {
        this.world = world;
        this.uuid = uuid;
        this.unloadedWithPlayer = unloadedWithPlayer;
    }

    public EntityRemovePacket(FriendlyByteBuf in) {
        this.world = in.readUtf();
        this.uuid = in.readUUID();
        this.unloadedWithPlayer = in.readBoolean();
    }

    @Override
    public void write(FriendlyByteBuf out) {
        out.writeUtf(world);
        out.writeUUID(uuid);
        out.writeBoolean(this.unloadedWithPlayer);
    }

    @Override
    public void handle(ExternalServerConnection connection) {
        MultiPaper.runSync(this::removeEntity);
    }

    private void removeEntity() {
        ServerLevel level = ((CraftWorld) Bukkit.getWorld(world)).getHandle();

        Entity entity = level.getEntityLookup().getEntityIgnoringAccessible(uuid);

        if (entity != null) {
            MultiPaperEntitiesHandler.removingEntity = true;
            Entity.RemovalReason reason = entity instanceof Player ? Entity.RemovalReason.KILLED : Entity.RemovalReason.DISCARDED;
            if (this.unloadedWithPlayer) reason = Entity.RemovalReason.UNLOADED_WITH_PLAYER;

            entity.setRemoved(reason);

            MultiPaperEntitiesHandler.removingEntity = false;
        } else if (!this.unloadedWithPlayer) {
            setEntityRemoved(uuid, Entity.RemovalReason.DISCARDED);
        }
    }

    public static void setEntityRemoved(UUID uuid, Entity.RemovalReason reason) {
        setEntityRemoved(uuid, reason, 300);
    }

    public static void setEntityRemoved(UUID uuid, Entity.RemovalReason reason, int durationInTicks) {
        if (reason == Entity.RemovalReason.UNLOADED_WITH_PLAYER) return;
        removedEntities.put(uuid, reason);
        ((CraftScheduler) Bukkit.getScheduler()).scheduleInternalTask(() -> {
            removedEntities.remove(uuid, reason);
        }, durationInTicks, "EntityRemovePacket-removeEntryFromRemovedEntities");
    }
}
