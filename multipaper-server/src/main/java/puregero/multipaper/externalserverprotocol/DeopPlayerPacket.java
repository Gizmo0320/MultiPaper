package puregero.multipaper.externalserverprotocol;

import com.mojang.authlib.GameProfile;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.MinecraftServer;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import puregero.multipaper.ExternalServerConnection;
import puregero.multipaper.MultiPaper;
import puregero.multipaper.config.MultiPaperConfiguration;

import java.util.UUID;

public class DeopPlayerPacket extends ExternalServerPacket {

    private static boolean handlingPacket = false;
    private final String name;
    private final UUID uuid;

    public DeopPlayerPacket(String name, UUID uuid) {
        this.name = name;
        this.uuid = uuid;
    }

    public static void broadcast(String name, UUID uuid) {
        if (!handlingPacket && MultiPaperConfiguration.get().syncSettings.syncJsonFiles) {
            MultiPaper.broadcastPacketToExternalServers(new DeopPlayerPacket(name, uuid));
        }
    }

    public DeopPlayerPacket(FriendlyByteBuf in) {
        name = in.readBoolean() ? in.readUtf() : null;
        uuid = in.readBoolean() ? in.readUUID() : null;
    }

    @Override
    public void write(FriendlyByteBuf out) {
        out.writeBoolean(name != null);
        if (name != null) {
            out.writeUtf(name);
        }

        out.writeBoolean(uuid != null);
        if (uuid != null) {
            out.writeUUID(uuid);
        }
    }

    @Override
    public void handle(ExternalServerConnection connection) {
        MultiPaper.runSync(() -> {
            handlingPacket = true;
            Player player = Bukkit.getPlayer(uuid);
            if (player != null) {
                player.setOp(false);
            } else {
                MinecraftServer.getServer().getPlayerList().getOps().remove(new GameProfile(uuid, name));
            }
            handlingPacket = false;
        });
    }
}
