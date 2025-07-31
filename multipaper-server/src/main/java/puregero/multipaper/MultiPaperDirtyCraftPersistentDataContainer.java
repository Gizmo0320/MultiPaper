package puregero.multipaper;

import io.papermc.paper.chunk.system.scheduling.NewChunkHolder;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import org.bukkit.NamespacedKey;
import org.bukkit.craftbukkit.entity.CraftEntity;
import org.bukkit.craftbukkit.persistence.CraftPersistentDataContainer;
import org.bukkit.craftbukkit.persistence.CraftPersistentDataTypeRegistry;
import org.bukkit.persistence.PersistentDataType;
import org.jetbrains.annotations.NotNull;
import puregero.multipaper.externalserverprotocol.EntityPersistentDataUpdatePacket;

import java.util.HashSet;
import java.util.Set;

public class MultiPaperDirtyCraftPersistentDataContainer extends CraftPersistentDataContainer {
    private static final Set<MultiPaperDirtyCraftPersistentDataContainer> dirtyContainers = new HashSet<>();

    public static void tick() {
        for (MultiPaperDirtyCraftPersistentDataContainer container : dirtyContainers) {
            Entity entity = container.entity.getHandle();

            NewChunkHolder newChunkHolder = MultiPaper.getChunkHolder((ServerLevel) entity.level(), entity.chunkPosition().x, entity.chunkPosition().z);
            if (newChunkHolder != null) {
                MultiPaper.broadcastPacketToExternalServers(newChunkHolder.externalEntitiesSubscribers, () -> new EntityPersistentDataUpdatePacket(entity, container, container.modifiedKeys));
            }

            container.modifiedKeys.clear();
        }

        dirtyContainers.clear();
    }


    private final CraftEntity entity;
    private final Set<NamespacedKey> modifiedKeys = new HashSet<>();

    public MultiPaperDirtyCraftPersistentDataContainer(CraftEntity entity, CraftPersistentDataTypeRegistry dataTypeRegistry) {
        super(dataTypeRegistry);
        this.entity = entity;
    }

    @Override
    public <T, Z> void set(@NotNull NamespacedKey key, @NotNull PersistentDataType<T, Z> type, @NotNull Z value) {
        super.set(key, type, value);
        this.setDirty(key);
    }

    @Override
    public void remove(@NotNull NamespacedKey key) {
        super.remove(key);
        this.setDirty(key);
    }

    private void setDirty(NamespacedKey key) {
        if (!EntityPersistentDataUpdatePacket.modifyingPersistentData) {
            modifiedKeys.add(key);
            dirtyContainers.add(this);
        }
    }

}
