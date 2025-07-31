package puregero.multipaper;

import io.papermc.paper.chunk.system.scheduling.NewChunkHolder;
import net.minecraft.network.protocol.game.ServerboundInteractPacket;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.entity.projectile.FishingHook;
import net.minecraft.world.level.chunk.LevelChunk;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import puregero.multipaper.externalserverprotocol.PlayerActionOnEntityPacket;
import puregero.multipaper.externalserverprotocol.PlayerTouchEntityPacket;

public class MultiPaperEntityInteractHandler {

    private static final Logger LOGGER = LogManager.getLogger(MultiPaperEntityInteractHandler.class.getSimpleName());

    public static ExternalServerConnection getOwner(Entity entity) {
        if (entity.isFake()) return null;
        NewChunkHolder newChunkHolder = MultiPaper.getChunkHolder(entity);

        if (MultiPaperEntitiesHandler.getControllingPassenger(entity) instanceof ExternalPlayer externalPlayer) {
            return externalPlayer.externalServerConnection;
        } else if (MultiPaper.isChunkExternal(newChunkHolder) && !MultiPaper.isRealPlayer(MultiPaperEntitiesHandler.getControllingPassenger(entity))) {
            return newChunkHolder.externalOwner.getConnection();
        }

        return null;
    }

    public static boolean handleEntityInteract(ServerPlayer player, Entity entity, ServerboundInteractPacket packet) {
        if (entity.isFake()) return false;
        ExternalServerConnection owner = getOwner(entity);
        if (owner != null) {
            owner.send(new PlayerActionOnEntityPacket(player, entity, packet));
            return true;
        }

        return false;
    }

    public static boolean touchEntity(Player player, Entity entity) {
        NewChunkHolder newChunkHolder = MultiPaper.getChunkHolder(entity);
        if (entity.isFake()) return false;
        if (MultiPaper.isRealPlayer(entity)) {
            return false;
        } else if (MultiPaper.isChunkExternal(newChunkHolder) && !(entity instanceof FishingHook)) {
            newChunkHolder.externalOwner.getConnection().send(new PlayerTouchEntityPacket(player, entity));

            return true;
        }

        return false;
    }
}
