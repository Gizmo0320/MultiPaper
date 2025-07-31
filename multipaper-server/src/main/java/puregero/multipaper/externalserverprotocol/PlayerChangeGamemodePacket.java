package puregero.multipaper.externalserverprotocol;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.GameType;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import puregero.multipaper.ExternalServerConnection;
import puregero.multipaper.MultiPaper;

import java.util.UUID;

public class PlayerChangeGamemodePacket extends ExternalServerPacket {

    private static final Logger LOGGER = LogManager.getLogger(PlayerChangeGamemodePacket.class.getSimpleName());

    private final UUID uuid;
    private final GameType gamemode;

    public PlayerChangeGamemodePacket(ServerPlayer player) {
        this.uuid = player.getUUID();
        this.gamemode = player.gameMode.getGameModeForPlayer();
    }

    public PlayerChangeGamemodePacket(FriendlyByteBuf in) {
        uuid = in.readUUID();
        gamemode = GameType.byId(in.readByte());
    }

    @Override
    public void write(FriendlyByteBuf out) {
        out.writeUUID(uuid);
        out.writeByte(gamemode.getId());
    }

    @Override
    public void handle(ExternalServerConnection connection) {
        MultiPaper.runSync(() -> {
            ServerPlayer player = MinecraftServer.getServer().getPlayerList().getPlayer(uuid);

            if (player == null) {
                LOGGER.warn("Could not find player " + uuid);
                return;
            }

            player.gameMode.changeGameModeForPlayer(gamemode);
        });
    }
}
