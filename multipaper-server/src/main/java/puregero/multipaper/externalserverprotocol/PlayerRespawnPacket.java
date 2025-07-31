package puregero.multipaper.externalserverprotocol;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.craftbukkit.CraftWorld;
import org.bukkit.event.player.PlayerRespawnEvent;
import puregero.multipaper.ExternalServerConnection;
import puregero.multipaper.MultiPaper;

import java.util.UUID;

public class PlayerRespawnPacket extends ExternalServerPacket {

    private final UUID uuid;
    private final String world;
    private final boolean alive;
    private final double x;
    private final double y;
    private final double z;
    private final float yaw;
    private final float pitch;
    private final boolean avoidSuffocation;
    private final PlayerRespawnEvent.RespawnFlag[] respawnFlags;

    public PlayerRespawnPacket(ServerPlayer player, ServerLevel worldserver, boolean alive, Location location, boolean avoidSuffocation, PlayerRespawnEvent.RespawnFlag[] respawnFlags) {
        this.uuid = player.getUUID();
        this.world = worldserver.convertable.getLevelId();
        this.alive = alive;
        this.x = location.getX();
        this.y = location.getY();
        this.z = location.getZ();
        this.yaw = location.getYaw();
        this.pitch = location.getPitch();
        this.avoidSuffocation = avoidSuffocation;
        this.respawnFlags = respawnFlags;
    }

    public PlayerRespawnPacket(FriendlyByteBuf in) {
        uuid = in.readUUID();
        world = in.readUtf();
        alive = in.readBoolean();
        x = in.readDouble();
        y = in.readDouble();
        z = in.readDouble();
        yaw = in.readFloat();
        pitch = in.readFloat();
        avoidSuffocation = in.readBoolean();

        respawnFlags = new PlayerRespawnEvent.RespawnFlag[in.readInt()];
        for (int i = 0; i < respawnFlags.length; i++) {
            respawnFlags[i] = PlayerRespawnEvent.RespawnFlag.valueOf(in.readUtf());
        }
    }

    @Override
    public void write(FriendlyByteBuf out) {
        out.writeUUID(uuid);
        out.writeUtf(world);
        out.writeBoolean(alive);
        out.writeDouble(x);
        out.writeDouble(y);
        out.writeDouble(z);
        out.writeFloat(yaw);
        out.writeFloat(pitch);
        out.writeBoolean(avoidSuffocation);

        out.writeInt(respawnFlags.length);
        for (PlayerRespawnEvent.RespawnFlag flag : respawnFlags) {
            out.writeUtf(flag.name());
        }
    }

    @Override
    public void handle(ExternalServerConnection connection) {
        MultiPaper.runSync(() -> {
            ServerLevel level = ((CraftWorld) Bukkit.getWorld(world)).getHandle();
            ServerPlayer player = MinecraftServer.getServer().getPlayerList().getPlayer(uuid);

            level.getServer().getPlayerList().respawn(player, level, alive, new Location(level.getWorld(), x, y, z, yaw, pitch), avoidSuffocation, PlayerRespawnEvent.RespawnReason.DEATH, respawnFlags);
        });
    }
}
