package puregero.multipaper.externalserverprotocol;

import com.mojang.authlib.GameProfile;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.MinecraftServer;
import puregero.multipaper.ExternalServerConnection;
import puregero.multipaper.MultiPaper;
import puregero.multipaper.config.MultiPaperConfiguration;

import java.util.UUID;

public class RemoveWhiteListedPlayerPacket extends ExternalServerPacket {

    private static boolean handlingPacket = false;
    private final String name;
    private final UUID uuid;

    public RemoveWhiteListedPlayerPacket(String name, UUID uuid) {
        this.name = name;
        this.uuid = uuid;
    }

    public static void broadcast(String name, UUID uuid) {
        if (!handlingPacket && MultiPaperConfiguration.get().syncSettings.syncJsonFiles) {
            MultiPaper.broadcastPacketToExternalServers(new RemoveWhiteListedPlayerPacket(name, uuid));
        }
    }

    public RemoveWhiteListedPlayerPacket(FriendlyByteBuf in) {
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
            MinecraftServer.getServer().getPlayerList().getWhiteList().remove(new GameProfile(uuid, name));
            handlingPacket = false;
        });
    }
}
