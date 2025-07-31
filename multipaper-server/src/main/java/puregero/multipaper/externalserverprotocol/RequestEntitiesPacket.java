package puregero.multipaper.externalserverprotocol;

import io.papermc.paper.chunk.system.scheduling.NewChunkHolder;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.protocol.game.ClientboundTeleportEntityPacket;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.bukkit.Bukkit;
import org.bukkit.craftbukkit.CraftWorld;
import org.bukkit.craftbukkit.scheduler.CraftScheduler;
import puregero.multipaper.ExternalServerConnection;
import puregero.multipaper.MultiPaper;
import puregero.multipaper.config.MultiPaperConfiguration;

import java.util.ConcurrentModificationException;
import java.util.concurrent.CompletableFuture;

public class RequestEntitiesPacket extends ExternalServerPacket {

    private static final Logger LOGGER = LogManager.getLogger(RequestEntitiesPacket.class.getSimpleName());

    private final String world;
    private final int cx;
    private final int cz;

    public RequestEntitiesPacket(String world, int cx, int cz) {
        this.world = world;
        this.cx = cx;
        this.cz = cz;
    }

    public RequestEntitiesPacket(FriendlyByteBuf in) {
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
        sendChunkLater(connection, world, cx, cz, 0);
    }

    private void sendChunkLater(ExternalServerConnection connection, String world, int cx, int cz, int depth) {
        ServerLevel level = ((CraftWorld) Bukkit.getWorld(world)).getHandle();
        NewChunkHolder chunkHolder = level.chunkTaskScheduler.chunkHolderManager.getChunkHolder(ChunkPos.asLong(cx, cz));
        if (chunkHolder == null || chunkHolder.getEntityChunk() == null) {
            if (depth >= 20 || Bukkit.isStopping()) {
                LOGGER.warn(connection.externalServer.getName() + " is requesting entities " + world + "," + cx + "," + cz + " but we timed out waiting for them to load.");
                connection.send(new SendEntitiesPacket(world, cx, cz, null));
                return;
            }
            ((CraftScheduler) Bukkit.getScheduler()).scheduleInternalTask(() -> {
                sendChunkLater(connection, world, cx, cz, depth + 1);
            }, 1, "ExternalServerConnection-sendEntitiesLater");
        } else {
            try {
                connection.send(new SendEntitiesPacket(level.getWorld().getName(), cx, cz, SendEntitiesPacket.getEntities(level, new ChunkPos(cx, cz), player -> {
                    if (MultiPaperConfiguration.get().optimizations.reducePlayerPositionUpdatesInUnloadedChunks) {
                        // This player is now in a loaded chunk, ensure its position is up to date
                        connection.send(new EntityUpdatePacket(player, new ClientboundTeleportEntityPacket(player)));
                    }
                })));
            } catch (ConcurrentModificationException e) {
                LOGGER.warn("Got ConcurrentModificationException while sending entities, sending it in main thread instead");
                MultiPaper.runSync(() -> sendChunkLater(connection, world, cx, cz, depth));
            }
        }
    }
}
