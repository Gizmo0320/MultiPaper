package puregero.multipaper.externalserverprotocol;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import puregero.multipaper.ExternalServerConnection;
import puregero.multipaper.MultiPaper;

import java.util.UUID;

public class PlayerExperienceUpdatePacket extends ExternalServerPacket {
    private static final Logger LOGGER = LogManager.getLogger(PlayerExperienceUpdatePacket.class.getSimpleName());

    private final UUID uuid;
    private final float progress;
    private final int total;
    private final int level;

    public PlayerExperienceUpdatePacket(ServerPlayer player, float progress, int total, int level) {
        this.uuid = player.getUUID();
        this.progress = progress;
        this.total = total;
        this.level = level;
    }

    public PlayerExperienceUpdatePacket(FriendlyByteBuf in) {
        uuid = in.readUUID();
        progress = in.readFloat();
        total = in.readInt();
        level = in.readInt();
    }

    @Override
    public void write(FriendlyByteBuf out) {
        out.writeUUID(uuid);
        out.writeFloat(progress);
        out.writeInt(total);
        out.writeInt(level);
    }

    @Override
    public void handle(ExternalServerConnection connection) {
        MultiPaper.runSync(() -> {
            ServerPlayer player = MinecraftServer.getServer().getPlayerList().getPlayer(uuid);

            if (player == null) {
                LOGGER.warn("Could not find player " + uuid);
                return;
            }

            player.experienceProgress = player.lastExperienceProgress = progress;
            player.totalExperience = player.lastTotalExperience = total;
            player.experienceLevel = player.lastExperienceLevel = level;
        });
    }
}
