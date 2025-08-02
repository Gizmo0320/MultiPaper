package puregero.multipaper;

import ca.spottedleaf.moonrise.patches.chunk_system.level.entity.ChunkEntitySlices;
import ca.spottedleaf.moonrise.patches.chunk_system.scheduling.NewChunkHolder;
import ca.spottedleaf.moonrise.patches.starlight.light.SWMRNibbleArray;
import io.papermc.paper.util.MCUtil;
import net.minecraft.core.BlockPos;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientboundBlockEntityDataPacket;
import net.minecraft.network.protocol.game.ClientboundBlockUpdatePacket;
import net.minecraft.network.protocol.game.ClientboundLightUpdatePacket;
import net.minecraft.network.protocol.game.ClientboundSectionBlocksUpdatePacket;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.LightLayer;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.ImposterProtoChunk;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.lighting.LevelLightEngine;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.bukkit.Bukkit;
import org.bukkit.craftbukkit.CraftWorld;
import puregero.multipaper.externalserverprotocol.SendUpdatePacket;
import puregero.multipaper.mastermessagingprotocol.messages.masterbound.SubscribeChunkMessage;
import puregero.multipaper.mastermessagingprotocol.messages.masterbound.UnsubscribeChunkMessage;
import puregero.multipaper.mastermessagingprotocol.messages.serverbound.BooleanMessageReply;

import javax.annotation.Nullable;
import java.util.BitSet;
import java.util.HashSet;
import java.util.Iterator;
import java.util.UUID;

public class MultiPaperChunkHandler {

    private static final Logger LOGGER = LogManager.getLogger(MultiPaperChunkHandler.class.getSimpleName());

    public static boolean shouldTick(final Level level, final BlockPos pos) {
        final LevelChunk chunk = level.getChunkIfLoaded(pos);
        return MultiPaper.isChunkLocal(chunk);
    }

    public static void onChunkLoad(final LevelChunk chunk) {

    }

    public static void onChunkUnload(final NewChunkHolder newChunkHolder, @Nullable final ChunkAccess chunk, @Nullable final ChunkEntitySlices chunkEntitySlices) {
        if (newChunkHolder.hasExternalLockRequest) {
            MultiPaper.unlockChunk(newChunkHolder, chunk, chunkEntitySlices);
        }
        MultiPaper.getConnection().sendAndAwaitReply(new UnsubscribeChunkMessage(newChunkHolder.world.getWorld().getName(), newChunkHolder.chunkX, newChunkHolder.chunkZ), BooleanMessageReply.class).thenRun(() ->
            onChunkUnsubscribed(newChunkHolder.world.getWorld().getName(), newChunkHolder.chunkX, newChunkHolder.chunkZ)
        );
    }

    public static void onChunkUnsubscribed(final String world, final int cx, final int cz) {
        final NewChunkHolder holder = MultiPaper.getChunkHolder(world, cx, cz);

        if (holder != null && holder.getCurrentChunk() != null) {
            LOGGER.warn("Chunk " + world + "," + cx + "," + cz + " was unsubscribed from but has a chunk loaded! Resubscribing...");
            MultiPaper.getConnection().send(new SubscribeChunkMessage(world, cx, cz));
        }
    }

    private static final HashSet<BlockEntity> blockEntitiesToBroadcast = new HashSet<>();
    public static void broadcastBlockEntityChange(final BlockEntity entity) {
        if (blockUpdateChunk != null) return; // Don't broadcast the update to other servers if we're handling an update
        blockEntitiesToBroadcast.add(entity);
        // Wait a bit as the block entity may get changed multiple times in 1 tick
        MultiPaper.runSync(() -> {
            for (final BlockEntity blockEntity : blockEntitiesToBroadcast) {
                if (blockEntity != null && blockEntity.getLevel() != null) {
                    onBlockUpdate(MultiPaper.getChunkHolder(blockEntity.getLevel().getWorld().getName(), blockEntity.getBlockPos()), ClientboundBlockEntityDataPacket.create(blockEntity, BlockEntity::saveWithFullMetadata, false));
                }
            }
            blockEntitiesToBroadcast.clear();
        });
    }

    public static void onBlockUpdate(final NewChunkHolder newChunkHolder, final Packet<?> packet) {
        if (newChunkHolder == null) {
            // Chunk is still loading
            return;
        }

        ChunkAccess chunk = newChunkHolder.getCurrentChunk();

        if (chunk instanceof final ImposterProtoChunk imposterProtoChunk) {
            chunk = imposterProtoChunk.getWrapped();
        }

        if (chunk == null) {
            LOGGER.warn("A " + packet.getClass().getSimpleName() + " occurred on an unloaded chunk " + newChunkHolder);
            return;
        }
        if (blockUpdateChunk == null) { // Don't broadcast the update to other servers if we're handling an update
            MultiPaper.broadcastPacketToExternalServers(newChunkHolder.externalSubscribers, () -> new SendUpdatePacket(newChunkHolder.world.uuid, packet));
        }
    }

    public static ChunkAccess blockUpdateChunk = null;
    private static NewChunkHolder holder = null;
    public static void handleBlockUpdate(final UUID world, final Packet<?> packet, final int depth) {
        holder = null;
        blockUpdateChunk = null;
        ChunkAccess tempChunk = null;
        final CraftWorld bukkitWorld = ((CraftWorld) Bukkit.getWorld(world));
        final ServerLevel level = bukkitWorld != null? bukkitWorld.getHandle() : null;
        if (level == null) {
            return;
        } else if (packet instanceof final ClientboundBlockUpdatePacket update) {
            holder = MultiPaper.getChunkHolder(world, update.getPos());
        } else if (packet instanceof final ClientboundSectionBlocksUpdatePacket update) {
            update.runUpdates((pos, state) -> {
                if (holder == null) holder = MultiPaper.getChunkHolder(world, pos);
            });
        } else if (packet instanceof final ClientboundBlockEntityDataPacket update) {
            holder = MultiPaper.getChunkHolder(world, update.getPos());
        } else if (packet instanceof final ClientboundLightUpdatePacket update) {
            holder = MultiPaper.getChunkHolder(world, update.getX(), update.getZ());
        }

        if (holder != null && holder.hasGenerationTask()) {
            holder.onCurrentChunkLoaded().thenRun(() -> {
                if (!Bukkit.isPrimaryThread()) {
                    new Exception("Loaded chunk " + holder.chunkX + "," + holder.chunkZ + ", outside of the main thread! (currentThread=" + Thread.currentThread() + ")").printStackTrace();
                }
                handleBlockUpdate(world, packet, depth);
            });
            return;
        }

        if (holder != null) {
            tempChunk = holder.getCurrentChunk();

            if (tempChunk instanceof final ImposterProtoChunk imposterProtoChunk) {
                tempChunk = imposterProtoChunk.getWrapped();
            }
        }

        if (holder != null) {
            // Only send changes we make below to players, not other servers
            holder.vanillaChunkHolder.addChangesToPlayersOnly = true;
        }

        // Set blockUpdateChunk here so that we can broadcast changes beforehand
        blockUpdateChunk = tempChunk;

        if (holder != null && level.getChunkIfLoaded(holder.chunkX, holder.chunkZ) != null) {
            // Chunk is loaded
            if (packet instanceof final ClientboundBlockUpdatePacket update) {
                setBlock(((LevelChunk) blockUpdateChunk), update.getPos(), update.getBlockState());
            } else if (packet instanceof final ClientboundSectionBlocksUpdatePacket update) {
                update.runUpdates((pos, state) -> {
                    setBlock(((LevelChunk) blockUpdateChunk), pos, state);
                });
            } else if (packet instanceof final ClientboundBlockEntityDataPacket update) {
                final BlockEntity existingBlockEntity = blockUpdateChunk.getBlockEntity(update.getPos());
                if (existingBlockEntity != null && existingBlockEntity.minecraftKey.toString().equals(update.getTag().getString("id"))) {
                    existingBlockEntity.load(update.getTag());
                    holder.vanillaChunkHolder.blockChanged(update.getPos());
                } else if (!blockUpdateChunk.getBlockState(update.getPos()).hasBlockEntity() && depth < 1) {
                    MCUtil.scheduleTask(1, () -> handleBlockUpdate(world, packet, depth + 1));
                } else {
                    blockUpdateChunk.removeBlockEntity(update.getPos());
                    blockUpdateChunk.setBlockEntityNbt(update.getTag());
                    blockUpdateChunk.getBlockEntity(update.getPos());
                    holder.vanillaChunkHolder.blockChanged(update.getPos());
                }
            }
        } else if (blockUpdateChunk != null) {
            // Chunk is not loaded
            if (packet instanceof final ClientboundBlockUpdatePacket update) {
                setBlockInUnloadedChunk(blockUpdateChunk, update.getPos(), update.getBlockState());
            } else if (packet instanceof final ClientboundSectionBlocksUpdatePacket update) {
                update.runUpdates((pos, state) -> {
                    setBlockInUnloadedChunk(blockUpdateChunk, pos, state);
                });
            } else if (packet instanceof final ClientboundBlockEntityDataPacket update) {
                blockUpdateChunk.removeBlockEntity(update.getPos());
                if (blockUpdateChunk instanceof final LevelChunk levelChunk && levelChunk.blockEntitiesToLoad != null) {
                    levelChunk.blockEntitiesToLoad.add(update.getTag());
                } else {
                    blockUpdateChunk.setBlockEntityNbt(update.getTag());
                    blockUpdateChunk.getBlockEntity(update.getPos());
                }
            } else if (packet instanceof final ClientboundLightUpdatePacket update) {
                handleLightUpdatePacket(level, blockUpdateChunk, update);
            }
        }

        if (holder != null) {
            holder.vanillaChunkHolder.addChangesToPlayersOnly = false;
        }

        blockUpdateChunk = null;
    }

    private static void setBlockInUnloadedChunk(final ChunkAccess chunk, final BlockPos pos, final BlockState blockState) {
        if (chunk instanceof final LevelChunk levelChunk) {
            levelChunk.setBlockState(pos, blockState, false, false);
        } else {
            chunk.setBlockState(pos, blockState, false);
        }
    }

    private static void setBlock(final LevelChunk chunk, final BlockPos pos, final BlockState blockState) {
        final BlockState oldState = chunk.setBlockState(pos, blockState, false, false);
        holder.vanillaChunkHolder.blockChanged(pos);

        if (oldState != null && blockState != oldState && (blockState.getLightBlock(chunk, pos) != oldState.getLightBlock(chunk, pos) || blockState.getLightEmission() != oldState.getLightEmission() || blockState.useShapeForLightOcclusion() || oldState.useShapeForLightOcclusion())) {
            chunk.level.getProfiler().push("queueCheckLightExternalUpdate");
            chunk.level.getChunkSource().getLightEngine().checkBlock(pos);
            chunk.level.getProfiler().pop();
        }
    }

    // From the client
    private static void handleLightUpdatePacket(final ServerLevel level, final ChunkAccess chunk, final ClientboundLightUpdatePacket packet) {
        final int i = packet.getX();
        final int j = packet.getZ();
        final LevelLightEngine levellightengine = level.getChunkSource().getLightEngine();
        final BitSet bitset = packet.getLightData().getSkyYMask();
        final BitSet bitset1 = packet.getLightData().getEmptySkyYMask();
        final Iterator<byte[]> iterator = packet.getLightData().getSkyUpdates().iterator();
        readSectionList(chunk, i, j, levellightengine, LightLayer.SKY, bitset, bitset1, iterator);
        final BitSet bitset2 = packet.getLightData().getBlockYMask();
        final BitSet bitset3 = packet.getLightData().getEmptyBlockYMask();
        final Iterator<byte[]> iterator1 = packet.getLightData().getBlockUpdates().iterator();
        readSectionList(chunk, i, j, levellightengine, LightLayer.BLOCK, bitset2, bitset3, iterator1);
    }

    // From the client
    private static void readSectionList(final ChunkAccess chunk, final int i, final int j, final LevelLightEngine levelLightEngine, final LightLayer lightLayer, final BitSet bitset2, final BitSet bitset3, final Iterator<byte[]> iterator1) {
        for(int k = 0; k < levelLightEngine.getLightSectionCount(); ++k) {
            final int l = levelLightEngine.getMinLightSection() + k;
            final boolean flag = bitset2.get(k);
            final boolean flag1 = bitset3.get(k);
            if (flag || flag1) {
                if (lightLayer == LightLayer.BLOCK) {
                    chunk.getBlockNibbles()[k] = flag? new SWMRNibbleArray(iterator1.next().clone()) : new SWMRNibbleArray();
                } else if (lightLayer == LightLayer.SKY) {
                    chunk.getSkyNibbles()[k] = flag ? new SWMRNibbleArray(iterator1.next().clone()) : new SWMRNibbleArray();
                }
            }
        }
    }
}
