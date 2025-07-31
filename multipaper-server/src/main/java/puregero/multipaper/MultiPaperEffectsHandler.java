package puregero.multipaper;

import io.papermc.paper.chunk.system.scheduling.NewChunkHolder;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import puregero.multipaper.externalserverprotocol.EntityUpdateEffectPacket;
import puregero.multipaper.externalserverprotocol.ExternalServerPacket;

import java.util.function.Supplier;

public class MultiPaperEffectsHandler {
    private static boolean updatingEffects = false;

    public static void onEffectAdd(LivingEntity entity, MobEffectInstance effect) {
        if (updatingEffects) {
            return;
        }

        broadcast(entity, () -> new EntityUpdateEffectPacket(entity, effect, false));
    }

    public static void onEffectRemove(LivingEntity entity, MobEffectInstance effect) {
        if (updatingEffects) {
            return;
        }

        broadcast(entity, () -> new EntityUpdateEffectPacket(entity, effect, true));


    }

    private static void broadcast(LivingEntity entity, Supplier<ExternalServerPacket> packetSupplier) {
        if (entity instanceof ServerPlayer serverPlayer) {
            MultiPaper.broadcastPacketToExternalServers(serverPlayer, packetSupplier.get());
        } else {
            NewChunkHolder newChunkHolder = MultiPaper.getChunkHolder(entity);
            if (newChunkHolder != null) {
                MultiPaper.broadcastPacketToExternalServers(newChunkHolder.externalEntitiesSubscribers, packetSupplier);
            }
        }
    }

    public static void handle(Entity entity, MobEffectInstance effect, boolean remove) {
        updatingEffects = true;
        if (remove) {
            ((LivingEntity) entity).removeEffect(effect.getEffect());
        } else {
            ((LivingEntity) entity).addEffect(effect);
        }
        updatingEffects = false;
    }
}
