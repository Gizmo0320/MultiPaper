package puregero.multipaper.externalserverprotocol;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.level.ServerPlayer;
import org.bukkit.Bukkit;
import org.bukkit.World;
import org.bukkit.craftbukkit.CraftWorld;
import puregero.multipaper.ExternalServerConnection;
import puregero.multipaper.MultiPaper;

public class SubscribeToWorldPacket extends ExternalServerPacket {

    private final String world;

    public SubscribeToWorldPacket(String world) {
        this.world = world;
    }

    public SubscribeToWorldPacket(FriendlyByteBuf in) {
        world = in.readUtf();
    }

    @Override
    public void write(FriendlyByteBuf out) {
        out.writeUtf(world);
    }

    @Override
    public void handle(ExternalServerConnection connection) {
        MultiPaper.runSync(() -> {
            World bukkitWorld = Bukkit.getWorld(world);
            if (connection.subscribedWorlds.add(world) && bukkitWorld instanceof CraftWorld craftWorld) {
                onWorldSubscribe(connection, craftWorld);
            }
        });
    }

    private void onWorldSubscribe(ExternalServerConnection connection, CraftWorld craftWorld) {
        for (ServerPlayer player : craftWorld.getHandle().players()) {
            if (MultiPaper.isRealPlayer(player)) {
                PlayerCreatePacket.sendPlayer(player, connection);
            }
        }
    }
}
