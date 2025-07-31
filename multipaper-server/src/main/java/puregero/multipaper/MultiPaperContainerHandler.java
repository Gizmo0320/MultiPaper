package puregero.multipaper;

import io.papermc.paper.chunk.system.scheduling.NewChunkHolder;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.CompoundContainer;
import net.minecraft.world.Container;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.ComposterBlock;
import net.minecraft.world.level.block.entity.BlockEntity;
import puregero.multipaper.externalserverprotocol.AddItemToContainerPacket;
import puregero.multipaper.externalserverprotocol.AddItemToEntityContainerPacket;

public class MultiPaperContainerHandler {
    public static BlockEntity getPrimaryChest(CompoundContainer compoundContainer) {
        BlockEntity chest1 = (BlockEntity) compoundContainer.container1;
        BlockEntity chest2 = (BlockEntity) compoundContainer.container2;

        if (chest1.getBlockPos().getX() > chest2.getBlockPos().getX()) {
            return chest1;
        } else if (chest2.getBlockPos().getX() > chest1.getBlockPos().getX()) {
            return chest2;
        } else if (chest1.getBlockPos().getZ() > chest2.getBlockPos().getZ()) {
            return chest1;
        } else {
            return chest2;
        }
    }

    public static boolean increaseItemExternal(Container to, int slot, ItemStack stack, int count) {
        if (to instanceof Entity entity) {
            NewChunkHolder newChunkHolder = MultiPaper.getChunkHolder(entity);
            if (MultiPaper.isChunkExternal(newChunkHolder)) {
                stack = stack.copy();
                stack.setCount(count);
                newChunkHolder.externalOwner.getConnection().send(new AddItemToEntityContainerPacket(entity, slot, stack));

                return true;
            } else {
                return false;
            }
        }

        if (to instanceof ComposterBlock.InputContainer || to instanceof ComposterBlock.OutputContainer || to instanceof ComposterBlock.EmptyContainer) {
            // It doesn't really matter if we modify composters across servers (which might even be impossible anyways)
            return false;
        }

        BlockEntity block;

        if (to instanceof CompoundContainer compoundContainer) {
            block = getPrimaryChest(compoundContainer);
        } else {
            block = (BlockEntity) to;
        }

        NewChunkHolder newChunkHolder = MultiPaper.getChunkHolder((ServerLevel) block.getLevel(), block.getBlockPos());
        if (MultiPaper.isChunkExternal(newChunkHolder)) {
            stack = stack.copy();
            stack.setCount(count);
            newChunkHolder.externalOwner.getConnection().send(new AddItemToContainerPacket(block, slot, stack));

            return true;
        } else {
            return false;
        }
    }
}
