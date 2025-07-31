package puregero.multipaper.externalserverprotocol;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.Container;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.HopperBlockEntity;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.bukkit.Bukkit;
import org.bukkit.craftbukkit.CraftWorld;
import puregero.multipaper.ExternalServerConnection;
import puregero.multipaper.MultiPaper;

import java.io.IOException;

public class AddItemToContainerPacket extends ExternalServerPacket {

    private static final Logger LOGGER = LogManager.getLogger(AddItemToContainerPacket.class.getSimpleName());

    private final String world;
    private final BlockPos pos;
    private final int slot;
    private final ItemStack itemStack;

    public AddItemToContainerPacket(BlockEntity blockEntity, int slot, ItemStack itemStack) {
        this((ServerLevel) blockEntity.getLevel(), blockEntity.getBlockPos(), slot, itemStack);
    }

    public AddItemToContainerPacket(ServerLevel serverLevel, BlockPos blockPos, int slot, ItemStack itemStack) {
        this.world = serverLevel.convertable.getLevelId();
        this.pos = blockPos;
        this.slot = slot;
        this.itemStack = itemStack;
    }

    public AddItemToContainerPacket(FriendlyByteBuf in) {
        world = in.readUtf();
        pos = BlockPos.of(in.readLong());
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
        out.writeLong(pos.asLong());
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
            Container container = HopperBlockEntity.getContainerAt(level, pos);

            if (container == null) {
                LOGGER.warn("Tried to set a " + itemStack + " in slot " + slot + " in a non-existant container at " + world + " " + pos);
            } else {
                // We can assume the item is being added from the side as it's cross servers, so any side direction such as north will do
                ItemStack leftOver = HopperBlockEntity.addItem(null, container, itemStack, Direction.NORTH);
                if (!leftOver.isEmpty()) {
                    LOGGER.warn("There was a left over " + leftOver + " after adding an item to " + container.getClass().getSimpleName() + "@" + world + pos);
                    ItemEntity item = new ItemEntity(level, pos.getX() + 0.5, pos.getY() + 1, pos.getZ() + 0.5, leftOver);
                    level.addFreshEntity(item);
                }
            }
        });
    }
}
