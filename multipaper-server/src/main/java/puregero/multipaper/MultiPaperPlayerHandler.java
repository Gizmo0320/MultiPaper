package puregero.multipaper;

import net.minecraft.network.protocol.game.ServerboundClientInformationPacket;
import net.minecraft.network.protocol.game.ServerboundPlayerAbilitiesPacket;
import net.minecraft.network.protocol.game.ServerboundSetCarriedItemPacket;
import net.minecraft.server.level.ServerPlayer;
import puregero.multipaper.externalserverprotocol.PlayerActionPacket;

public class MultiPaperPlayerHandler {
    public static void handlePlayerAbilities(ServerPlayer player, ServerboundPlayerAbilitiesPacket packet) {
        if (MultiPaper.isRealPlayer(player)) {
            MultiPaper.broadcastPacketToExternalServers(player, new PlayerActionPacket(player, packet));
        }
    }

    public static void handleClientInformation(ServerPlayer player, ServerboundClientInformationPacket packet) {
        if (MultiPaper.isRealPlayer(player)) {
            MultiPaper.broadcastPacketToExternalServers(player, new PlayerActionPacket(player, packet));
        }
    }
}
