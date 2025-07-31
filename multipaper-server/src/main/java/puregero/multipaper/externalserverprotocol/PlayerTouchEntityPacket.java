package puregero.multipaper.externalserverprotocol;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import puregero.multipaper.ExternalServerConnection;
import puregero.multipaper.MultiPaper;

import java.util.UUID;

public class PlayerTouchEntityPacket extends ExternalServerPacket {

    private static final Logger LOGGER = LogManager.getLogger(PlayerTouchEntityPacket.class.getSimpleName());

    private final UUID uuid;
    private final UUID entityUuid;

    public PlayerTouchEntityPacket(Player player, Entity entity) {
        this.uuid = player.getUUID();
        this.entityUuid = entity.getUUID();
    }

    public PlayerTouchEntityPacket(FriendlyByteBuf in) {
        uuid = in.readUUID();
        entityUuid = in.readUUID();
    }

    @Override
    public void write(FriendlyByteBuf out) {
        out.writeUUID(uuid);
        out.writeUUID(entityUuid);
    }

    @Override
    public void handle(ExternalServerConnection connection) {
        MultiPaper.runSync(() -> {
            ServerPlayer player = MinecraftServer.getServer().getPlayerList().getPlayer(uuid);

            if (player == null) {
                LOGGER.warn("Tried to run a touch entity on a non-existent player with uuid " + uuid);
                return;
            }

            Entity entity = ((ServerLevel) player.level()).getEntity(entityUuid);

            if (entity == null) {
                Entity.RemovalReason removalReason = EntityRemovePacket.removedEntities.get(entityUuid);
                if (removalReason != null && removalReason.shouldDestroy()) {
                    connection.send(new EntityRemovePacket(player.level().getWorld().getName(), entityUuid, false));
                    return;
                }

                LOGGER.warn(player.getScoreboardName() + " tried to touch a non-existent entity with uuid " + entityUuid + ", requesting it...");
                RequestEntityPacket.requestEntity(connection, ((ServerLevel) player.level()).uuid, entityUuid);
                return;
            }

            entity.playerTouch(player);
        });
    }
}
