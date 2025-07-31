package puregero.multipaper.externalserverprotocol;

import io.papermc.paper.chunk.system.scheduling.NewChunkHolder;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.Vec3;
import org.bukkit.Bukkit;
import org.bukkit.World;
import org.bukkit.craftbukkit.CraftWorld;
import puregero.multipaper.ExternalPlayer;
import puregero.multipaper.ExternalServerConnection;
import puregero.multipaper.MultiPaper;
import puregero.multipaper.MultiPaperEntitiesHandler;

import java.util.UUID;

public class AddDeltaMovementPacket extends ExternalServerPacket {
    private static boolean handlingPacket = false;
    private final UUID world;
    private final UUID entityUUID;
    private final Vec3 velocity;

    public AddDeltaMovementPacket(ServerLevel level, Entity entity, Vec3 velocity) {
        this.world = level.getWorld().getUID();
        this.entityUUID = entity.getUUID();
        this.velocity = velocity;
    }

    public static void broadcast(Entity entity, Vec3 velocity) {
        if (!handlingPacket) {
            Entity controller = MultiPaperEntitiesHandler.getControllingPassenger(entity);
            if (controller instanceof ExternalPlayer externalPlayer) {
                externalPlayer.externalServerConnection.send(new AddDeltaMovementPacket((ServerLevel) entity.level(), entity, velocity));
            } else {
                NewChunkHolder newChunkHolder = MultiPaper.getChunkHolder(entity);
                if (newChunkHolder != null && newChunkHolder.externalOwner != null && newChunkHolder.externalOwner.getConnection() != null) {
                    newChunkHolder.externalOwner.getConnection().send(new AddDeltaMovementPacket((ServerLevel) entity.level(), entity, velocity));
                }
            }
        }
    }

    public AddDeltaMovementPacket(FriendlyByteBuf in) {
        this.world = in.readUUID();
        this.entityUUID = in.readUUID();
        this.velocity = new Vec3(in.readDouble(), in.readDouble(), in.readDouble());
    }

    @Override
    public void write(FriendlyByteBuf out) {
        out.writeUUID(this.world);
        out.writeUUID(this.entityUUID);
        out.writeDouble(this.velocity.x);
        out.writeDouble(this.velocity.y);
        out.writeDouble(this.velocity.z);
    }

    @Override
    public void handle(ExternalServerConnection connection) {
        MultiPaper.runSync(() -> {
            handlingPacket = true;
            World world = Bukkit.getWorld(this.world);
            if (world instanceof CraftWorld craftWorld) {
                Entity entity = craftWorld.getHandle().getEntity(entityUUID);
                if (entity != null) {
                    entity.addDeltaMovement(velocity);
                }
            }
            handlingPacket = false;
        });
    }
}
