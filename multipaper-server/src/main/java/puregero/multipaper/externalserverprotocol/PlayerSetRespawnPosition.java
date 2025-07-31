package puregero.multipaper.externalserverprotocol;

import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.Level;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import puregero.multipaper.ExternalServerConnection;
import puregero.multipaper.MultiPaper;

import java.util.UUID;

public class PlayerSetRespawnPosition extends ExternalServerPacket {

    private static final Logger LOGGER = LogManager.getLogger(PlayerSetRespawnPosition.class.getSimpleName());
    private static boolean settingRespawnPosition = false;

    private final UUID uuid;
    private final ResourceKey<Level> dimension;
    private final BlockPos pos;
    private final float angle;
    private final boolean forced;

    public PlayerSetRespawnPosition(ServerPlayer player) {
        this.uuid = player.getUUID();
        this.dimension = player.respawnDimension;
        this.pos = player.respawnPosition;
        this.angle = player.respawnAngle;
        this.forced = player.respawnForced;
    }

    public PlayerSetRespawnPosition(FriendlyByteBuf in) {
        uuid = in.readUUID();
        dimension = ResourceKey.create(Registries.DIMENSION, new ResourceLocation(in.readUtf()));
        pos = in.readBoolean() ? BlockPos.of(in.readLong()) : null;
        angle = in.readFloat();
        forced = in.readBoolean();
    }

    @Override
    public void write(FriendlyByteBuf out) {
        out.writeUUID(uuid);
        out.writeUtf(dimension.location().toString());

        out.writeBoolean(pos != null);
        if (pos != null) {
            out.writeLong(pos.asLong());
        }

        out.writeFloat(angle);
        out.writeBoolean(forced);
    }

    @Override
    public void handle(ExternalServerConnection connection) {
        MultiPaper.runSync(() -> {
            ServerPlayer player = MinecraftServer.getServer().getPlayerList().getPlayer(uuid);

            if (player == null) {
                LOGGER.warn("Tried to set a respawn position on a non-existent player with uuid " + uuid);
                return;
            }

            settingRespawnPosition = true;
            player.setRespawnPosition(dimension, pos, angle, forced, false);
            settingRespawnPosition = false;
        });
    }

    public static void broadcastRespawnPosition(ServerPlayer player) {
        if (!settingRespawnPosition) {
            MultiPaper.broadcastPacketToExternalServers(player, new PlayerSetRespawnPosition(player));
        }
    }
}
