package puregero.multipaper;

import io.papermc.paper.chunk.system.scheduling.NewChunkHolder;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientboundContainerClosePacket;
import net.minecraft.network.protocol.game.ClientboundOpenScreenPacket;
import net.minecraft.network.protocol.game.ClientboundPlayerPositionPacket;
import net.minecraft.network.protocol.game.ClientboundSetCarriedItemPacket;
import net.minecraft.network.protocol.game.ClientboundSetExperiencePacket;
import net.minecraft.network.protocol.game.ServerGamePacketListener;
import net.minecraft.network.protocol.game.ServerboundAcceptTeleportationPacket;
import net.minecraft.network.protocol.game.ServerboundContainerClosePacket;
import net.minecraft.network.protocol.game.ServerboundUseItemOnPacket;
import net.minecraft.network.protocol.game.ServerboundUseItemPacket;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.RelativeMovement;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.BucketItem;
import net.minecraft.world.item.EnderEyeItem;
import net.minecraft.world.item.FishingRodItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.AABB;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import puregero.multipaper.externalserverprotocol.PlayerActionPacket;
import puregero.multipaper.externalserverprotocol.PlayerInventoryUpdatePacket;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.function.BiConsumer;
import java.util.function.Supplier;

public class MultiPaperInventoryHandler {

    private static final Logger LOGGER = LogManager.getLogger(MultiPaperInventoryHandler.class.getSimpleName());

    public static boolean updatingInventory = false;
    private static final HashMap<UUID, ArrayList<AwaitingTeleport>> awaitingTeleports = new HashMap<>();
    private static final Set<NonNullListFilter<? extends ItemStack>> modifiedInventories = new LinkedHashSet<>();

    public static boolean handlePacketFromExternalServer(ExternalServer server, ServerPlayer player, Packet<?> packet) {
        if (packet instanceof ClientboundOpenScreenPacket) {
            // An external server has requested to open a window on a player
            player.openContainer = server;
        } else if (packet instanceof ClientboundContainerClosePacket) {
            // An external server has requested to close the open window on a player
            if (player.openContainer == server) {
                player.openContainer = null;
            }
        } else if (packet instanceof ClientboundSetCarriedItemPacket setCarriedItemPacket) {
            // An external server is changing the selected item in the hotbar
            player.getInventory().selected = setCarriedItemPacket.getSlot();
        } else if (packet instanceof ClientboundSetExperiencePacket setExperiencePacket) {
            // An external server is changing the player's experience level
            player.experienceLevel = setExperiencePacket.getExperienceLevel();
            player.experienceProgress = setExperiencePacket.getExperienceProgress();
            player.totalExperience = setExperiencePacket.getTotalExperience();
        } else if (packet instanceof ClientboundPlayerPositionPacket playerPositionPacket) {
            // An external server is teleporting the player
            double x = playerPositionPacket.getRelativeArguments().contains(RelativeMovement.X) ? player.getX() : 0.0D;
            double y = playerPositionPacket.getRelativeArguments().contains(RelativeMovement.Y) ? player.getY() : 0.0D;
            double z = playerPositionPacket.getRelativeArguments().contains(RelativeMovement.Z) ? player.getZ() : 0.0D;
            float yaw = playerPositionPacket.getRelativeArguments().contains(RelativeMovement.Y_ROT) ? player.getYRot() : 0.0F;
            float pitch = playerPositionPacket.getRelativeArguments().contains(RelativeMovement.X_ROT) ? player.getXRot() : 0.0F;
            player.connection.teleport(x + playerPositionPacket.getX(), y + playerPositionPacket.getY(), z + playerPositionPacket.getZ(), yaw + playerPositionPacket.getYRot(), pitch + playerPositionPacket.getXRot(), playerPositionPacket.getRelativeArguments());
            server.getConnection().send(new PlayerActionPacket(player, new ServerboundAcceptTeleportationPacket(playerPositionPacket.getId())));
            return true;
        }

        return false;
    }

    /**
     * Returns true if the even should be cancelled
     */
    public static boolean handleInteractEvent(ServerPlayer player, ServerboundUseItemOnPacket packet) {
        NewChunkHolder newChunkHolder = MultiPaper.getChunkHolder((ServerLevel) player.level(), packet.getHitResult().getBlockPos());
        ItemStack item = player.getItemInHand(packet.getHand());
        if (MultiPaper.isChunkExternal(newChunkHolder) && !(item.getItem() instanceof BucketItem)) {
            newChunkHolder.externalOwner.getConnection().send(new PlayerActionPacket(player, packet));
            return true;
        }

        return false;
    }

    /**
     * Returns true if the even should be cancelled
     */
    public static boolean handleUseItemEvent(ServerPlayer player, ServerboundUseItemPacket packet) {
        NewChunkHolder newChunkHolder = MultiPaper.getChunkHolder((ServerLevel) player.level(), player.blockPosition());
        ItemStack item = player.getItemInHand(packet.getHand());
        if (MultiPaper.isChunkExternal(newChunkHolder) && item.getItem() instanceof EnderEyeItem) {
            newChunkHolder.externalOwner.getConnection().send(new PlayerActionPacket(player, packet));
            return true;
        } else if (MultiPaper.isRealPlayer(player) && item.getItem() instanceof FishingRodItem) {
            MultiPaper.broadcastPacketToExternalServers(newChunkHolder.externalSubscribers, () -> new PlayerActionPacket(player, packet));
        }

        return false;
    }

    /**
     * Returns true if the even should be cancelled
     */
    public static boolean handleContainerEvent(ServerPlayer player, Packet<ServerGamePacketListener> containerPacket) {
        if (player.openContainer != null) {
            player.openContainer.getConnection().send(new PlayerActionPacket(player, containerPacket));

            if (containerPacket instanceof ServerboundContainerClosePacket) {
                player.openContainer = null;
            }

            return true;
        }

        return false;
    }

    public static void updateInventory(ServerPlayer player, String name, int slot, ItemStack replacingItem, ItemStack item) {
        updatingInventory = true; // Don't let these changes mark the inventories as dirty
        NonNullListFilter<ItemStack> component = null;
        switch (name) {
            case "items" -> component = player.getInventory().items;
            case "armor" -> component = player.getInventory().armor;
            case "offhand" -> component = player.getInventory().offhand;
            case "enderchest" -> MultiPaperEnderChestHandler.updateInventory(player, slot, item);
            default -> throw new IllegalArgumentException("Unknown inventory component of " + name);
        }

        item.dirty = false;

        if (component != null) {
            ItemStack lastItem = component.lastItems.get(slot);
            ItemStack currentItem = component.get(slot);

            if (!ItemStack.matches(lastItem, currentItem) && !MultiPaper.isRealPlayer(player)) {
                // Our changes haven't been sent yet, send them
                broadcastComponentChanges(player, component);
            }

            if (MultiPaper.isRealPlayer(player) && replacingItem != null && !ItemStack.matches(replacingItem, currentItem)) {
                // The expected item doesn't match, a merge is required
                item.dirty = true; component.isDirty = true; // Resend the item afterwards to sync the other servers
                if (isSameItemSameTagIgnoringDurability(replacingItem, item)) {
                    if (replacingItem.getCount() != item.getCount()) {
                        int countDiff = item.getCount() - replacingItem.getCount();
                        if (countDiff > 0) {
                            item.setCount(countDiff);
                            addItem(item, player);
                        } else {
                            LOGGER.warn(player.getScoreboardName() + ": An external server tried to remove " + countDiff + " items from " + replacingItem + ", but that item is now a " + currentItem + ". Searching for duped items to remove...");
                            item.setCount(-countDiff);
                            removeNearbyItems(player, item);
                        }
                    } else if (replacingItem.getDamageValue() != item.getDamageValue()) {
                        int damageDiff = item.getDamageValue() - replacingItem.getDamageValue();
                        if (isSameItemSameTagIgnoringDurability(currentItem, item)) {
                            currentItem.setDamageValue(currentItem.getDamageValue() + damageDiff);
                        }
                    } else {
                        LOGGER.warn(player.getScoreboardName() + ": Trying to merge the same item same tags, but neither the count nor the durability is different. " + item + " and " + replacingItem + " and " + currentItem);
                    }
                } else {
                    addItem(item, player);
                    if (!replacingItem.isEmpty()) {
                        // The item has probably duplicated, try to find one of the copies and remove it
                        LOGGER.info(player.getScoreboardName() + ": An external server tried to replace " + replacingItem + " with a " + item + ", but that item is now a " + currentItem + ". Searching for duped items to remove...");
                        removeNearbyItems(player, replacingItem);
                    }
                }
            } else {
                component.set(slot, item);
                component.lastItems.set(slot, item.copy()); // We don't need to do this if it's our player
            }
        }

        updatingInventory = false;
    }

    private static void addItem(ItemStack itemStack, ServerPlayer player) {
        if (player.isDeadOrDying() || !player.getInventory().add(itemStack)) {
            player.drop(itemStack, false);
        }
    }

    private static void removeNearbyItems(ServerPlayer player, ItemStack itemToRemove) {
        for (Runnable runnable : new Runnable[]{
                () -> removeFromContainerMenu(player, itemToRemove, player.containerMenu),
                () -> removeFromContainerMenu(player, itemToRemove, player.inventoryMenu),
                () -> removeFromInventory(player, itemToRemove, player.getInventory().compartments),
                () -> removeFromItemEntities(player, itemToRemove),
        }) {
            runnable.run();

            if (itemToRemove.isEmpty()) {
                return;
            }
        }
    }

    private static void removeFromContainerMenu(ServerPlayer player, ItemStack itemToRemove, AbstractContainerMenu containerMenu) {
        removeItems(player, itemToRemove, containerMenu::getCarried, (item, count) -> {
            LOGGER.info("{}: Removing {}x of {} from cursor in open menu {}", player.getScoreboardName(), count, itemToRemove, containerMenu.getClass().getSimpleName());
            containerMenu.setCarried(item);
        });

        for (Slot slot : containerMenu.slots) {
            removeItems(player, itemToRemove, slot::getItem, (item, count) -> {
                LOGGER.info("{}: Removing {}x of {} from open menu {}", player.getScoreboardName(), count, itemToRemove, containerMenu.getClass().getSimpleName());
                slot.set(item);
            });
        }
    }

    private static void removeFromInventory(ServerPlayer player, ItemStack itemToRemove, List<NonNullListFilter<ItemStack>> compartments) {
        for (List<ItemStack> compartment : compartments) {
            for (int slot = 0; slot < compartment.size(); slot++) {
                final int finalSlot = slot;
                removeItems(player, itemToRemove, () -> compartment.get(finalSlot), (item, count) -> {
                    LOGGER.info("{}: Removing {}x of {} from inventory", player.getScoreboardName(), count, itemToRemove);
                    compartment.set(finalSlot, item);
                });
            }
        }
    }

    private static void removeFromItemEntities(ServerPlayer player, ItemStack itemToRemove) {
        for (ItemEntity entity : player.level().getEntitiesOfClass(ItemEntity.class, AABB.ofSize(player.position(), 16, 16, 16))) {
            removeItems(player, itemToRemove, entity::getItem, (item, count) -> {
                LOGGER.info("{}: Removing {}x of {} from item entity at {}", player.getScoreboardName(), count, itemToRemove, entity.position());
                entity.remove(Entity.RemovalReason.DISCARDED); // Remove the item entity to update the item inside
                if (!item.isEmpty()) {
                    entity.spawnAtLocation(item, 0); // Respawn it if it has items left
                }
            });
        }
    }

    private static void removeItems(ServerPlayer player, ItemStack itemToRemove, Supplier<ItemStack> getter, BiConsumer<ItemStack, Integer> setter) {
        if (itemToRemove.isEmpty()) return;

        ItemStack item = getter.get();
        if (ItemStack.isSameItemSameTags(item, itemToRemove)) {
            int itemCount = item.getCount();
            if (item.getCount() < itemToRemove.getCount()) {
                item.setCount(0);
                setter.accept(ItemStack.EMPTY, itemCount);
                itemToRemove.setCount(itemToRemove.getCount() - itemCount);
            } else {
                item.setCount(itemCount - itemToRemove.getCount());
                setter.accept(item, itemToRemove.getCount());
                itemToRemove.setCount(0);
            }
        }
    }

    private static boolean isSameItemSameTagIgnoringDurability(ItemStack left, ItemStack right) {
        boolean hasLeftDurability = false;
        boolean hasRightDurability = false;
        int leftDurability = left.getDamageValue();
        int rightDurability = right.getDamageValue();

        if (left.getTag() != null && left.getTag().contains("Damage")) {
            hasLeftDurability = true;
            left.getTag().remove("Damage");
        }
        if (right.getTag() != null) {
            hasRightDurability = true;
            right.getTag().remove("Damage");
        }

        boolean result = ItemStack.isSameItemSameTags(left, right);

        if (hasLeftDurability) left.getTag().putInt("Damage", leftDurability);
        if (hasRightDurability) right.getTag().putInt("Damage", rightDurability);

        return result;
    }

    /**
     * Returns true if the changes to the inventory component should be marked as dirty.
     */
    public static <E extends ItemStack> boolean markDirty(NonNullListFilter<E> inventoryComponent) {
        if ( !io.papermc.paper.util.TickThread.isTickThread() ) {
            LOGGER.warn("Asynchronous inventory modification. This is unsafe and will eventually cause an issue.", new IllegalStateException("Async access"));
        }

        if (!updatingInventory) {
            modifiedInventories.add(inventoryComponent);
            return true;
        }

        return false;
    }

    /**
     * Runs at the end of a vanilla tick. Ie any changes to the inventory made in the tick will instantly be updated
     * to other servers without a tick delay.
     */
    public static void tick() {
        for (NonNullListFilter<? extends ItemStack> inventoryComponent : modifiedInventories) {
            if (inventoryComponent.player instanceof ServerPlayer player) {
                broadcastComponentChanges(player, inventoryComponent);
            }
        }
        modifiedInventories.clear();
    }

    public static void broadcastComponentChanges(ServerPlayer player, NonNullListFilter<? extends ItemStack> inventoryComponent) {
        if (inventoryComponent.isDirty) {
            inventoryComponent.isDirty = false;
            ListTag items = new ListTag();
            for (int i = 0; i < inventoryComponent.size(); i++) {
                if (inventoryComponent.dirty[i] || inventoryComponent.get(i).dirty) {
                    CompoundTag item = new CompoundTag();
                    item.putInt("Slot", i);
                    inventoryComponent.get(i).save(item);
                    items.add(item);

                    if (!MultiPaper.isRealPlayer(player)) {
                        CompoundTag replacingItem = new CompoundTag();
                        inventoryComponent.lastItems.get(i).save(replacingItem);
                        item.put("Replacing", replacingItem);
                    }

                    inventoryComponent.dirty[i] = false;
                    inventoryComponent.get(i).dirty = false;
                    inventoryComponent.lastItems.set(i, inventoryComponent.get(i).copy());
                }
            }

            if (!items.isEmpty()) {
                CompoundTag itemsRoot = new CompoundTag();
                itemsRoot.put("items", items);
                MultiPaper.broadcastPacketToExternalServers(player, new PlayerInventoryUpdatePacket(player, inventoryComponent.name, itemsRoot));
                player.detectEquipmentUpdates();
            }
        }
    }

    public static void sendFullInventoryUpdate(ServerPlayer player, ExternalServerConnection... connections) {
        for (NonNullListFilter<? extends ItemStack> inventoryComponent : new NonNullListFilter[] {
                player.getInventory().items,
                player.getInventory().armor,
                player.getInventory().offhand,
        }) {
            ListTag items = new ListTag();
            for (int i = 0; i < inventoryComponent.size(); i++) {
                CompoundTag item = new CompoundTag();
                item.putInt("Slot", i);
                inventoryComponent.get(i).save(item);
                items.add(item);
            }

            CompoundTag itemsRoot = new CompoundTag();
            itemsRoot.put("items", items);
            for (ExternalServerConnection connection : connections) {
                connection.send(new PlayerInventoryUpdatePacket((ServerPlayer) inventoryComponent.player, inventoryComponent.name, itemsRoot));
            }
        }
    }

    public static void handleAcceptTeleport(ServerPlayer player, ServerboundAcceptTeleportationPacket packet) {
        ArrayList<AwaitingTeleport> accepts = awaitingTeleports.get(player.getUUID());

        if (accepts != null) {
            accepts.removeIf(accept -> {
               if (accept.id == packet.getId()) {
                   accept.externalServer.getConnection().send(new PlayerActionPacket(player, packet));
                   return true;
               } else {
                   return false;
               }
            });

            if (accepts.isEmpty()) {
                awaitingTeleports.remove(player.getUUID());
            }
        }
    }

    private static class AwaitingTeleport {
        private final ExternalServer externalServer;
        private final int id;

        private AwaitingTeleport(ExternalServer externalServer, int id) {
            this.externalServer = externalServer;
            this.id = id;
        }
    }
}
