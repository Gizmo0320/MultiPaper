package puregero.multipaper.externalserverprotocol;

import com.mojang.authlib.GameProfile;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.players.ServerOpListEntry;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import puregero.multipaper.ExternalServerConnection;
import puregero.multipaper.MultiPaper;
import puregero.multipaper.config.MultiPaperConfiguration;

import java.util.UUID;

public class OpPlayerPacket extends ExternalServerPacket {

    private static boolean handlingPacket = false;
    private final String name;
    private final UUID uuid;
    private final int level;
    private final boolean bypassPlayerLimit;

    public OpPlayerPacket(String name, UUID uuid, int level, boolean bypassPlayerLimit) {
        this.name = name;
        this.uuid = uuid;
        this.level = level;
        this.bypassPlayerLimit = bypassPlayerLimit;
    }

    public static void broadcast(String name, UUID uuid, int level, boolean bypassPlayerLimit) {
        if (!handlingPacket && MultiPaperConfiguration.get().syncSettings.syncJsonFiles) {
            MultiPaper.broadcastPacketToExternalServers(new OpPlayerPacket(name, uuid, level, bypassPlayerLimit));
        }
    }

    public OpPlayerPacket(FriendlyByteBuf in) {
        name = in.readBoolean() ? in.readUtf() : null;
        uuid = in.readBoolean() ? in.readUUID() : null;
        level = in.readInt();
        bypassPlayerLimit = in.readBoolean();
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

        out.writeInt(level);

        out.writeBoolean(bypassPlayerLimit);
    }

    @Override
    public void handle(ExternalServerConnection connection) {
        MultiPaper.runSync(() -> {
            handlingPacket = true;
            Player player = Bukkit.getPlayer(uuid);
            if (player != null) {
                player.setOp(true);
            } else {
                MinecraftServer.getServer().getPlayerList().getOps().add(new ServerOpListEntry(new GameProfile(uuid, name), level, bypassPlayerLimit));
            }
            handlingPacket = false;
        });
    }
}
