package puregero.multipaper.externalserverprotocol;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Player;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import puregero.multipaper.ExternalServerConnection;
import puregero.multipaper.MultiPaper;

import java.util.UUID;

public class PlayerFoodUpdatePacket extends ExternalServerPacket {
    private static final Logger LOGGER = LogManager.getLogger(PlayerFoodUpdatePacket.class.getSimpleName());

    private final UUID uuid;
    private final int foodLevel;
    private final float saturationLevel;

    public PlayerFoodUpdatePacket(Player player) {
        this.uuid = player.getUUID();
        this.foodLevel = player.getFoodData().foodLevel;
        this.saturationLevel = player.getFoodData().saturationLevel;
    }

    public PlayerFoodUpdatePacket(FriendlyByteBuf in) {
        uuid = in.readUUID();
        foodLevel = in.readShort();
        saturationLevel = in.readFloat();
    }

    @Override
    public void write(FriendlyByteBuf out) {
        out.writeUUID(uuid);
        out.writeShort(foodLevel);
        out.writeFloat(saturationLevel);
    }

    @Override
    public void handle(ExternalServerConnection connection) {
        MultiPaper.runSync(() -> {
            ServerPlayer player = MinecraftServer.getServer().getPlayerList().getPlayer(uuid);

            if (player == null) {
                LOGGER.warn("Could not find player " + uuid);
                return;
            }

            player.getFoodData().foodLevel = foodLevel;
            player.getFoodData().saturationLevel = saturationLevel;
        });
    }
}
