package puregero.multipaper;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import puregero.multipaper.config.MultiPaperConfiguration;
import puregero.multipaper.mastermessagingprotocol.messages.masterbound.RequestEntityIdBlock;
import puregero.multipaper.mastermessagingprotocol.messages.serverbound.IntegerPairMessageReply;

import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Synchronise the entity ids between servers.
 *
 * Requirements: No two servers can assign the same entity id. A server must be
 * able to assign a new entity id instantly without waiting for an I/O
 * operation or anything.
 *
 * This solution: Allocate blocks of entity ids to servers. Eg, server1 gets
 * block [0, 1024), server2 gets block [1024, 2048), etc. The server will be
 * able to instantly assign an entity id from this block. An extra block will
 * be cached on each server so that it can go into use immediately if the
 * current block runs out of ids. If the cache is emptied, an async request
 * will be made to assign a new block of entity ids to refill the cache in
 * time for the next requirement for a new block.
 */
public class MultiPaperEntityIdManager {
    private static final Logger LOGGER = LogManager.getLogger(MultiPaperEntityIdManager.class.getSimpleName());
    private static AtomicInteger LOCAL_ENTITY_COUNTER = new AtomicInteger(1);
    private static Block entityIdBlock = null;
    private static Block nextEntityIdBlock = null;
    private static long nextBlockRequestSentTime = 0;
    private static CompletableFuture<Void> nextBlockRequest = CompletableFuture.completedFuture(null);

    private static synchronized void getNextBlock(Block oldBlock) {
        if (!Objects.equals(entityIdBlock, oldBlock)) {
            // The block has already been changed by another thread, abort
            return;
        }

        if (!nextBlockRequest.isDone()) {
            // Wait for an ongoing request for the next block
            LOGGER.warn("Waiting for more entity ids from the master. (The request for more entity ids was sent " + (System.currentTimeMillis() - nextBlockRequestSentTime) + "ms ago)");
            nextBlockRequest.join();
        }

        if (nextEntityIdBlock == null) {
            // There is no next block, request it. This is expected if the server has just started up.
            requestNextBlock().join();
        }

        LOCAL_ENTITY_COUNTER = new AtomicInteger(nextEntityIdBlock.min);

        entityIdBlock = nextEntityIdBlock;
        nextEntityIdBlock = null;

        // Begin the I/O request for the next block so that there's always one ready in the cache.
        requestNextBlock();
    }

    private static CompletableFuture<Void> requestNextBlock() {
        nextBlockRequestSentTime = System.currentTimeMillis();
        return nextBlockRequest = MultiPaper.getConnection().sendAndAwaitReply(new RequestEntityIdBlock(), IntegerPairMessageReply.class).thenAccept(reply -> {
            nextEntityIdBlock = new Block(reply.x, reply.y);
        });
    }

    // Generate sequential entity ids
    public static int[] next(int count) {
        if (MultiPaperConfiguration.get().syncSettings.syncEntityIds) {
            int[] ids = new int[count];
            int progress = 0;
            while (progress < count) {
                ids[progress] = next();
                if (progress > 0) {
                    if (ids[progress] != ids[progress - 1] + 1) {
                        ids[0] = ids[progress];
                        progress = 1;
                        continue;
                    }
                }
                progress++;
            }

            return ids;
        } else {
            int[] ids = new int[count];
            for (int i = 0; i < count; i++) {
                ids[i] = LOCAL_ENTITY_COUNTER.getAndIncrement();
            }
            return ids;
        }
    }

    public static int next() {
        if (MultiPaperConfiguration.get().syncSettings.syncEntityIds) {
            Block block = entityIdBlock;
            int id = LOCAL_ENTITY_COUNTER.getAndIncrement();
            if (block == null || !block.isInBounds(id)) { // Check the max id before the min id due to concurrent variable modification
                getNextBlock(block);
                return next();
            }
            return id;
        } else {
            return LOCAL_ENTITY_COUNTER.getAndIncrement();
        }
    }

    private record Block(int min, int max) {
        public boolean isInBounds(int i) {
            return i >= min && i < max;
        }
    }
}
