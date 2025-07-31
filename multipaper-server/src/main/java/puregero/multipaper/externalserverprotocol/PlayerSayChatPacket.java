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

public class PlayerSayChatPacket extends ExternalServerPacket {

    private static final Logger LOGGER = LogManager.getLogger(PlayerSayChatPacket.class.getSimpleName());

    private final UUID uuid;
    private final String message;

    public PlayerSayChatPacket(ServerPlayer player, String message) {
        this.uuid = player.getUUID();
        this.message = message;
    }

    public PlayerSayChatPacket(FriendlyByteBuf in) {
        this.uuid = in.readUUID();
        this.message = in.readUtf();
    }

    @Override
    public void write(FriendlyByteBuf out) {
        out.writeUUID(this.uuid);
        out.writeUtf(this.message);
    }

    @Override
    public void handle(ExternalServerConnection connection) {
        MultiPaper.runSync(() -> {
            ServerPlayer player = MinecraftServer.getServer().getPlayerList().getPlayer(uuid);

            if (player == null) {
                LOGGER.warn("Tried to run an action on a non-existent player with uuid " + uuid);
                return;
            }

            player.getBukkitEntity().chat(message);
        });
    }
}
