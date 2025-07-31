package puregero.multipaper.externalserverprotocol;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.players.IpBanListEntry;
import puregero.multipaper.ExternalServerConnection;
import puregero.multipaper.MultiPaper;
import puregero.multipaper.config.MultiPaperConfiguration;

import java.util.Date;

public class BanIpPacket extends ExternalServerPacket {

    private static boolean handlingPacket = false;
    private final String ip;
    private final Date created;
    private final Date expires;
    private final String reason;
    private final String source;

    public BanIpPacket(String ip, Date created, Date expires, String reason, String source) {
        this.ip = ip;
        this.created = created;
        this.expires = expires;
        this.reason = reason;
        this.source = source;
    }

    public static void broadcast(String ip, Date created, Date expires, String reason, String source) {
        if (!handlingPacket && MultiPaperConfiguration.get().syncSettings.syncJsonFiles) {
            MultiPaper.broadcastPacketToExternalServers(new BanIpPacket(ip, created, expires, reason, source));
        }
    }

    public BanIpPacket(FriendlyByteBuf in) {
        ip = in.readBoolean() ? in.readUtf() : null;
        created = in.readBoolean() ? in.readDate() : null;
        expires = in.readBoolean() ? in.readDate() : null;
        reason = in.readBoolean() ? in.readUtf() : null;
        source = in.readBoolean() ? in.readUtf() : null;
    }

    @Override
    public void write(FriendlyByteBuf out) {
        out.writeBoolean(ip != null);
        if (ip != null) {
            out.writeUtf(ip);
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
            MinecraftServer.getServer().getPlayerList().getIpBans().add(new IpBanListEntry(ip, created, source, expires, reason));
            handlingPacket = false;
        });
    }
}
