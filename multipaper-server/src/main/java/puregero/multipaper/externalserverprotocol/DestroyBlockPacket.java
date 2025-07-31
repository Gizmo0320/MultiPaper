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

public class DestroyBlockPacket extends ExternalServerPacket {

    private static final Logger LOGGER = LogManager.getLogger(DestroyBlockPacket.class.getSimpleName());

    private final UUID uuid;
    private final BlockPos pos;

    public DestroyBlockPacket(ServerPlayer player, BlockPos pos) {
        this.uuid = player.getUUID();
        this.pos = pos;
    }

    public DestroyBlockPacket(FriendlyByteBuf in) {
        uuid = in.readUUID();
        pos = in.readBlockPos();
    }

    @Override
    public void write(FriendlyByteBuf out) {
        out.writeUUID(uuid);
        out.writeBlockPos(pos);
    }

    @Override
    public void handle(ExternalServerConnection connection) {
        MultiPaper.runSync(() -> {
            ServerPlayer player = MinecraftServer.getServer().getPlayerList().getPlayer(uuid);

            if (player == null) {
                LOGGER.warn(uuid + " tried to break a block but they aren't online!");
                return;
            }

            player.gameMode.destroyBlock(pos);
        });
    }
}
