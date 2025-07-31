package puregero.multipaper.externalserverprotocol;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.Difficulty;
import org.bukkit.Bukkit;
import org.bukkit.World;
import org.bukkit.craftbukkit.CraftWorld;
import puregero.multipaper.ExternalServerConnection;
import puregero.multipaper.MultiPaper;

public class WhitelistTogglePacket extends ExternalServerPacket {

    public static boolean updatingWhitelistToggle = false;

    private final boolean whitelistEnabled;

    public WhitelistTogglePacket(boolean whitelistEnabled) {
        this.whitelistEnabled = whitelistEnabled;
    }

    public WhitelistTogglePacket(FriendlyByteBuf in) {
        whitelistEnabled = in.readBoolean();
    }

    @Override
    public void write(FriendlyByteBuf out) {
        out.writeBoolean(whitelistEnabled);
    }

    @Override
    public void handle(ExternalServerConnection connection) {
        MultiPaper.runSync(() -> {
            updatingWhitelistToggle = true;
            MinecraftServer.getServer().getPlayerList().setUsingWhiteList(whitelistEnabled);
            updatingWhitelistToggle = false;
        });
    }
}
