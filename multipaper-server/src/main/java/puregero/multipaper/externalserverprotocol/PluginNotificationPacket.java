package puregero.multipaper.externalserverprotocol;

import net.minecraft.network.FriendlyByteBuf;
import org.bukkit.Bukkit;
import org.bukkit.craftbukkit.CraftServer;
import puregero.multipaper.ExternalServerConnection;
import puregero.multipaper.MultiPaper;

public class PluginNotificationPacket extends ExternalServerPacket {
    private final String channel;
    private final byte[] data;

    public PluginNotificationPacket(String channel, byte[] data) {
        this.channel = channel;
        this.data = data;
    }

    public PluginNotificationPacket(FriendlyByteBuf in) {
        channel = in.readUtf();
        data = in.readByteArray();
    }

    @Override
    public void write(FriendlyByteBuf out) {
        out.writeUtf(channel);
        out.writeByteArray(data);
    }

    @Override
    public void handle(ExternalServerConnection connection) {
        MultiPaper.runSync(() -> ((CraftServer) Bukkit.getServer()).getMultiPaperNotificationManager().onNotification(connection, channel, data));
    }
}
