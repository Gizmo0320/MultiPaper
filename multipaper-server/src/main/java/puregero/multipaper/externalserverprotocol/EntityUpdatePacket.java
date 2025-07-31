package puregero.multipaper.externalserverprotocol;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import net.minecraft.network.ConnectionProtocol;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.PacketFlow;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.entity.Visibility;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.bukkit.Bukkit;
import org.bukkit.World;
import org.bukkit.craftbukkit.CraftWorld;
import org.bukkit.craftbukkit.scheduler.CraftScheduler;
import puregero.multipaper.ExternalServerConnection;
import puregero.multipaper.MultiPaper;
import puregero.multipaper.MultiPaperEntitiesHandler;

import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public class EntityUpdatePacket extends ExternalServerPacket {

    private static final Logger LOGGER = LogManager.getLogger(EntityUpdatePacket.class.getSimpleName());

    // If anyone has a better idea for locking only certain threads rather than creating a set of the thread ids that need locking, plz contribute
    public static Set<Long> threadsWritingUpdatePackets = ConcurrentHashMap.newKeySet();

    private final UUID world;
    private final UUID uuid;
    private final Packet<?> packet;

    private final long chunkPos;

    public EntityUpdatePacket(Entity entity, Packet<?> packet) {
        this.world = ((ServerLevel) entity.level()).uuid;
        this.uuid = entity.getUUID();
        this.packet = packet;
        this.chunkPos = entity.chunkPosition().longKey;
    }

    public EntityUpdatePacket(FriendlyByteBuf in) {
        world = in.readUUID();
        uuid = in.readUUID();

        ByteBuf buf = Unpooled.wrappedBuffer(in.readByteArray());
        FriendlyByteBuf friendlyByteBuf = new FriendlyByteBuf(buf);
        int packetId = friendlyByteBuf.readVarInt();
        packet = ConnectionProtocol.PLAY.createPacket(PacketFlow.CLIENTBOUND, packetId, friendlyByteBuf);
        this.chunkPos = in.readLong();
    }

    @Override
    public void write(FriendlyByteBuf out) {
        out.writeUUID(world);
        out.writeUUID(uuid);

        ConnectionProtocol protocol = ConnectionProtocol.getProtocolForPacket(packet);
        Integer id = protocol.getPacketId(PacketFlow.CLIENTBOUND, packet);
        ByteBuf buf = Unpooled.buffer();
        FriendlyByteBuf friendlyByteBuf = new FriendlyByteBuf(buf);
        friendlyByteBuf.writeVarInt(id);
        threadsWritingUpdatePackets.add(Thread.currentThread().getId());
        packet.write(friendlyByteBuf);
        threadsWritingUpdatePackets.remove(Thread.currentThread().getId());
        out.writeByteArray(buf.array());
        out.writeLong(this.chunkPos);
    }

    @Override
    public void handle(ExternalServerConnection connection) {
        MultiPaper.runSync(() -> handleLater(connection, 0));
    }

    private void handleLater(ExternalServerConnection connection, int depth) {
        World bukkitWorld = Bukkit.getWorld(world);

        if (bukkitWorld instanceof CraftWorld craftWorld) {
            ServerLevel level = craftWorld.getHandle();
            Entity entity = level.getEntityLookup().getEntityIgnoringAccessible(uuid);
            if (entity == null) {
                if (EntityRemovePacket.removedEntities.containsKey(uuid)) {
                    return;
                }

                // If we can't find the entity, try again later,
                // the spawn entity packet is probably coming later

                // first we check if we really need to request the entity by checking if entity's chunk is loaded
                if (!level.getEntityLookup().isChunkLoaded(new ChunkPos(this.chunkPos))) {
                    return;
                }

                if (depth > 5) {
                    if (depth >= 20) {
                        LOGGER.warn("Could not find entity " + uuid + " for " + packet.getClass().getSimpleName() + ", requesting it");
                    }
                    RequestEntityPacket.requestEntity(connection, world, uuid);
                    return;
                }

                ((CraftScheduler) Bukkit.getScheduler()).scheduleInternalTask(() -> {
                    handleLater(connection, depth + 1);
                }, 1, "EntityUpdatePacket-handleLaters");
                return;
            }

            MultiPaperEntitiesHandler.handleEntityUpdate(connection, entity, packet);
        }
    }
}
