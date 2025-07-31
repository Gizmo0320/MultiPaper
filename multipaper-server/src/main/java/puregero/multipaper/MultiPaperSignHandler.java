package puregero.multipaper;

import io.papermc.paper.chunk.system.scheduling.NewChunkHolder;
import net.minecraft.network.protocol.game.ServerboundSignUpdatePacket;
import net.minecraft.server.level.ServerPlayer;
import puregero.multipaper.externalserverprotocol.PlayerActionPacket;

public class MultiPaperSignHandler {

    /**
     * Returns true if the sign update should be cancelled
     */
    public static boolean handleSignUpdate(ServerPlayer player, ServerboundSignUpdatePacket packet) {
        NewChunkHolder newChunkHolder = MultiPaper.getChunkHolder(player);
        if (MultiPaper.isChunkExternal(newChunkHolder)) {
            newChunkHolder.externalOwner.getConnection().send(new PlayerActionPacket(player, packet));
            return true;
        }

        return false;
    }

}
