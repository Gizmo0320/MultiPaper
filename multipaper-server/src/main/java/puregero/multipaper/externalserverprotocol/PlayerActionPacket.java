package puregero.multipaper.externalserverprotocol;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import net.minecraft.network.ConnectionProtocol;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.PacketFlow;
import net.minecraft.network.protocol.game.ServerGamePacketListener;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import puregero.multipaper.ExternalServerConnection;
import puregero.multipaper.MultiPaper;

import java.util.UUID;

public class PlayerActionPacket extends ExternalServerPacket {

    private static final Logger LOGGER = LogManager.getLogger(PlayerActionPacket.class.getSimpleName());

    private final UUID uuid;
    private final Packet<ServerGamePacketListener> action;

    public PlayerActionPacket(ServerPlayer player, Packet<ServerGamePacketListener> action) {
        this.uuid = player.getUUID();
        this.action = action;
    }

    public PlayerActionPacket(FriendlyByteBuf in) {
        uuid = in.readUUID();

        ByteBuf buf = Unpooled.wrappedBuffer(in.readByteArray());
        FriendlyByteBuf friendlyByteBuf = new FriendlyByteBuf(buf);
        int packetId = friendlyByteBuf.readVarInt();
        action = (Packet<ServerGamePacketListener>) ConnectionProtocol.PLAY.createPacket(PacketFlow.SERVERBOUND, packetId, friendlyByteBuf);
    }

    @Override
    public void write(FriendlyByteBuf out) {
        out.writeUUID(uuid);

        ConnectionProtocol protocol = ConnectionProtocol.getProtocolForPacket(action);
        Integer id = protocol.getPacketId(PacketFlow.SERVERBOUND, action);
        ByteBuf buf = Unpooled.buffer();
        FriendlyByteBuf friendlyByteBuf = new FriendlyByteBuf(buf);
        friendlyByteBuf.writeVarInt(id);
        action.write(friendlyByteBuf);
        out.writeByteArray(buf.array());
    }

    @Override
    public void handle(ExternalServerConnection connection) {
        MultiPaper.runSync(() -> {
            ServerPlayer player = MinecraftServer.getServer().getPlayerList().getPlayer(uuid);

            if (player == null) {
                LOGGER.warn("Tried to run an action on a non-existent player with uuid " + uuid);
                return;
            }

            action.handle(player.connection);
        });
    }
}
