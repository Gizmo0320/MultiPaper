package puregero.multipaper.externalserverprotocol;

import net.minecraft.core.BlockPos;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.ai.goal.WrappedGoal;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.bukkit.Bukkit;
import org.bukkit.craftbukkit.CraftWorld;
import puregero.multipaper.ExternalServerConnection;
import puregero.multipaper.MultiPaper;

import java.util.UUID;

public class MobSetNavigationGoalPacket extends ExternalServerPacket {

    private static final Logger LOGGER = LogManager.getLogger(MobSetNavigationGoalPacket.class.getSimpleName());

    private final String world;
    private final UUID uuid;
    private final BlockPos goal;
    private final double speed;

    public MobSetNavigationGoalPacket(Mob mob, BlockPos goal) {
        this.world = ((ServerLevel) mob.level()).convertable.getLevelId();
        this.uuid = mob.getUUID();
        this.goal = goal;
        this.speed = mob.getNavigation().speedModifier;
    }

    public MobSetNavigationGoalPacket(FriendlyByteBuf in) {
        this.world = in.readUtf();
        this.uuid = in.readUUID();
        this.goal = BlockPos.of(in.readLong());
        this.speed = in.readDouble();
    }

    @Override
    public void write(FriendlyByteBuf out) {
        out.writeUtf(world);
        out.writeUUID(uuid);
        out.writeLong(goal.asLong());
        out.writeDouble(speed);
    }

    @Override
    public void handle(ExternalServerConnection connection) {
        MultiPaper.runSync(() -> {
            ServerLevel level = ((CraftWorld) Bukkit.getWorld(world)).getHandle();

            if (!level.isLoaded(goal)) {
                // Chunk is not loaded, don't bother since this will force the chunk to be loaded in sync,
                // just find a new goal naturally
                return;
            }

            Entity entity = level.getEntity(uuid);
            if (entity instanceof Mob mob) {
                mob.goalSelector.getRunningGoals().forEach(WrappedGoal::stop);
                mob.targetSelector.getRunningGoals().forEach(WrappedGoal::stop);
                mob.getNavigation().moveTo(mob.getNavigation().createPath(goal, 0), speed);
            } else {
                LOGGER.warn("Couldn't find mob " + uuid + " for navigation goal");
            }
        });
    }
}
