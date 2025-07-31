package puregero.multipaper.externalserverprotocol;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import puregero.multipaper.ExternalServerConnection;
import puregero.multipaper.MultiPaper;
import puregero.multipaper.MultiPaperInventoryHandler;

import java.io.IOException;
import java.util.UUID;

public class PlayerInventoryUpdatePacket extends ExternalServerPacket {

    private static final Logger LOGGER = LogManager.getLogger(PlayerInventoryUpdatePacket.class.getSimpleName());

    private final UUID uuid;
    private final String component;
    private final CompoundTag tag;

    public PlayerInventoryUpdatePacket(ServerPlayer player, String component, CompoundTag tag) {
        this.uuid = player.getUUID();
        this.component = component;
        this.tag = tag;
    }

    public PlayerInventoryUpdatePacket(FriendlyByteBuf in) {
        uuid = in.readUUID();
        component = in.readUtf();

        try {
            tag = MultiPaper.nbtFromBytes(in.readByteArray());
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    @Override
    public void write(FriendlyByteBuf out) {
        out.writeUUID(uuid);
        out.writeUtf(component);

        try {
            out.writeByteArray(MultiPaper.nbtToBytes(tag));
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    @Override
    public void handle(ExternalServerConnection connection) {
        MultiPaper.runSync(() -> {
            ServerPlayer player = MinecraftServer.getServer().getPlayerList().getPlayer(uuid);

            if (player == null) {
                LOGGER.warn("Tried to update the inventory of a non-existent player uuid " + uuid);
                return;
            }

            ListTag items = tag.getList("items", Tag.TAG_COMPOUND);
            items.forEach(i -> {
                CompoundTag item = (CompoundTag) i;
                ItemStack itemStack = ItemStack.of(item);

                ItemStack replacingItem = null;
                if (item.contains("Replacing")) {
                    replacingItem = ItemStack.of(item.getCompound("Replacing"));
                }

                MultiPaperInventoryHandler.updateInventory(player, component, item.getInt("Slot"), replacingItem, itemStack);
            });

            player.detectEquipmentUpdates();
        });
    }
}
