package puregero.multipaper.externalserverprotocol;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import puregero.multipaper.ExternalServerConnection;
import puregero.multipaper.MultiPaper;

import java.util.UUID;

public class PlayerSetCameraPacket extends ExternalServerPacket {

    public static boolean handlingSetCamera = false;

    private final UUID uuid;
    private final UUID uuidCamera;

    public PlayerSetCameraPacket(ServerPlayer player, Entity camera) {
        this.uuid = player.getUUID();
        this.uuidCamera = camera == null ? null : camera.getUUID();
    }

    public PlayerSetCameraPacket(FriendlyByteBuf in) {
        uuid = in.readUUID();
        uuidCamera = in.readBoolean() ? in.readUUID() : null;
    }

    @Override
    public void write(FriendlyByteBuf out) {
        out.writeUUID(uuid);
        out.writeBoolean(uuidCamera != null);
        if (uuidCamera != null) {
            out.writeUUID(uuidCamera);
        }
    }

    @Override
    public void handle(ExternalServerConnection connection) {
        MultiPaper.runSync(() -> {
            handlingSetCamera = true;

            ServerPlayer player = MinecraftServer.getServer().getPlayerList().getPlayer(uuid);
            Entity entity = ((ServerLevel) player.level()).getEntity(uuidCamera);
            player.setCamera(entity);

            handlingSetCamera = false;
        });
    }
}
