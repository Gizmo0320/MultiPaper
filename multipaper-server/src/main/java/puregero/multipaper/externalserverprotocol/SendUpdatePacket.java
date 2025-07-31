package puregero.multipaper.externalserverprotocol;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import net.minecraft.network.ConnectionProtocol;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.PacketFlow;
import net.minecraft.network.protocol.game.*;
import net.minecraft.server.level.ServerLevel;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import puregero.multipaper.ExternalServerConnection;
import puregero.multipaper.MultiPaper;
import puregero.multipaper.MultiPaperChunkHandler;
import puregero.multipaper.MultiPaperWorldBorderHandler;

import java.util.UUID;

public class SendUpdatePacket extends ExternalServerPacket {

    private static final Logger LOGGER = LogManager.getLogger(SendUpdatePacket.class.getSimpleName());

    private final UUID world;
    private final Packet<?> packet;

    public SendUpdatePacket(ServerLevel level, Packet<?> packet) {
        this(level.uuid, packet);
    }

    public SendUpdatePacket(UUID world, Packet<?> packet) {
        this.world = world;
        this.packet = packet;
    }

    public SendUpdatePacket(FriendlyByteBuf in) {
        world = in.readUUID();

        byte[] bytes = in.readByteArray();
        ByteBuf buf = Unpooled.wrappedBuffer(bytes);
        FriendlyByteBuf friendlyByteBuf = new FriendlyByteBuf(buf);
        friendlyByteBuf.maxNbtSize = Long.MAX_VALUE;
        int packetId = friendlyByteBuf.readVarInt();
        packet = ConnectionProtocol.PLAY.createPacket(PacketFlow.CLIENTBOUND, packetId, friendlyByteBuf);
    }

    @Override
    public void write(FriendlyByteBuf out) {
        out.writeUUID(world);

        ConnectionProtocol protocol = ConnectionProtocol.getProtocolForPacket(packet);
        Integer id = protocol.getPacketId(PacketFlow.CLIENTBOUND, packet);
        ByteBuf buf = Unpooled.buffer();
        FriendlyByteBuf friendlyByteBuf = new FriendlyByteBuf(buf);
        friendlyByteBuf.writeVarInt(id);
        packet.write(friendlyByteBuf);
        out.writeByteArray(buf.array());
    }

    @Override
    public void handle(ExternalServerConnection connection) {
        if (packet instanceof ClientboundBlockUpdatePacket || packet instanceof ClientboundSectionBlocksUpdatePacket || packet instanceof ClientboundLightUpdatePacket || packet instanceof ClientboundBlockEntityDataPacket) {
            MultiPaper.runSync(() -> MultiPaperChunkHandler.handleBlockUpdate(world, packet, 0));
        } else if (packet instanceof ClientboundSetBorderSizePacket || packet instanceof ClientboundSetBorderLerpSizePacket || packet instanceof ClientboundSetBorderCenterPacket || packet instanceof ClientboundSetBorderWarningDelayPacket || packet instanceof ClientboundSetBorderWarningDistancePacket) {
            MultiPaper.runSync(() -> MultiPaperWorldBorderHandler.handle(world, packet));
        } else {
            LOGGER.warn("Unhandled update packet of type " + packet.getClass().getSimpleName());
        }
    }
}
