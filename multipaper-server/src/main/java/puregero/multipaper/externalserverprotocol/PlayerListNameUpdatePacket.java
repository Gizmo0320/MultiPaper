package puregero.multipaper.externalserverprotocol;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.game.ClientboundPlayerInfoUpdatePacket;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import puregero.multipaper.ExternalServerConnection;
import puregero.multipaper.MultiPaper;

import java.util.UUID;

public class PlayerListNameUpdatePacket extends ExternalServerPacket {

    private final UUID uuid;
    private final Component listName;

    public PlayerListNameUpdatePacket(ServerPlayer player) {
        this.uuid = player.getUUID();
        this.listName = player.listName;
    }

    public PlayerListNameUpdatePacket(FriendlyByteBuf in) {
        uuid = in.readUUID();
        listName = Component.Serializer.fromJson(in.readUtf());
    }

    @Override
    public void write(FriendlyByteBuf out) {
        out.writeUUID(uuid);
        out.writeUtf(Component.Serializer.toJson(listName));
    }

    @Override
    public void handle(ExternalServerConnection connection) {
        MultiPaper.runSync(() -> {
            ServerPlayer player = MinecraftServer.getServer().getPlayerList().getPlayer(uuid);

            if (player != null) {
                player.listName = listName;

                for (ServerPlayer receiver : player.server.getPlayerList().players) {
                    if (MultiPaper.isRealPlayer(receiver) && receiver.getBukkitEntity().canSee(player.getBukkitEntity())) {
                        receiver.connection.send(new ClientboundPlayerInfoUpdatePacket(ClientboundPlayerInfoUpdatePacket.Action.UPDATE_DISPLAY_NAME, player));
                    }
                }
            }
        });
    }
}
