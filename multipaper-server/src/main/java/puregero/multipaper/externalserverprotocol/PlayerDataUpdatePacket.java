package puregero.multipaper.externalserverprotocol;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import org.bukkit.entity.Player;
import puregero.multipaper.ExternalServerConnection;
import puregero.multipaper.MultiPaper;

import java.util.UUID;

public class PlayerDataUpdatePacket extends ExternalServerPacket {

    private final UUID uuid;
    private final boolean persistent;
    private final String key;
    private final String value;

    public PlayerDataUpdatePacket(Player player, boolean persistent, String key, String value) {
        this.uuid = player.getUniqueId();
        this.persistent = persistent;
        this.key = key;
        this.value = value;
    }

    public PlayerDataUpdatePacket(FriendlyByteBuf in) {
        uuid = in.readUUID();
        persistent = in.readBoolean();
        key = in.readUtf();
        if (in.readBoolean()) {
            value = in.readUtf();
        } else {
            value = null;
        }
    }

    @Override
    public void write(FriendlyByteBuf out) {
        out.writeUUID(uuid);
        out.writeBoolean(persistent);
        out.writeUtf(key);
        out.writeBoolean(value != null);
        if (value != null) {
            out.writeUtf(value);
        }
    }

    @Override
    public void handle(ExternalServerConnection connection) {
        MultiPaper.runSync(() -> {
            ServerPlayer player = MinecraftServer.getServer().getPlayerList().getPlayer(uuid);

            if (player != null) {
                if (value != null) {
                    if (persistent) {
                        player.getBukkitEntity().persistentData.put(key, value);
                    } else {
                        player.getBukkitEntity().data.put(key, value);
                    }
                } else {
                    if (persistent) {
                        player.getBukkitEntity().persistentData.remove(key);
                    } else {
                        player.getBukkitEntity().data.remove(key);
                    }
                }
            }
        });
    }
}
