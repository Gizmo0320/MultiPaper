package puregero.multipaper.externalserverprotocol;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Player;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import puregero.multipaper.ExternalServerConnection;
import puregero.multipaper.MultiPaper;

import java.util.UUID;

public class PlayerResetAttackStrengthPacket extends ExternalServerPacket {

    private static final Logger LOGGER = LogManager.getLogger(PlayerResetAttackStrengthPacket.class.getSimpleName());
    private static boolean settingAttackStrength = false;

    private final UUID uuid;

    public PlayerResetAttackStrengthPacket(Player player) {
        this.uuid = player.getUUID();
    }

    public PlayerResetAttackStrengthPacket(FriendlyByteBuf in) {
        uuid = in.readUUID();
    }

    @Override
    public void write(FriendlyByteBuf out) {
        out.writeUUID(uuid);
    }

    @Override
    public void handle(ExternalServerConnection connection) {
        MultiPaper.runSync(() -> {
            ServerPlayer player = MinecraftServer.getServer().getPlayerList().getPlayer(uuid);

            if (player == null) {
                LOGGER.warn("Tried to reset attack strength of a non-existent player with uuid " + uuid);
                return;
            }

            settingAttackStrength = true;
            player.resetAttackStrengthTicker();
            settingAttackStrength = false;
        });
    }

    public static void broadcastResetAttackStrength(Player player) {
        if (!settingAttackStrength && player instanceof ServerPlayer serverPlayer) {
            MultiPaper.broadcastPacketToExternalServers(serverPlayer, new PlayerResetAttackStrengthPacket(player));
        }
    }
}
