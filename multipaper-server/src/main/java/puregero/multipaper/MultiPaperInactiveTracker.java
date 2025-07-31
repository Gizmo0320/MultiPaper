package puregero.multipaper;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import puregero.multipaper.config.MultiPaperConfiguration;

import java.util.Iterator;
import java.util.LinkedList;
import java.util.Queue;

public class MultiPaperInactiveTracker {
    private final ServerLevel level;
    private final Queue<Entity> entitiesToSendChangesThisTick = new LinkedList<>();

    public MultiPaperInactiveTracker(ServerLevel level) {
        this.level = level;
    }

    public void track(Entity entity) {
        if (MultiPaperConfiguration.get().optimizations.ticksPerInactiveEntityTracking > 1 && !entity.sendChangesThisTick && entity.tracker != null) {
            entity.sendChangesThisTick = true;
            entitiesToSendChangesThisTick.add(entity);
        }
    }

    public boolean tick() {
        int tickCount = level.getServer().getTickCount();
        if (MultiPaperConfiguration.get().optimizations.ticksPerInactiveEntityTracking > 1 && tickCount % MultiPaperConfiguration.get().optimizations.ticksPerInactiveEntityTracking != 0) {
            level.timings.trackerInactive.startTiming();
            try {
                Iterator<Entity> iterator = entitiesToSendChangesThisTick.iterator();
                while (iterator.hasNext()) {
                    Entity entity = iterator.next();

                    if (entity.tracker == null) {
                        entity.sendChangesThisTick = false;
                        iterator.remove();
                        continue;
                    }

                    if (tickCount - entity.tracker.serverEntity.lastChangesSent >= entity.tracker.serverEntity.updateInterval) {
                        entity.tracker.serverEntity.lastChangesSent = tickCount;
                        entity.tracker.updatePlayers(entity.getPlayersInTrackRange());
                        entity.tracker.serverEntity.sendChanges();
                        entity.sendChangesThisTick = false;
                        iterator.remove();
                    }
                }
            } finally {
                level.timings.trackerInactive.stopTiming();
            }
            return true;
        }

        return false;
    }
}
