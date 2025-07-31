package puregero.multipaper.externalserverprotocol;

import net.minecraft.core.BlockPos;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import puregero.multipaper.ExternalServerConnection;
import puregero.multipaper.MultiPaper;

import java.util.UUID;

public class DestroyAndAckPacket extends ExternalServerPacket {

    private static final Logger LOGGER = LogManager.getLogger(DestroyAndAckPacket.class.getSimpleName());

    private final UUID uuid;
    private final BlockPos pos;
    private final int sequence;
    private final String reason;

    public DestroyAndAckPacket(ServerPlayer player, BlockPos pos, int sequence, String reason) {
        this.uuid = player.getUUID();
        this.pos = pos;
        this.sequence = sequence;
        this.reason = reason;
    }

    public DestroyAndAckPacket(FriendlyByteBuf in) {
        uuid = in.readUUID();
        pos = in.readBlockPos();
        sequence = in.readVarInt();
        reason = in.readUtf();
    }

    @Override
    public void write(FriendlyByteBuf out) {
        out.writeUUID(uuid);
        out.writeBlockPos(pos);
        out.writeVarInt(sequence);
        out.writeUtf(reason);
    }

    @Override
    public void handle(ExternalServerConnection connection) {
        MultiPaper.runSync(() -> {
            ServerPlayer player = MinecraftServer.getServer().getPlayerList().getPlayer(uuid);

            if (player == null) {
                LOGGER.warn(uuid + " tried to break a block but they aren't online!");
                return;
            }

            player.gameMode.destroyAndAck(pos, sequence, reason);
            player.destroyAndAckHandledByExternalServer = false;
            player.connection.ackBlockChangesUpTo(sequence);
        });
    }
}
