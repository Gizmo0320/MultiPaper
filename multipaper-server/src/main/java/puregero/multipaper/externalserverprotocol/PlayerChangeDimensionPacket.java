package puregero.multipaper.externalserverprotocol;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import org.bukkit.Bukkit;
import org.bukkit.craftbukkit.CraftWorld;
import puregero.multipaper.ExternalServerConnection;
import puregero.multipaper.MultiPaper;

import java.util.UUID;

public class PlayerChangeDimensionPacket extends ExternalServerPacket {

    private final UUID uuid;
    private final UUID world;
    private final double x;
    private final double y;
    private final double z;
    private final boolean reset;

    public PlayerChangeDimensionPacket(ServerPlayer player, boolean reset) {
        this.uuid = player.getUUID();
        this.world = player.level().getWorld().getUID();
        this.x = player.getX();
        this.y = player.getY();
        this.z = player.getZ();
        this.reset = reset;
    }

    public PlayerChangeDimensionPacket(FriendlyByteBuf in) {
        uuid = in.readUUID();
        world = in.readUUID();
        x = in.readDouble();
        y = in.readDouble();
        z = in.readDouble();
        reset = in.readBoolean();
    }

    @Override
    public void write(FriendlyByteBuf out) {
        out.writeUUID(uuid);
        out.writeUUID(world);
        out.writeDouble(x);
        out.writeDouble(y);
        out.writeDouble(z);
        out.writeBoolean(reset);
    }

    @Override
    public void handle(ExternalServerConnection connection) {
        MultiPaper.runSync(() -> {
            ServerLevel level = ((CraftWorld) Bukkit.getWorld(world)).getHandle();
            ServerPlayer player = MinecraftServer.getServer().getPlayerList().getPlayer(uuid);

            // Remove from old world
            ((ServerLevel) player.level()).removePlayerImmediately(player, Entity.RemovalReason.DISCARDED);

            player.setPosRaw(x, y, z);

            if (reset) {
                player.keepLevel = false;
                player.newLevel = 0;
                player.newTotalExp = 0;
                player.expToDrop = 0;
                player.newExp = 0;

                player.reset();
            }

            // Add to new world
            player.setServerLevel(level);
            player.unsetRemoved();
            level.addRespawnedPlayer(player);
        });
    }
}
