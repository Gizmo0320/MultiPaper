package puregero.multipaper.externalserverprotocol;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import net.minecraft.core.BlockPos;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.Entity;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.bukkit.Bukkit;
import org.bukkit.block.Block;
import org.bukkit.craftbukkit.CraftWorld;
import org.bukkit.craftbukkit.block.CraftBlock;
import org.bukkit.craftbukkit.event.CraftEventFactory;
import puregero.multipaper.ExternalServerConnection;
import puregero.multipaper.MultiPaper;
import puregero.multipaper.util.DamageSourceSerializer;

import java.io.*;
import java.util.UUID;

public class HurtEntityPacket extends ExternalServerPacket {

    private static final Logger LOGGER = LogManager.getLogger(HurtEntityPacket.class.getSimpleName());

    private final String world;
    private final UUID uuid;
    private final ByteBuf sourceBytes;
    private final float amount;
    private final UUID entityDamageUuid;
    private final BlockPos blockDamagePos;

    public HurtEntityPacket(Entity entity, DamageSource source, float amount, Entity entityDamage, Block blockDamage) {
        this.world = ((ServerLevel) entity.level()).convertable.getLevelId();
        this.uuid = entity.getUUID();
        this.amount = amount;
        this.entityDamageUuid = entityDamage == null ? null : entityDamage.getUUID();
        this.blockDamagePos = blockDamage == null ? null : ((CraftBlock) blockDamage).getPosition();

        try {
            FriendlyByteBuf out = new FriendlyByteBuf(Unpooled.buffer());
            DamageSourceSerializer.serialize(source, out);
            sourceBytes = out;
        } catch (IOException e) {
            throw new RuntimeException(e);
        }

    }

    public HurtEntityPacket(FriendlyByteBuf in) {
        world = in.readUtf();
        uuid = in.readUUID();
        amount = in.readFloat();

        if (in.readBoolean()) {
            entityDamageUuid = in.readUUID();
        } else {
            entityDamageUuid = null;
        }

        if (in.readBoolean()) {
            blockDamagePos = BlockPos.of(in.readLong());
        } else {
            blockDamagePos = null;
        }

        sourceBytes = in.readBytes(in.readableBytes());
    }

    @Override
    public void write(FriendlyByteBuf out) {
        out.writeUtf(world);
        out.writeUUID(uuid);
        out.writeFloat(amount);

        out.writeBoolean(entityDamageUuid != null);
        if (entityDamageUuid != null) {
            out.writeUUID(entityDamageUuid);
        }

        out.writeBoolean(blockDamagePos != null);
        if (blockDamagePos != null) {
            out.writeLong(blockDamagePos.asLong());
        }

        out.writeBytes(sourceBytes);
    }

    @Override
    public void handle(ExternalServerConnection connection) {
        MultiPaper.runSync(() -> {
            try {
                ServerLevel level = ((CraftWorld) Bukkit.getWorld(world)).getHandle();
                Entity entity = level.getEntity(uuid);
                DamageSource source = DamageSourceSerializer.deserialize(entity, level, new FriendlyByteBuf(sourceBytes));
                sourceBytes.release();

                if (entity == null) {
                    LOGGER.warn("Could not find entity " + uuid + " for damage source " + source.getMsgId());
                    return;
                }

                Entity entityDamage = entityDamageUuid == null ? null : level.getEntity(entityDamageUuid);
                Block blockDamage = blockDamagePos == null ? null : CraftBlock.at(level, blockDamagePos);

                CraftEventFactory.entityDamage = entityDamage;
                CraftEventFactory.blockDamage = blockDamage;

                entity.hurt(source, amount);

                CraftEventFactory.entityDamage = null;
                CraftEventFactory.blockDamage = null;
            } catch (IOException e) {
                throw new RuntimeException(e);
            }
        });
    }
}
