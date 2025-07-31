package puregero.multipaper.externalserverprotocol;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import puregero.multipaper.ExternalServerConnection;
import puregero.multipaper.MultiPaper;

import java.util.UUID;

public class PlayerUseBlockPacket extends ExternalServerPacket {

    private static final Logger LOGGER = LogManager.getLogger(PlayerUseBlockPacket.class.getSimpleName());

    private final UUID uuid;
    private final BlockPos pos;

    public PlayerUseBlockPacket(Player player, BlockPos blockPos) {
        this.uuid = player.getUUID();
        this.pos = blockPos;
    }

    public PlayerUseBlockPacket(FriendlyByteBuf in) {
        uuid = in.readUUID();
        pos = BlockPos.of(in.readLong());
    }

    @Override
    public void write(FriendlyByteBuf out) {
        out.writeUUID(uuid);
        out.writeLong(pos.asLong());
    }

    @Override
    public void handle(ExternalServerConnection connection) {
        MultiPaper.runSync(() -> {
            ServerPlayer player = MinecraftServer.getServer().getPlayerList().getPlayer(uuid);

            if (player == null) {
                LOGGER.warn("Tried to use a block " + pos + " with a non-existent player of uuid " + uuid);
                return;
            }

            player.level().getBlockState(pos).use(player.level(), player, InteractionHand.MAIN_HAND, new BlockHitResult(new Vec3(pos.getX(), pos.getY(), pos.getZ()), Direction.NORTH, pos, false));
        });
    }
}
