package puregero.multipaper.externalserverprotocol;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.Difficulty;
import org.bukkit.Bukkit;
import org.bukkit.World;
import org.bukkit.craftbukkit.CraftWorld;
import puregero.multipaper.ExternalServerConnection;
import puregero.multipaper.MultiPaper;

public class DifficultyUpdatePacket extends ExternalServerPacket {

    public static boolean updatingDifficulty = false;

    private final String world;
    private final String difficulty;

    public DifficultyUpdatePacket(ServerLevel level) {
        this.world = level.getWorld().getName();
        this.difficulty = level.serverLevelData.getDifficulty().getKey();
    }

    public DifficultyUpdatePacket(FriendlyByteBuf in) {
        world = in.readUtf();
        difficulty = in.readUtf();
    }

    @Override
    public void write(FriendlyByteBuf out) {
        out.writeUtf(world);
        out.writeUtf(difficulty);
    }

    @Override
    public void handle(ExternalServerConnection connection) {
        MultiPaper.runSync(() -> {
            World bukkitWorld = Bukkit.getWorld(world);
            if (bukkitWorld instanceof CraftWorld craftWorld) {
                updatingDifficulty = true;
                craftWorld.getHandle().getServer().setDifficulty(craftWorld.getHandle(), Difficulty.byName(difficulty), true);
                updatingDifficulty = false;
            }
        });
    }
}
