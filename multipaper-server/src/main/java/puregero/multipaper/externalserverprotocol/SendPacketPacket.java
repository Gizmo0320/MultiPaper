package puregero.multipaper.externalserverprotocol;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import net.minecraft.network.ConnectionProtocol;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.PacketFlow;
import net.minecraft.network.protocol.game.ClientboundPlayerPositionPacket;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import puregero.multipaper.ExternalServerConnection;
import puregero.multipaper.MultiPaper;
import puregero.multipaper.MultiPaperInventoryHandler;

import java.util.List;
import java.util.UUID;

public class SendPacketPacket extends ExternalServerPacket {

    private static final Logger LOGGER = LogManager.getLogger(SendPacketPacket.class.getSimpleName());

    private final UUID[] uuids;
    private final Packet<?> packet;

    public SendPacketPacket(List<? extends ServerPlayer> players, Packet<?> packet) {
        this.uuids = new UUID[players.size()];
        for (int i = 0; i < players.size(); i++) {
            this.uuids[i] = players.get(i).getUUID();
        }
        this.packet = packet;
    }

    public SendPacketPacket(ServerPlayer player, Packet<?> packet) {
        this.uuids = new UUID[1];
        this.uuids[0] = player.getUUID();
        this.packet = packet;
    }

    public SendPacketPacket(FriendlyByteBuf in) {
        uuids = new UUID[in.readInt()];
        for (int i = 0; i < uuids.length; i++) {
            this.uuids[i] = in.readUUID();
        }

        byte[] bytes = in.readByteArray();
        ByteBuf buf = Unpooled.wrappedBuffer(bytes);
        FriendlyByteBuf friendlyByteBuf = new FriendlyByteBuf(buf);
        int packetId = friendlyByteBuf.readVarInt();
        packet = ConnectionProtocol.PLAY.createPacket(PacketFlow.CLIENTBOUND, packetId, friendlyByteBuf);
    }

    @Override
    public void write(FriendlyByteBuf out) {
        out.writeInt(uuids.length);
        for (UUID uuid : uuids) {
            out.writeUUID(uuid);
        }

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
        if (packet instanceof ClientboundPlayerPositionPacket) {
            MultiPaper.runSync(() -> doHandle(connection));
        } else {
            doHandle(connection);
        }
    }

    public void doHandle(ExternalServerConnection connection) {
        for (UUID uuid : uuids) {
            ServerPlayer player = MinecraftServer.getServer().getPlayerList().getPlayer(uuid);

            if (player == null) {
                LOGGER.warn("Tried to send a packet to a non-existent player uuid " + uuid);
                continue;
            }

            if (MultiPaperInventoryHandler.handlePacketFromExternalServer(connection.externalServer, player, packet)) {
                continue;
            }

            player.connection.send(packet);
        }
    }
}
