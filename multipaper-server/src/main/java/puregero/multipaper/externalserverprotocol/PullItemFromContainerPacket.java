package puregero.multipaper.externalserverprotocol;

import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.Container;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.Hopper;
import net.minecraft.world.level.block.entity.HopperBlockEntity;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.bukkit.Bukkit;
import org.bukkit.craftbukkit.CraftWorld;
import puregero.multipaper.ExternalServerConnection;
import puregero.multipaper.MultiPaper;

import java.io.IOException;

public class PullItemFromContainerPacket extends ExternalServerPacket {

    private static final Logger LOGGER = LogManager.getLogger(PullItemFromContainerPacket.class.getSimpleName());

    private final String world;
    private final BlockPos source;
    private final int slot;
    private final ItemStack itemStack;
    private final BlockPos destination;

    public PullItemFromContainerPacket(BlockEntity blockEntity, int slot, ItemStack itemStack, Hopper destinationHopper) {
        this.world = ((ServerLevel) blockEntity.getLevel()).convertable.getLevelId();
        this.source = blockEntity.getBlockPos();
        this.slot = slot;
        this.itemStack = itemStack;
        this.destination = new BlockPos((int) destinationHopper.getLevelX(), (int) destinationHopper.getLevelY(), (int) destinationHopper.getLevelZ());
    }

    public PullItemFromContainerPacket(FriendlyByteBuf in) {
        this.world = in.readUtf();
        this.source = in.readBlockPos();
        this.slot = in.readByte();

        try {
            this.itemStack = ItemStack.of(MultiPaper.nbtFromBytes(in.readByteArray()));
        } catch (IOException e) {
            throw new RuntimeException(e);
        }

        this.destination = in.readBlockPos();
    }

    @Override
    public void write(FriendlyByteBuf out) {
        out.writeUtf(this.world);
        out.writeBlockPos(this.source);
        out.writeByte(this.slot);

        try {
            out.writeByteArray(MultiPaper.nbtToBytes(this.itemStack.save(new CompoundTag())));
        } catch (IOException e) {
            throw new RuntimeException(e);
        }

        out.writeBlockPos(this.destination);
    }

    @Override
    public void handle(ExternalServerConnection connection) {
        MultiPaper.runSync(() -> {
            ServerLevel level = ((CraftWorld) Bukkit.getWorld(this.world)).getHandle();
            Container container = HopperBlockEntity.getContainerAt(level, this.source);

            if (container == null) {
                LOGGER.warn("Tried to take a " + this.itemStack + " in slot " + this.slot + " in a non-existent container at " + this.world + " " + this.source);
            } else if (!ItemStack.isSameItemSameTags(this.itemStack, container.getItem(this.slot))) {
                LOGGER.warn("Tried to take a " + this.itemStack + " in slot " + this.slot + " from container at " + this.world + " " + this.source + ", but it is a " + container.getItem(this.slot));
            } else {
                ItemStack origItemStack = container.getItem(this.slot);
                int count = Math.min(this.itemStack.getCount(), origItemStack.getCount());
                ItemStack pulledItemStack = origItemStack.copy(true);
                pulledItemStack.setCount(count);
                origItemStack.setCount(origItemStack.getCount() - count);

                HopperBlockEntity.ignoreTileUpdates = true;
                container.setItem(this.slot, origItemStack);
                HopperBlockEntity.ignoreTileUpdates = false;
                container.setChanged();

                connection.send(new AddItemToContainerPacket(level, this.destination, 0, pulledItemStack));
            }
        });
    }
}
