package puregero.multipaper;

import com.mojang.datafixers.util.Pair;
import io.papermc.paper.chunk.system.scheduling.NewChunkHolder;
import net.minecraft.core.BlockPos;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.*;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.server.level.ChunkMap;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.Container;
import net.minecraft.world.entity.*;
import net.minecraft.world.entity.ai.attributes.AttributeInstance;
import net.minecraft.world.entity.ai.attributes.AttributeModifier;
import net.minecraft.world.entity.decoration.ArmorStand;
import net.minecraft.world.entity.decoration.LeashFenceKnotEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.entity.projectile.FireworkRocketEntity;
import net.minecraft.world.entity.projectile.FishingHook;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.phys.Vec3;
import org.apache.commons.lang.ArrayUtils;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.bukkit.entity.HumanEntity;
import puregero.multipaper.config.MultiPaperConfiguration;
import puregero.multipaper.externalserverprotocol.*;
import puregero.multipaper.mastermessagingprotocol.messages.masterbound.UnsubscribeEntitiesMessage;
import puregero.multipaper.mastermessagingprotocol.messages.masterbound.WillSaveEntitiesLaterMessage;

import java.util.ArrayList;
import java.util.List;

public class MultiPaperEntitiesHandler {

    private static final Logger LOGGER = LogManager.getLogger(MultiPaperEntitiesHandler.class.getSimpleName());
    public static boolean removingEntity = false;
    public static boolean takingItem = false;
    public static boolean modifyingPassengers = false;

    public static Entity getControllingPassenger(Entity entity) {
        Entity controller = entity.getRootVehicle();
        Entity temp;

        while ((temp = getControllingPassengerNonRecursiveButActuallyStillRecursiveJustLessRecursive(controller)) != null && temp != controller) {
            controller = temp;
        }

        return controller;
    }

    private static Entity getControllingPassengerNonRecursiveButActuallyStillRecursiveJustLessRecursive(Entity entity) {
        if (entity instanceof ServerPlayer serverPlayer) {
            // Players control themselves
            return serverPlayer;
        } else if (entity instanceof FireworkRocketEntity fireworkRocket && fireworkRocket.attachedToEntity != null && fireworkRocket.attachedToEntity.isAlive()) {
            // Firework rockets are basically a vehicle and need to be controlled by the controller's server
            return fireworkRocket.attachedToEntity;
        } else if (entity.getControllingPassenger() != null) {
            // Vanilla minecraft's way of handling it
            return entity.getControllingPassenger();
        } else {
            // For non-vanilla vehicles, just find the first player passenger
            for (Entity passenger : entity.getPassengers()) {
                Entity controller = getControllingPassengerNonRecursiveButActuallyStillRecursiveJustLessRecursive(passenger);
                if (controller != null) {
                    return controller;
                }
            }
        }
        return null;
    }

    /**
     * @return true if this entity should be ticked, false if it will be ticked
     *         by another server.
     */
    public static boolean tickEntity(Entity entity) {
        if (MultiPaper.isRealPlayer(getControllingPassenger(entity))) {
            return true;
        }

        if (MultiPaper.isExternalPlayer(getControllingPassenger(entity))) {
            return false;
        }

        LevelChunk chunk = entity.level().getChunkIfLoaded(entity.blockPosition());
        if (!MultiPaper.isChunkLocal(chunk)
                && !(entity instanceof FishingHook)) {
            if (entity instanceof ArmorStand armorStand) {
                boolean temp = armorStand.canTick;
                armorStand.canTick = false;
                armorStand.tick();
                armorStand.canTick = temp;
            }

            if (entity instanceof LivingEntity livingEntity) {
                if (MultiPaper.isChunkExternal(chunk)) {
                    livingEntity.setNoActionTime(0);
                } else {
                    livingEntity.setNoActionTime(livingEntity.getNoActionTime() + 1);
                    livingEntity.checkDespawn();
                }
            }

            return false;
        }

        return true;
    }

    public static void onEntitiesUnload(NewChunkHolder newChunkHolder) {
        MultiPaper.getConnection().send(new UnsubscribeEntitiesMessage(newChunkHolder.world.convertable.getLevelId(), newChunkHolder.chunkX, newChunkHolder.chunkZ));

        // Clear our cache of other servers that are subscribed to this chunk
        newChunkHolder.externalEntitiesSubscribers.clear();
    }

    public static void willWriteEntities(NewChunkHolder newChunkHolder) {
        MultiPaper.getConnection().send(new WillSaveEntitiesLaterMessage(newChunkHolder.world.convertable.getLevelId(), newChunkHolder.chunkX, newChunkHolder.chunkZ));
    }

    public static void onChunkMove(Entity entity, BlockPos to, BlockPos from) {
        if (from.equals(BlockPos.ZERO)) return; // They just spawned
        if (!entity.shouldBeSaved() && !(!entity.isPassenger() && entity.isVehicle() && entity.hasExactlyOnePlayerPassenger()) && !(MultiPaperConfiguration.get().optimizations.reducePlayerPositionUpdatesInUnloadedChunks && MultiPaper.isRealPlayer(entity))) return; // Entity shouldn't be synced (eg players)

        LevelChunk chunkFrom = entity.level().getChunkIfLoaded(from);
        LevelChunk chunkTo = entity.level().getChunkIfLoaded(to);

        if (chunkFrom == null || chunkTo == null) {
            return;
        }

        Entity controllingPassenger = getControllingPassenger(entity);
        if (MultiPaper.isRealPlayer(controllingPassenger) || (MultiPaper.isChunkLocal(chunkFrom) && !MultiPaper.isExternalPlayer(controllingPassenger))) {
            if (!MultiPaper.isChunkLocal(chunkTo)) {
                // Leaving our jurisdiction, do a full entity update to ensure the new external server has all the required info
                if (!(entity instanceof ServerPlayer)) { // Ignore players as they aren't ticked by the new external server
                    onEntityUnlock(entity);
                    MultiPaper.runSync(() -> MultiPaper.broadcastPacketToExternalServers(chunkTo.getChunkHolder().externalEntitiesSubscribers, () -> new EntityUpdateNBTPacket(entity)));
                    if (entity instanceof Mob mob) {
                        MultiPaper.runSync(() -> {
                            BlockPos goal = mob.getNavigation().getTargetPos();
                            if (goal != null) {
                                MultiPaper.broadcastPacketToExternalServers(chunkTo.getChunkHolder().externalEntitiesSubscribers, () -> new MobSetNavigationGoalPacket(mob, goal));
                            }
                        });
                    }
                }
            }
            for (ExternalServer fromServer : chunkFrom.getChunkHolder().externalEntitiesSubscribers) {
                if (fromServer.getConnection() != null && !chunkTo.getChunkHolder().externalEntitiesSubscribers.contains(fromServer)) {
                    // Entity is leaving another server's area, make sure they know this
                    MultiPaper.runSync(() -> fromServer.getConnection().send(new EntityUpdatePacket(entity, new ClientboundTeleportEntityPacket(entity))));
                }
            }
            for (ExternalServer toServer : chunkTo.getChunkHolder().externalEntitiesSubscribers) {
                if (toServer.getConnection() != null && !chunkFrom.getChunkHolder().externalEntitiesSubscribers.contains(toServer)) {
                    // Entity is entering another server's area, send them the full entity
                    MultiPaper.runSync(() -> {
                        if (entity instanceof ServerPlayer) {
                            // Ensure the player's position is up to date
                            MultiPaper.runSync(() -> toServer.getConnection().send(new EntityUpdatePacket(entity, new ClientboundTeleportEntityPacket(entity))));
                        } else {
                            MultiPaper.runSync(() -> {
                                EntityUpdateWithDependenciesPacket.sendVehicleAndPassengersPacketsRecursivelyToServers(entity, List.of(toServer));
                            });
                        }
                    });
                }
            }
        }
    }

    public static void onEntityUnlock(Entity entity) {
        if (entity instanceof Container container) {
            new ArrayList<>(container.getViewers()).forEach(HumanEntity::closeInventory);
        }
        for (Entity passenger : entity.getPassengers()) {
            onEntityUnlock(passenger);
        }
    }

    private static void setRemovedRecursive(Entity entity) {
        for (Entity passenger : entity.getPassengers()) {
            if (!(passenger instanceof ServerPlayer)) {
                setRemovedRecursive(passenger);
            }
        }
        entity.setRemoved(Entity.RemovalReason.UNLOADED_TO_CHUNK);
    }

    public static void handleEntityUpdate(ExternalServerConnection connection, Entity entity, Packet<?> packet) {
        if (entity.level() instanceof ServerLevel serverLevel) {
            serverLevel.inactiveTracker.track(entity);
        }

        if (packet instanceof ClientboundMoveEntityPacket moveEntityPacket) {
            VecDeltaCodec vecDeltaCodec = new VecDeltaCodec();
            vecDeltaCodec.setBase(entity.position());
            Vec3 vector = vecDeltaCodec.decode(moveEntityPacket.getXa(), moveEntityPacket.getYa(), moveEntityPacket.getZa());
            if (!(entity instanceof ServerPlayer) && !((ServerLevel) entity.level()).getEntityLookup().isChunkLoaded(new ChunkPos(new BlockPos((int) vector.x, (int) vector.y, (int) vector.z)))) {
                setRemovedRecursive(entity);
                return;
            }
            entity.preserveMotion = true;
            setFallDistance(entity, vector.y);
            if (moveEntityPacket.hasRotation()) {
                entity.moveTo(vector.x, vector.y, vector.z, moveEntityPacket.getyRot() * 360f / 256, moveEntityPacket.getxRot() * 360f / 256);
            } else {
                // Include y-rot and x-rot, as without it, it teleports players
                entity.moveTo(vector.x, vector.y, vector.z, entity.getYRot(), entity.getXRot());
            }
            entity.onGround = moveEntityPacket.isOnGround();
        } else if (packet instanceof ClientboundTeleportEntityPacket teleportEntityPacket) {
            if (!(entity instanceof ServerPlayer) &&!((ServerLevel) entity.level()).getEntityLookup().isChunkLoaded(new ChunkPos(new BlockPos((int) teleportEntityPacket.getX(), (int) teleportEntityPacket.getY(), (int) teleportEntityPacket.getZ())))) {
                setRemovedRecursive(entity);
                return;
            }
            entity.preserveMotion = true;
            setFallDistance(entity, teleportEntityPacket.getY());
            entity.moveTo(teleportEntityPacket.getX(), teleportEntityPacket.getY(), teleportEntityPacket.getZ(), teleportEntityPacket.getyRot() * 360f / 256, teleportEntityPacket.getxRot() * 360f / 256);
            entity.onGround = teleportEntityPacket.isOnGround();
        } else if (packet instanceof ClientboundSetEntityMotionPacket setEntityMotionPacket) {
            entity.setDeltaMovement(setEntityMotionPacket.getXa() / 8000.0D, setEntityMotionPacket.getYa() / 8000.0D, setEntityMotionPacket.getZa() / 8000.0D);
        } else if (packet instanceof ClientboundRotateHeadPacket rotateHeadPacket) {
            entity.setYHeadRot(rotateHeadPacket.getYHeadRot() * 360f / 256);
        } else if (packet instanceof ClientboundSetEntityDataPacket setEntityDataPacket) {
            if (entity instanceof ExternalPlayer) {
                ((ExternalPlayer) entity).updatingData = true;
            }

            entity.getEntityData().assignValues(setEntityDataPacket.packedItems());
            if (entity instanceof LivingEntity livingEntity) {
                for (SynchedEntityData.DataValue<?> item : setEntityDataPacket.packedItems()) {
                    if (item.id() == LivingEntity.DATA_HEALTH_ID.getId()) {
                        // Dumb CraftBukkit needs us to set the health using setHealth instead of simply updating the entity data
                        livingEntity.setHealth((Float) item.value());
                    }
                }
            }

            // Due 1.19.3 packet changes when apply the values we should resync it to local players.
            if (entity.tracker != null) {
                entity.tracker.seenBy.forEach(serverplayerconnection -> {
                    if (MultiPaper.isRealPlayer(serverplayerconnection.getPlayer())) {
                        entity.getEntityData().refresh(serverplayerconnection.getPlayer());
                    }
                });
            }

            if (entity instanceof ExternalPlayer) {
                ((ExternalPlayer) entity).updatingData = false;
            }
        } else if (packet instanceof ClientboundUpdateAttributesPacket updateAttributesPacket) {
            for (ClientboundUpdateAttributesPacket.AttributeSnapshot snapshot : updateAttributesPacket.getValues()) {
                AttributeInstance instance = ((LivingEntity) entity).getAttribute(snapshot.getAttribute());
                instance.setBaseValue(snapshot.getBase());
                instance.removeModifiers();
                for (AttributeModifier modifier : snapshot.getModifiers()) {
                    instance.addPermanentModifier(modifier);
                }
            }
        } else if (packet instanceof ClientboundAnimatePacket animatePacket) {
            ChunkMap.TrackedEntity trackedEntity = entity.tracker;
            if (trackedEntity != null) {
                trackedEntity.broadcast(new ClientboundAnimatePacket(entity, animatePacket.getAction()));
            }
        } else if (packet instanceof ClientboundDamageEventPacket damageEventPacket) {
            ChunkMap.TrackedEntity trackedEntity = entity.tracker;
            if (trackedEntity != null) {
                trackedEntity.broadcast(new ClientboundDamageEventPacket(entity, damageEventPacket.getSource(entity.level())));
            }
        } else if (packet instanceof ClientboundEntityEventPacket entityEventPacket) {
            ChunkMap.TrackedEntity trackedEntity = entity.tracker;
            if (trackedEntity != null) {
                trackedEntity.broadcast(new ClientboundEntityEventPacket(entity, entityEventPacket.getEventId()));
            }
        } else if (packet instanceof ClientboundSetEquipmentPacket setEquipmentPacket) {
            for (Pair<EquipmentSlot, ItemStack> pair : setEquipmentPacket.getSlots()) {
                entity.setItemSlot(pair.getFirst(), pair.getSecond());
            }
            if (entity instanceof LivingEntity livingEntity) {
                livingEntity.detectEquipmentUpdates();
            }
        } else {
            LOGGER.warn("Unhandled packet " + packet);
        }
    }

    private static void setFallDistance(Entity entity, double newY) {
        double oldY = entity.getY();

        if (newY >= oldY) {
            entity.fallDistance = 0;
        } else {
            entity.fallDistance += oldY - newY;
        }
    }

    public static void handleEntityWithDependenicesUpdate(Entity entity, Entity[] entities, Packet<?> packet) {
        if (packet instanceof ClientboundSetPassengersPacket setPassengersPacket) {
            modifyingPassengers = true;
            for (Entity riding : entity.getPassengers()) {
                if (!ArrayUtils.contains(entities, riding)) {
                    riding.stopRiding();
                }
            }
            for (Entity passenger : entities) {
                if (!entity.getPassengers().contains(passenger)) {
                    passenger.startRiding(entity, true);
                }
            }
            modifyingPassengers = false;
        } else if (packet instanceof ClientboundSetEntityLinkPacket setEntityLinkPacket) {
            if (entities.length == 0) {
                ((Mob) entity).setLeashedTo(null, true);
            } else {
                ((Mob) entity).setLeashedTo(entities[0], true);
            }
        } else if (packet instanceof ClientboundTakeItemEntityPacket takeItemEntityPacket) {
            takingItem = true;
            ((LivingEntity) entities[0]).take(entity, takeItemEntityPacket.getAmount());
            takingItem = false;
        } else {
            LOGGER.warn("Unhandled dependencies packet " + packet);
        }
    }

    public static void onEntitySpawn(Entity entity) {
        if (!shouldSyncEntity(entity)) return;

        MultiPaper.runSync(() -> { // Run this after the entity has finished spawning
            if (entity.isRemoved()) {
                return;
            }

            NewChunkHolder newChunkHolder = MultiPaper.getChunkHolder((ServerLevel) entity.level(), entity.chunkPosition().x, entity.chunkPosition().z);
            if (newChunkHolder != null) {
                MultiPaper.broadcastPacketToExternalServers(newChunkHolder.externalEntitiesSubscribers, () -> new EntityUpdateNBTPacket(entity));
                MultiPaper.broadcastPacketToExternalServers(newChunkHolder.externalEntitiesSubscribers, () -> new EntityUpdatePacket(entity, new ClientboundSetEntityDataPacket(entity.getId(), entity.getEntityData().getAll())));
            } else {
                LOGGER.warn(entity + " spawned in an unloaded chunk, broadcasting it to all servers just incase anyone has it loaded");
                MultiPaper.broadcastPacketToExternalServers(new EntityUpdateNBTPacket(entity));
                MultiPaper.broadcastPacketToExternalServers(new EntityUpdatePacket(entity, new ClientboundSetEntityDataPacket(entity.getId(), entity.getEntityData().getAll())));
            }
        });
    }

    public static boolean shouldSyncEntity(Entity entity) {
        return !entity.isFake() && (entity.shouldBeSaved() || entity instanceof LightningBolt || entity instanceof LeashFenceKnotEntity);
    }

    public static void onEntityRemove(Entity entity, Entity.RemovalReason reason) {
        if (!shouldSyncEntity(entity) && MultiPaperEntitiesHandler.getControllingPassenger(entity) == entity && !entity.isPassenger() && !(entity instanceof Player && reason == Entity.RemovalReason.KILLED)) return;
        if (removingEntity) return;
        if (entity instanceof ExternalPlayer) return;
        if (reason == Entity.RemovalReason.UNLOADED_TO_CHUNK) return;

        NewChunkHolder newChunkHolder = MultiPaper.getChunkHolder((ServerLevel) entity.level(), entity.chunkPosition().x, entity.chunkPosition().z);
        if (newChunkHolder != null) {
            MultiPaper.broadcastPacketToExternalServers(newChunkHolder.externalEntitiesSubscribers, () -> new EntityRemovePacket(entity, reason == Entity.RemovalReason.UNLOADED_WITH_PLAYER));
        } else {
            LOGGER.warn(entity + " removed in an unloaded chunk");
        }
    }

    public static void onEntityUpdate(Entity entity, Packet<?> packet, NewChunkHolder newChunkHolder) {
        if (!entity.shouldBeSaved() && !(entity instanceof ServerPlayer) && (entity.getPassengers().isEmpty() || entity.getIndirectPassengersStream().noneMatch(e -> e instanceof ServerPlayer))) return;

        if (packet instanceof ClientboundSetEquipmentPacket && entity instanceof ServerPlayer) {
            // This is handled with inventories for players
            return;
        } else if (packet instanceof ClientboundSetPassengersPacket setPassengersPacket) {
            List<Entity> entities = new ArrayList<>();
            for (int id : setPassengersPacket.getPassengers()) {
                entities.add(entity.level().getEntity(id));
            }
            MultiPaper.broadcastPacketToExternalServers(newChunkHolder.externalEntitiesSubscribers, () -> new EntityUpdateWithDependenciesPacket(entity, entities, packet));
            return;
        } else if (packet instanceof ClientboundSetEntityLinkPacket setEntityLinkPacket) {
            List<Entity> entities = new ArrayList<>();
            entities.add(entity.level().getEntity(setEntityLinkPacket.getDestId()));
            MultiPaper.broadcastPacketToExternalServers(newChunkHolder.externalEntitiesSubscribers, () -> new EntityUpdateWithDependenciesPacket(entity, entities, packet));
            return;
        } else if (packet instanceof ClientboundTakeItemEntityPacket takeItemEntityPacket) {
            List<Entity> entities = new ArrayList<>();
            entities.add(entity.level().getEntity(takeItemEntityPacket.getPlayerId()));
            MultiPaper.broadcastPacketToExternalServers(newChunkHolder.externalEntitiesSubscribers, () -> new EntityUpdateWithDependenciesPacket(entity, entities, packet));
            return;
        }

        if (packet instanceof ClientboundBlockUpdatePacket) {
            MultiPaperChunkHandler.onBlockUpdate(newChunkHolder, packet);
            return;
        }

        if (MultiPaper.isRealPlayer(entity) && !(MultiPaperConfiguration.get().optimizations.reducePlayerPositionUpdatesInUnloadedChunks && packet instanceof ClientboundMoveEntityPacket)) {
            MultiPaper.broadcastPacketToExternalServers(new EntityUpdatePacket(entity, packet));
            return;
        }

        if (newChunkHolder != null) {
            MultiPaper.broadcastPacketToExternalServers(newChunkHolder.externalEntitiesSubscribers, () -> new EntityUpdatePacket(entity, packet));
        } else {
            LOGGER.warn("onEntityUpdate was called for an unloaded chunk " + entity);
        }
    }
}
