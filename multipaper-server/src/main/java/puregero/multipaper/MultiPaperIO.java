package puregero.multipaper;

import ca.spottedleaf.concurrentutil.executor.Cancellable;
import ca.spottedleaf.concurrentutil.executor.standard.PrioritisedExecutor;
import com.mojang.logging.LogUtils;
import io.papermc.paper.chunk.system.io.RegionFileIOThread;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtIo;
import net.minecraft.server.level.ServerLevel;
import org.slf4j.Logger;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.PriorityQueue;
import java.util.Queue;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.BiConsumer;

public class MultiPaperIO {

    private static final Logger LOGGER = LogUtils.getClassLogger();
    private static final int CONCURRENT_READS = 16;
    private static final Executor EVENT_LOOP = Executors.newSingleThreadExecutor();

    private static final List<ScheduledChunkRead> ongoingReads = new ArrayList<>();

    private static final Map<ChunkRegionKey, ScheduledChunkRead> scheduledChunkReads = new HashMap<>();
    private static final Queue<ScheduledChunkRead> priorityQueue = new PriorityQueue<>(Comparator.comparingInt(a -> a.priority.ordinal()));

    public static Cancellable loadDataAsync(final ServerLevel world, final int chunkX, final int chunkZ,
                                            final RegionFileIOThread.RegionFileType type, final BiConsumer<CompoundTag, Throwable> onComplete,
                                            final boolean intendingToBlock, final PrioritisedExecutor.Priority priority) {
        String path = switch (type) {
            case CHUNK_DATA -> "region";
            case ENTITY_DATA -> "entities";
            case POI_DATA -> "poi";
        };
        LoadTask loadTask = new LoadTask(world.getWorld().getName(), path, chunkX, chunkZ, onComplete, intendingToBlock, priority);
        CompletableFuture.runAsync(() -> schedule(loadTask), EVENT_LOOP);
        return loadTask;
    }

    private static void processQueue() {
        ScheduledChunkRead scheduledChunkRead = priorityQueue.peek();

        if (scheduledChunkRead != null && (ongoingReads.size() < CONCURRENT_READS || scheduledChunkRead.priority == PrioritisedExecutor.Priority.BLOCKING)) {
            priorityQueue.poll().execute();
        }
    }

    private static void schedule(LoadTask loadTask) {
        ScheduledChunkRead scheduledChunkRead = scheduledChunkReads.computeIfAbsent(loadTask.chunkRegionKey, ScheduledChunkRead::new);
        scheduledChunkRead.dependants.add(loadTask);
        scheduledChunkRead.recalculatePriority();
    }

    private static boolean cancel(LoadTask loadTask) {
        ScheduledChunkRead scheduledChunkRead = scheduledChunkReads.computeIfAbsent(loadTask.chunkRegionKey, ScheduledChunkRead::new);
        if (scheduledChunkRead.priority == PrioritisedExecutor.Priority.COMPLETING) {
            return false;
        }

        scheduledChunkRead.dependants.remove(loadTask);
        scheduledChunkRead.recalculatePriority();
        return true;
    }

    private static class ScheduledChunkRead {
        final ChunkRegionKey chunkRegionKey;
        final Set<LoadTask> dependants = new HashSet<>();
        PrioritisedExecutor.Priority priority = null;
        boolean intendingToBlock = false;

        ScheduledChunkRead(ChunkRegionKey chunkRegionKey) {
            this.chunkRegionKey = chunkRegionKey;
        }

        void recalculatePriority() {
            if (priority == PrioritisedExecutor.Priority.COMPLETING) {
                // Either in progress or completed, can't change priority now
                return;
            }

            PrioritisedExecutor.Priority oldPriority = priority;

            priority = PrioritisedExecutor.Priority.IDLE;
            intendingToBlock = false;
            for (LoadTask loadTask : dependants) {
                if (loadTask.priority.ordinal() < priority.ordinal()) {
                    priority = loadTask.priority;
                }
                if (loadTask.intendingToBlock) {
                    intendingToBlock = true;
                }
            }

            if (priority != oldPriority) {
                if (oldPriority != null) {
                    priorityQueue.remove(this);
                }

                if (dependants.isEmpty()) {
                    scheduledChunkReads.remove(chunkRegionKey);
                    return;
                }

                priorityQueue.add(this);
                processQueue();
            }
        }

        void execute() {
            if (priority == PrioritisedExecutor.Priority.COMPLETING) {
                throw new RuntimeException("Executing twice!!!");
            }

            priority = PrioritisedExecutor.Priority.COMPLETING;
            ongoingReads.add(this);

            read();
        }

        void read() {
            MultiPaper.readRegionFileAsync(chunkRegionKey.getName(), chunkRegionKey.getPath(), chunkRegionKey.getX(), chunkRegionKey.getZ()).thenApply(in -> {
                try {
                    return in == null ? null : NbtIo.read(in);
                } catch (IOException e) {
                    throw new RuntimeException(e);
                }
            }).thenAcceptAsync(nbt -> {
                complete(nbt, null);
            }, EVENT_LOOP).orTimeout(20, TimeUnit.SECONDS).exceptionallyAsync(throwable -> {
                if (throwable instanceof TimeoutException) {
                    LOGGER.warn("Timed out reading " + chunkRegionKey.getName() + "," + chunkRegionKey.getPath() + "," + chunkRegionKey.getX() + "," + chunkRegionKey.getZ() + ", retrying...");
                    read();
                } else {
                    LOGGER.error("Error reading " + chunkRegionKey.getName() + "," + chunkRegionKey.getPath() + "," + chunkRegionKey.getX() + "," + chunkRegionKey.getZ(), throwable);
                    complete(null, throwable);
                }
                return null;
            }, EVENT_LOOP);
        }

        void complete(CompoundTag compoundTag, Throwable throwable) {
            ongoingReads.remove(this);
            scheduledChunkReads.remove(chunkRegionKey);
            Iterator<LoadTask> loadTaskIterator = dependants.iterator();
            while (loadTaskIterator.hasNext()) {
                LoadTask loadTask = loadTaskIterator.next();
                CompletableFuture.runAsync(() -> loadTask.onComplete.accept(compoundTag, throwable));
                loadTaskIterator.remove();
            }
            processQueue();
        }
    }

    private static class LoadTask implements Cancellable {
        private final ChunkRegionKey chunkRegionKey;
        private final BiConsumer<CompoundTag, Throwable> onComplete;
        private final boolean intendingToBlock;
        private final PrioritisedExecutor.Priority priority;

        LoadTask(String world, String path, int chunkX, int chunkZ, BiConsumer<CompoundTag, Throwable> onComplete, boolean intendingToBlock, PrioritisedExecutor.Priority priority) {
            this.chunkRegionKey = new ChunkRegionKey(world, path, chunkX, chunkZ);
            this.onComplete = onComplete;
            this.intendingToBlock = intendingToBlock;
            this.priority = priority;
        }

        @Override
        public boolean cancel() {
            return CompletableFuture.supplyAsync(() -> MultiPaperIO.cancel(this), EVENT_LOOP).join();
        }
    }
}
