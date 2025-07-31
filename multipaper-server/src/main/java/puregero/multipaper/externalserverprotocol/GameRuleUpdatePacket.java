package puregero.multipaper.externalserverprotocol;

import net.minecraft.network.FriendlyByteBuf;
import org.bukkit.Bukkit;
import org.bukkit.World;
import puregero.multipaper.ExternalServerConnection;
import puregero.multipaper.MultiPaper;

public class GameRuleUpdatePacket extends ExternalServerPacket {

    private static boolean updatingGamerules = false;

    private final String world;
    private final String name;
    private final String value;

    public GameRuleUpdatePacket(World world, String name, String value) {
        this.world = world.getName();
        this.name = name;
        this.value = value;
    }

    public GameRuleUpdatePacket(FriendlyByteBuf in) {
        world = in.readUtf();
        name = in.readUtf();
        value = in.readUtf();
    }

    @Override
    public void write(FriendlyByteBuf out) {
        out.writeUtf(world);
        out.writeUtf(name);
        out.writeUtf(value);
    }

    @Override
    public void handle(ExternalServerConnection connection) {
        MultiPaper.runSync(() -> {
            World bukkitWorld = Bukkit.getWorld(world);
            if (bukkitWorld != null) {
                updatingGamerules = true;
                bukkitWorld.setGameRuleValue(name, value);
                updatingGamerules = false;
            }
        });
    }

    public static void onGameRuleChange(World world, String name, String value) {
        if (!updatingGamerules) {
            MultiPaper.broadcastPacketToExternalServers(new GameRuleUpdatePacket(world, name, value));
        }
    }
}
