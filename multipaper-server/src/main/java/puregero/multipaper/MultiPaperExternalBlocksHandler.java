package puregero.multipaper;

import io.papermc.paper.chunk.system.scheduling.NewChunkHolder;
import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.material.Fluid;
import net.minecraft.world.ticks.LevelChunkTicks;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import puregero.multipaper.externalserverprotocol.SendTickListPacket;
import puregero.multipaper.mastermessagingprotocol.ChunkKey;
import puregero.multipaper.mastermessagingprotocol.messages.masterbound.RequestChunkOwnershipMessage;
import puregero.multipaper.mastermessagingprotocol.messages.serverbound.BooleanMessageReply;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.concurrent.CompletableFuture;

public class MultiPaperExternalBlocksHandler {

    private static final Logger LOGGER = LogManager.getLogger(MultiPaperExternalBlocksHandler.class.getSimpleName());
    private static final HashSet<NewChunkHolder> scheduledChunks = new HashSet<>();
    private static final List<CompletableFutureWithKey<HashSet<NewChunkHolder>, Boolean>> takingControlOf = new ArrayList<>();

    public static void onBlockScheduled(ServerLevel level, BlockPos pos) {
        NewChunkHolder newChunkHolder = MultiPaper.getChunkHolder(level, pos);

        if (MultiPaper.isChunkExternal(newChunkHolder)) {
            scheduledChunks.add(newChunkHolder);
        }
    }

    public static void tick() {
        takingControlOf.clear();

        while (!scheduledChunks.isEmpty()) {
            Iterator<NewChunkHolder> iterator = scheduledChunks.iterator();
            NewChunkHolder newChunkHolder = iterator.next();
            iterator.remove();

            if (!newChunkHolder.hasExternalLockRequest) {
                // We aren't ticking this chunk
                sendTickListTo(newChunkHolder);
                continue;
            }

            HashSet<NewChunkHolder> neighbours = new HashSet<>();

            fillTickingNeighbours(newChunkHolder, neighbours);

            boolean hasALocalChunk = false;

            for (NewChunkHolder neighbour : neighbours) {
                if (MultiPaper.isChunkLocal(neighbour)) {
                    hasALocalChunk = true;
                    break;
                }
            }

            if (hasALocalChunk) {
                takingControlOf.add(requestChunkOwnership(neighbours));
            } else {
                for (NewChunkHolder neighbour : neighbours) {
                    sendTickListTo(neighbour);
                }
            }
        }

        if (!takingControlOf.isEmpty()) {
            CompletableFuture<Void> allFuture = CompletableFuture.allOf(takingControlOf.toArray(CompletableFuture[]::new));

            // Wait for the control process to complete before continuing the tick so that this doesn't mess up the next tick
            MinecraftServer.getServer().managedBlock(allFuture::isDone);

            for (CompletableFutureWithKey<HashSet<NewChunkHolder>, Boolean> completableFuture : takingControlOf) {
                if (!completableFuture.join()) {
                    // Failed to take control of the chunks, send the tick lists to their owners
                    for (NewChunkHolder newChunkHolder : completableFuture.getKey()) {
                        sendTickListTo(newChunkHolder);
                    }
                }
            }
        }
    }

    private static void sendTickListTo(NewChunkHolder newChunkHolder) {
        if (MultiPaper.isChunkExternal(newChunkHolder)) {
            if (newChunkHolder.getCurrentChunk() instanceof LevelChunk levelChunk) {
                newChunkHolder.externalOwner.getConnection().send(new SendTickListPacket(levelChunk));
                ((LevelChunkTicks<Block>) levelChunk.getBlockTicks()).removeIf(schedule -> true);
                ((LevelChunkTicks<Fluid>) levelChunk.getFluidTicks()).removeIf(schedule -> true);
            } else {
                LOGGER.warn("Tried sending a tick list to a chunk that isn't a level chunk: " + newChunkHolder);
            }
        }
    }

    private static void fillTickingNeighbours(NewChunkHolder newChunkHolder, HashSet<NewChunkHolder> neighbours) {
        for (int x = -1; x <= 1; x ++) {
            for (int z = -1; z <= 1; z ++) {
                NewChunkHolder neighbour = MultiPaper.getChunkHolder(newChunkHolder.world, newChunkHolder.chunkX + x, newChunkHolder.chunkZ + z);

                if (neighbour != null && !neighbours.contains(neighbour) && neighbour.hasExternalLockRequest &&
                        neighbour.getCurrentChunk() instanceof LevelChunk levelChunk &&
                        (levelChunk.getBlockTicks().count() > 0 || levelChunk.getFluidTicks().count() > 0)) {
                    scheduledChunks.remove(neighbour);
                    neighbours.add(neighbour);
                    fillTickingNeighbours(neighbour, neighbours);
                }
            }
        }
    }

    private static CompletableFutureWithKey<HashSet<NewChunkHolder>, Boolean> requestChunkOwnership(HashSet<NewChunkHolder> neighbours) {
        CompletableFutureWithKey<HashSet<NewChunkHolder>, Boolean> future = new CompletableFutureWithKey<>(neighbours);

        String world = null;
        ChunkKey[] chunkKeys = new ChunkKey[neighbours.size()];
        int i = 0;

        for (NewChunkHolder newChunkHolder : neighbours) {
            if (world == null) {
                world = newChunkHolder.world.getWorld().getName();
            }

            chunkKeys[i++] = new ChunkKey(newChunkHolder.world.getWorld().getName(), newChunkHolder.chunkX, newChunkHolder.chunkZ);
        }

        MultiPaper.getConnection().sendAndAwaitReply(new RequestChunkOwnershipMessage(world, chunkKeys), BooleanMessageReply.class).thenAccept(message -> future.complete(message.result));

        return future;
    }

    private static class CompletableFutureWithKey<K, V> extends CompletableFuture<V> {
        private final K key;

        private CompletableFutureWithKey(K key) {
            this.key = key;
        }

        public K getKey() {
            return key;
        }
    }

}
