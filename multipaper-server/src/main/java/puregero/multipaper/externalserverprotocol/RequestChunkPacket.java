package puregero.multipaper.externalserverprotocol;

import io.papermc.paper.chunk.system.scheduling.NewChunkHolder;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ThreadedLevelLightEngine;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.ImposterProtoChunk;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.storage.ChunkSerializer;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.bukkit.Bukkit;
import org.bukkit.World;
import org.bukkit.craftbukkit.CraftWorld;
import puregero.multipaper.ExternalServer;
import puregero.multipaper.ExternalServerConnection;
import puregero.multipaper.MultiPaper;

import java.util.ConcurrentModificationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

public class RequestChunkPacket extends ExternalServerPacket {

    private static final Logger LOGGER = LogManager.getLogger(RequestChunkPacket.class.getSimpleName());
    public static ExternalServer blocker = null;

    private final String world;
    private final int cx;
    private final int cz;

    public RequestChunkPacket(String world, int cx, int cz) {
        this.world = world;
        this.cx = cx;
        this.cz = cz;
    }

    public RequestChunkPacket(FriendlyByteBuf in) {
        world = in.readUtf();
        cx = in.readInt();
        cz = in.readInt();
    }

    @Override
    public void write(FriendlyByteBuf out) {
        out.writeUtf(world);
        out.writeInt(cx);
        out.writeInt(cz);
    }

    @Override
    public void handle(ExternalServerConnection connection) {
        World bukkitWorld = Bukkit.getWorld(world);

        if (!(bukkitWorld instanceof CraftWorld craftWorld)) {
            LOGGER.warn(connection.externalServer.getName() + " is requesting chunk " + world + "," + cx + "," + cz + " but we don't have the world " + world + " loaded.");
            connection.send(new SendChunkPacket(world, cx, cz, null));
            return;
        }

        ServerLevel level = craftWorld.getHandle();
        NewChunkHolder newChunkHolder = MultiPaper.getChunkHolder(world, cx, cz);

        if (newChunkHolder == null) {
            LOGGER.warn(connection.externalServer.getName() + " is requesting chunk " + world + "," + cx + "," + cz + " but we aren't trying to load it.");
            connection.send(new SendChunkPacket(world, cx, cz, null));
            return;
        }

        CompletableFuture<ChunkAccess> future = newChunkHolder.onCurrentChunkLoaded();

        CompletableFuture<ChunkAccess> futureToWaitOn = future;
        if (blocker == connection.externalServer) {
            ChunkAccess access = newChunkHolder.getCurrentChunk();
            if (access != null) {
                futureToWaitOn = CompletableFuture.completedFuture(newChunkHolder.getCurrentChunk());
            } else {
                connection.send(new SendChunkPacket(world, cx, cz, null));
                return;
            }
        }

        futureToWaitOn.thenAccept(chunk -> {
            if (future != newChunkHolder.onCurrentChunkLoaded()) {
                // The future has been updated, try again
                handle(connection);
                return;
            }

            if (chunk == null) {
                LOGGER.warn(connection.externalServer.getName() + " is requesting chunk " + world + "," + cx + "," + cz + " but we don't have it loaded.");
                connection.send(new SendChunkPacket(world, cx, cz, null));
                return;
            }

            CompletableFuture<Void> lightFuture = ((ThreadedLevelLightEngine) level.getLightEngine()).theLightEngine.lightQueue.getChunkFuture(newChunkHolder.vanillaChunkHolder.getPos());

            if (!lightFuture.isDone()) {
                lightFuture.thenRunAsync(() -> handle(connection));
                return;
            }

            try {
                ListTag entitiesToLoad = null;
                ListTag blockEntitiesToLoad = null;

                ChunkAccess fullChunk = chunk instanceof ImposterProtoChunk imposterProtoChunk ? imposterProtoChunk.getWrapped() : chunk;

                if (fullChunk instanceof LevelChunk levelChunk) {
                    // Cache these tags in case they get deleted while we serialize the chunk (multi-threaded fun!)
                    entitiesToLoad = levelChunk.entitiesToLoad;
                    blockEntitiesToLoad = levelChunk.blockEntitiesToLoad;
                }

                CompoundTag tag = ChunkSerializer.write(level, chunk);

                if (entitiesToLoad != null) {
                    tag.put("entities", entitiesToLoad);
                }
                if (blockEntitiesToLoad != null) {
                    tag.put("block_entities", blockEntitiesToLoad);
                }

                connection.send(new SendChunkPacket(world, cx, cz, tag));
            } catch (ConcurrentModificationException e) {
                LOGGER.warn("Got ConcurrentModificationException while sending chunk, sending it in main thread instead");
                MultiPaper.runSync(() -> handle(connection));
            }
        })
        // Timeout instantly if this server is blocking our chunk loading, as this is probably also blocking their chunk loading
        .orTimeout(15, TimeUnit.SECONDS).exceptionally(throwable -> {
            if (throwable instanceof TimeoutException) {
                LOGGER.warn("Timed out while sending chunk " + world + "," + cx + "," + cz);
            } else {
                LOGGER.warn("Error while sending chunk " + world + "," + cx + "," + cz, throwable);
            }

            connection.send(new SendChunkPacket(world, cx, cz, null));
            return null;
        });
    }
}
