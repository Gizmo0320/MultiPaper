package puregero.multipaper.externalserverprotocol;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.MinecraftServer;
import puregero.multipaper.ExternalServerConnection;
import puregero.multipaper.MultiPaper;
import puregero.multipaper.config.MultiPaperConfiguration;

public class PardonIpPacket extends ExternalServerPacket {

    private static boolean handlingPacket = false;
    private final String ip;

    public PardonIpPacket(String ip) {
        this.ip = ip;
    }

    public static void broadcast(String ip) {
        if (!handlingPacket && MultiPaperConfiguration.get().syncSettings.syncJsonFiles) {
            MultiPaper.broadcastPacketToExternalServers(new PardonIpPacket(ip));
        }
    }

    public PardonIpPacket(FriendlyByteBuf in) {
        ip = in.readBoolean() ? in.readUtf() : null;
    }

    @Override
    public void write(FriendlyByteBuf out) {
        out.writeBoolean(ip != null);
        if (ip != null) {
            out.writeUtf(ip);
        }
    }

    @Override
    public void handle(ExternalServerConnection connection) {
        MultiPaper.runSync(() -> {
            handlingPacket = true;
            MinecraftServer.getServer().getPlayerList().getIpBans().remove(ip);
            handlingPacket = false;
        });
    }
}
