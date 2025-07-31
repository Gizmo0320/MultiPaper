package puregero.multipaper;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.Container;
import net.minecraft.world.ContainerListener;
import net.minecraft.world.item.ItemStack;
import org.bukkit.Bukkit;
import org.bukkit.craftbukkit.scheduler.CraftScheduler;
import org.bukkit.scheduler.BukkitTask;
import puregero.multipaper.externalserverprotocol.PlayerInventoryUpdatePacket;

public class MultiPaperEnderChestHandler implements ContainerListener {

    private static boolean broadcastChanges = true;

    private final ServerPlayer player;
    private ItemStack[] sentItems = new ItemStack[0];
    private BukkitTask isScheduled = null;

    public MultiPaperEnderChestHandler(ServerPlayer player) {
        this.player = player;
    }

    @Override
    public void containerChanged(Container container) {
        if (container.getContainerSize() != sentItems.length) {
            sentItems = new ItemStack[container.getContainerSize()];
        }

        if (isScheduled == null && broadcastChanges && player.server.getPlayerList().getPlayer(player.getUUID()) == player) {
            // Wait till they join to broadcast changes
            isScheduled = ((CraftScheduler) Bukkit.getScheduler()).scheduleInternalTask(() -> {
                isScheduled = null;
                containerChanged(container);
            }, 1, "MultiPaperEnderChestHandler-containerChanged");
            return;
        }


        isScheduled = null;

        CompoundTag itemsRoot = new CompoundTag();
        ListTag items = new ListTag();
        for (int i = 0; i < sentItems.length; i++) {
            ItemStack item = container.getItem(i);
            if (!item.equals(sentItems[i])) {
                sentItems[i] = item.copy();

                if (broadcastChanges) {
                    CompoundTag itemToSend = new CompoundTag();
                    itemToSend.putInt("Slot", i);
                    item.save(itemToSend);
                    items.add(itemToSend);
                }
            }
        }

        if (!items.isEmpty()) {
            itemsRoot.put("items", items);
            MultiPaper.broadcastPacketToExternalServers(new PlayerInventoryUpdatePacket(player, "enderchest", itemsRoot));
        }
    }

    public static void sendFullEnderChestUpdate(ServerPlayer player, ExternalServerConnection... connections) {
        CompoundTag itemsRoot = new CompoundTag();
        ListTag items = new ListTag();
        for (int i = 0; i < player.getEnderChestInventory().getContainerSize(); i++) {
            ItemStack item = player.getEnderChestInventory().getItem(i);
            CompoundTag itemToSend = new CompoundTag();
            itemToSend.putInt("Slot", i);
            item.save(itemToSend);
            items.add(itemToSend);
        }

        itemsRoot.put("items", items);
        for (ExternalServerConnection connection : connections) {
            connection.send(new PlayerInventoryUpdatePacket(player, "enderchest", itemsRoot));
        }
    }

    public static void updateInventory(ServerPlayer player, int slot, ItemStack item) {
        broadcastChanges = false;
        player.getEnderChestInventory().setItem(slot, item);
        broadcastChanges = true;
    }
}
