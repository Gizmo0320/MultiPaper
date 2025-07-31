package puregero.multipaper.externalserverprotocol;

import net.kyori.adventure.text.Component;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.bukkit.Bukkit;
import org.bukkit.event.player.PlayerKickEvent;
import puregero.multipaper.ExternalServerConnection;
import puregero.multipaper.MultiPaper;
import puregero.multipaper.config.MultiPaperConfiguration;
import puregero.multipaper.event.player.PlayerLeaveExternalServerEvent;

import java.util.UUID;

public class PlayerRemovePacket extends ExternalServerPacket {

    public static Component EXTERNAL_DISCONNECT_COMPONENT = Component.text("Disconnected from external server");
    public static Component LOGGED_IN_FROM_ANOTHER_LOCATION = Component.text("Logged in from another location");
    private static final Logger LOGGER = LogManager.getLogger(PlayerRemovePacket.class.getSimpleName());

    private final UUID uuid;

    public PlayerRemovePacket(ServerPlayer player) {
        this.uuid = player.getUUID();
    }

    public PlayerRemovePacket(FriendlyByteBuf in) {
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
                LOGGER.warn("Tried to remove a non-existent player with uuid " + uuid);
                return;
            }

            player.connection.disconnect(EXTERNAL_DISCONNECT_COMPONENT, PlayerKickEvent.Cause.TIMEOUT);
            PlayerLeaveExternalServerEvent playerLeaveExternalServerEvent = new PlayerLeaveExternalServerEvent(player.getGameProfile().getId(), player.getGameProfile().getName(), MultiPaperConfiguration.get().masterConnection.myName);
            Bukkit.getPluginManager().callEvent(playerLeaveExternalServerEvent);
        });
    }
}
