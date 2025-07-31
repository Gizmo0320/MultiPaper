package puregero.multipaper.externalserverprotocol;

import com.mojang.authlib.GameProfile;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.players.UserBanListEntry;
import puregero.multipaper.ExternalServerConnection;
import puregero.multipaper.MultiPaper;
import puregero.multipaper.config.MultiPaperConfiguration;

import java.util.Date;
import java.util.UUID;

public class BanPlayerPacket extends ExternalServerPacket {

    private static boolean handlingPacket = false;
    private final String name;
    private final UUID uuid;
    private final Date created;
    private final Date expires;
    private final String reason;
    private final String source;

    public BanPlayerPacket(String name, UUID uuid, Date created, Date expires, String reason, String source) {
        this.name = name;
        this.uuid = uuid;
        this.created = created;
        this.expires = expires;
        this.reason = reason;
        this.source = source;
    }

    public static void broadcast(String name, UUID uuid, Date created, Date expires, String reason, String source) {
        if (!handlingPacket && MultiPaperConfiguration.get().syncSettings.syncJsonFiles) {
            MultiPaper.broadcastPacketToExternalServers(new BanPlayerPacket(name, uuid, created, expires, reason, source));
        }
    }

    public BanPlayerPacket(FriendlyByteBuf in) {
        name = in.readBoolean() ? in.readUtf() : null;
        uuid = in.readBoolean() ? in.readUUID() : null;
        created = in.readBoolean() ? in.readDate() : null;
        expires = in.readBoolean() ? in.readDate() : null;
        reason = in.readBoolean() ? in.readUtf() : null;
        source = in.readBoolean() ? in.readUtf() : null;
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

        out.writeBoolean(created != null);
        if (created != null) {
            out.writeDate(created);
        }

        out.writeBoolean(expires != null);
        if (expires != null) {
            out.writeDate(expires);
        }

        out.writeBoolean(reason != null);
        if (reason != null) {
            out.writeUtf(reason);
        }

        out.writeBoolean(source != null);
        if (source != null) {
            out.writeUtf(source);
        }
    }

    @Override
    public void handle(ExternalServerConnection connection) {
        MultiPaper.runSync(() -> {
            handlingPacket = true;
            MinecraftServer.getServer().getPlayerList().getBans().add(new UserBanListEntry(new GameProfile(uuid, name), created, source, expires, reason));
            handlingPacket = false;
        });
    }
}
