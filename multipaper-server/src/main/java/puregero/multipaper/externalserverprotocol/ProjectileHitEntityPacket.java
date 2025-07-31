package puregero.multipaper.externalserverprotocol;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.projectile.Projectile;
import net.minecraft.world.phys.EntityHitResult;
import net.minecraft.world.phys.Vec3;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.bukkit.Bukkit;
import org.bukkit.World;
import org.bukkit.craftbukkit.CraftWorld;
import puregero.multipaper.ExternalServerConnection;
import puregero.multipaper.MultiPaper;

import java.util.UUID;

public class ProjectileHitEntityPacket extends ExternalServerPacket {

    private static final Logger LOGGER = LogManager.getLogger(ProjectileHitEntityPacket.class.getSimpleName());

    private final String world;
    private final UUID uuid;
    private final UUID entityUuid;
    private final Vec3 location;

    public ProjectileHitEntityPacket(Projectile projectile, Entity entity, Vec3 location) {
        this.world = projectile.level().getWorld().getName();
        this.uuid = projectile.getUUID();
        this.entityUuid = entity.getUUID();
        this.location = location;
    }

    public ProjectileHitEntityPacket(FriendlyByteBuf in) {
        world = in.readUtf();
        uuid = in.readUUID();
        entityUuid = in.readUUID();
        location = new Vec3(in.readDouble(), in.readDouble(), in.readDouble());
    }

    @Override
    public void write(FriendlyByteBuf out) {
        out.writeUtf(world);
        out.writeUUID(uuid);
        out.writeUUID(entityUuid);
        out.writeDouble(location.x);
        out.writeDouble(location.y);
        out.writeDouble(location.z);
    }

    @Override
    public void handle(ExternalServerConnection connection) {
        MultiPaper.runSync(() -> {
            World bukkitWorld = Bukkit.getWorld(world);

            if (bukkitWorld instanceof CraftWorld craftWorld) {
                ServerLevel level = craftWorld.getHandle();
                Projectile projectile = (Projectile) level.getEntity(uuid);
                Entity entity = level.getEntity(entityUuid);

                if (projectile == null) {
                    LOGGER.warn("Tried to hit an entity with a projectile, but the projectile " + uuid + " is null");
                    return;
                }

                if (entity == null) {
                    LOGGER.warn("Tried to hit an entity with a projectile " + projectile + ", but the entity " + entityUuid + " is null");
                    return;
                }

                projectile.onHit0(new EntityHitResult(entity, location));
            }
        });
    }
}
