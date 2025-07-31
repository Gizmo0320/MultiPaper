package puregero.multipaper.externalserverprotocol;

import net.minecraft.core.BlockPos;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.level.ServerLevel;
import org.bukkit.Bukkit;
import org.bukkit.World;
import org.bukkit.craftbukkit.CraftWorld;
import puregero.multipaper.ExternalServerConnection;
import puregero.multipaper.MultiPaper;

public class SpawnUpdatePacket extends ExternalServerPacket {

    public static boolean updatingSpawn = false;

    private final String world;
    private final BlockPos pos;
    private final float angle;

    public SpawnUpdatePacket(ServerLevel level) {
        this.world = level.getWorld().getName();
        this.pos = new BlockPos(level.levelData.getXSpawn(), level.levelData.getYSpawn(), level.levelData.getZSpawn());
        this.angle = level.levelData.getSpawnAngle();
    }

    public SpawnUpdatePacket(FriendlyByteBuf in) {
        world = in.readUtf();
        pos = BlockPos.of(in.readLong());
        angle = in.readFloat();
    }

    @Override
    public void write(FriendlyByteBuf out) {
        out.writeUtf(world);
        out.writeLong(pos.asLong());
        out.writeFloat(angle);
    }

    @Override
    public void handle(ExternalServerConnection connection) {
        MultiPaper.runSync(() -> {
            World bukkitWorld = Bukkit.getWorld(world);
            if (bukkitWorld instanceof CraftWorld craftWorld) {
                updatingSpawn = true;
                craftWorld.getHandle().setDefaultSpawnPos(pos, angle);
                updatingSpawn = false;
            }
        });
    }
}
