package puregero.multipaper.externalserverprotocol;

import net.minecraft.core.Direction;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.Container;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.entity.HopperBlockEntity;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.bukkit.Bukkit;
import org.bukkit.craftbukkit.CraftWorld;
import puregero.multipaper.ExternalServerConnection;
import puregero.multipaper.MultiPaper;

import java.io.IOException;
import java.util.UUID;

public class AddItemToEntityContainerPacket extends ExternalServerPacket {

    private static final Logger LOGGER = LogManager.getLogger(AddItemToEntityContainerPacket.class.getSimpleName());

    private final String world;
    private final UUID uuid;
    private final int slot;
    private final ItemStack itemStack;

    public AddItemToEntityContainerPacket(Entity entity, int slot, ItemStack itemStack) {
        this.world = ((ServerLevel) entity.level()).convertable.getLevelId();
        this.uuid = entity.getUUID();
        this.slot = slot;
        this.itemStack = itemStack;
    }

    public AddItemToEntityContainerPacket(FriendlyByteBuf in) {
        world = in.readUtf();
        uuid = in.readUUID();
        slot = in.readByte();

        try {
            itemStack = ItemStack.of(MultiPaper.nbtFromBytes(in.readByteArray()));
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    @Override
    public void write(FriendlyByteBuf out) {
        out.writeUtf(world);
        out.writeUUID(uuid);
        out.writeByte(slot);

        try {
            out.writeByteArray(MultiPaper.nbtToBytes(itemStack.save(new CompoundTag())));
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    @Override
    public void handle(ExternalServerConnection connection) {
        MultiPaper.runSync(() -> {
            ServerLevel level = ((CraftWorld) Bukkit.getWorld(world)).getHandle();
            Entity entity = level.getEntity(uuid);

            if (entity instanceof Container container) {
                // We can assume the item is being added from the side as it's cross servers, so any side direction such as north will do
                ItemStack leftOver = HopperBlockEntity.addItem(null, container, itemStack, Direction.NORTH);
                if (!leftOver.isEmpty()) {
                    LOGGER.warn("There was a left over " + leftOver + " after adding an item to " + container);
                    ItemEntity item = new ItemEntity(level, entity.getX(), entity.getY(), entity.getZ(), leftOver);
                    level.addFreshEntity(item);
                }
            } else {
                LOGGER.warn("Tried to set a " + itemStack + " in slot " + slot + " in a non-existent entity in " + world + " with uuid " + uuid + ": " + entity);
            }
        });
    }
}
